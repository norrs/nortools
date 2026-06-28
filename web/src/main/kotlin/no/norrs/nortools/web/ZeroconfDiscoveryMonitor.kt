package no.norrs.nortools.web

import no.norrs.nortools.lib.zeroconf.IpFamily
import no.norrs.nortools.lib.zeroconf.LlmnrClient
import no.norrs.nortools.lib.zeroconf.LlmnrRecord
import no.norrs.nortools.lib.zeroconf.MdnsClient
import no.norrs.nortools.lib.zeroconf.MdnsRecord
import no.norrs.nortools.lib.zeroconf.NetbiosNameServiceClient
import no.norrs.nortools.lib.zeroconf.NetbiosResponse
import no.norrs.nortools.lib.zeroconf.SsdpClient
import no.norrs.nortools.lib.zeroconf.SsdpMessage
import no.norrs.nortools.lib.zeroconf.WsDiscoveryClient
import no.norrs.nortools.lib.zeroconf.WsDiscoveryMessage
import no.norrs.nortools.lib.zeroconf.WsDiscoveryMetadata
import no.norrs.nortools.lib.zeroconf.WsDiscoverySoapCodec
import java.net.HttpURLConnection
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object ZeroconfDiscoveryMonitor {
    private val timeout = Duration.ofSeconds(2)
    private val passiveListenTimeout = Duration.ofSeconds(30)
    private val devices = linkedMapOf<String, MutableDashboardDevice>()
    private val hostnameRecords = linkedMapOf<String, MutableHostnameResolution>()
    private val serviceTypes = linkedMapOf<String, MutableServiceTypeInfo>()
    private val events = ArrayDeque<ZeroconfDashboardEvent>()
    private val warnings = ArrayDeque<String>()
    private val stats = linkedMapOf<String, ZeroconfProtocolStat>()
    private val started = AtomicBoolean(false)
    private val passiveStarted = AtomicBoolean(false)
    private val scanning = AtomicBoolean(false)
    private var lastCycleStartedAt: String? = null
    private var lastCycleFinishedAt: String? = null
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "zeroconf-discovery-monitor").apply { isDaemon = true }
    }
    private val listenerThreadId = AtomicInteger(0)
    private val listenerExecutor = Executors.newFixedThreadPool(5) { runnable ->
        Thread(runnable, "zeroconf-passive-listener-${listenerThreadId.incrementAndGet()}").apply { isDaemon = true }
    }

    fun start() {
        if (started.compareAndSet(false, true)) {
            executor.scheduleWithFixedDelay({ refreshNow() }, 0, 15, TimeUnit.SECONDS)
        }
        startPassiveListeners()
    }

    fun refreshNow() {
        if (!scanning.compareAndSet(false, true)) return
        lastCycleStartedAt = Instant.now().toString()
        try {
            runDiscoveryCycle()
        } finally {
            lastCycleFinishedAt = Instant.now().toString()
            scanning.set(false)
        }
    }

    @Synchronized
    fun snapshot(): ZeroconfDashboardSnapshot {
        val visibleDevices = devices.values
            .sortedWith(compareByDescending<MutableDashboardDevice> { it.lastSeen }.thenBy { it.displayName })
            .map { it.toDevice() }
        return ZeroconfDashboardSnapshot(
            generatedAt = Instant.now().toString(),
            running = started.get(),
            scanning = scanning.get(),
            lastCycleStartedAt = lastCycleStartedAt,
            lastCycleFinishedAt = lastCycleFinishedAt,
            deviceCount = visibleDevices.size,
            serviceCount = visibleDevices.sumOf { it.services.size },
            devices = visibleDevices,
            hostnames = hostnameRecords.values
                .sortedBy { it.hostname }
                .map { it.toResolution() },
            serviceCatalog = serviceTypes.values
                .sortedBy { it.type }
                .map { it.toInfo() },
            events = events.toList().takeLast(80).reversed(),
            protocolStats = stats.values.toList(),
            warnings = warnings.toList().takeLast(30).reversed(),
        )
    }

    @Synchronized
    fun deviceById(id: String): ZeroconfDashboardDevice? = devices[id]?.toDevice()

    @Synchronized
    fun documentById(id: String, index: Int): Pair<String, String>? =
        devices[id]?.documents?.getOrNull(index)?.let { it.contentType to it.content }

    private fun runDiscoveryCycle() {
        safeProtocol("mDNS") {
            val client = MdnsClient(timeout = timeout)
            val serviceCatalog = client.query("_services._dns-sd._udp.local", "PTR", maxPackets = 60)
            serviceCatalog.warnings.forEach { rememberDiscoveryWarning(it) }
            val serviceTypes = (mdnsSeedServiceTypes() + serviceCatalog.records
                .filter { it.type == "PTR" && it.name.equals("_services._dns-sd._udp.local.", ignoreCase = true) }
                .map { cleanDnsName(it.data) })
                .distinct()
                .take(16)
            val records = (listOf(serviceCatalog) + serviceTypes.map { type ->
                client.query(type, "PTR", maxPackets = 40)
            }).flatMap { result ->
                result.warnings.forEach { rememberDiscoveryWarning(it) }
                result.records
            }
            val usefulRecords = ingestMdnsRecords(records)
            updateStats("mDNS", usefulRecords)
        }
        safeProtocol("SSDP") {
            val result = SsdpClient(timeout = timeout).search(searchTarget = "ssdp:all", maxPackets = 60)
            result.warnings.forEach { rememberDiscoveryWarning(it) }
            val messages = result.messages.filter { it.isNotify || it.isResponse }
            messages.forEach(::ingestSsdp)
            updateStats("SSDP", messages.size)
        }
        safeProtocol("WS-Discovery") {
            val client = WsDiscoveryClient(timeout = timeout)
            val result = client.probe(ipFamily = IpFamily.BOTH, maxPackets = 60)
            result.warnings.forEach { rememberDiscoveryWarning(it) }
            val messages = result.messages.filterNot { it.messageType == "Probe" || it.messageType == "Resolve" }
            val enrichedMessages = enrichWsdMessages(client, messages)
            enrichedMessages.forEach { ingestWsd(it.message, it.metadata) }
            updateStats("WS-Discovery", enrichedMessages.size)
        }
        safeProtocol("SMB Sweep") {
            val hits = scanLocalSmbHosts()
            hits.forEach(::ingestSmbSweep)
            updateStats("SMB Sweep", hits.size)
        }
    }

    private fun safeProtocol(protocol: String, block: () -> Unit) {
        runCatching(block).onFailure { error ->
            rememberWarning("$protocol discovery failed: ${error.message ?: error::class.java.simpleName}")
            updateStats(protocol, 0, status = "warning")
        }
    }

    private fun startPassiveListeners() {
        if (!passiveStarted.compareAndSet(false, true)) return
        listenerExecutor.execute {
            passiveListenLoop("mDNS") {
                val result = MdnsClient(timeout = passiveListenTimeout).listen(maxPackets = 250)
                rememberPassiveWarnings(result.warnings)
                val usefulRecords = ingestMdnsRecords(result.records)
                updateStats("mDNS", usefulRecords)
            }
        }
        listenerExecutor.execute {
            passiveListenLoop("SSDP") {
                val result = SsdpClient(timeout = passiveListenTimeout).listen(maxPackets = 250)
                rememberPassiveWarnings(result.warnings)
                val messages = result.messages.filter { it.isNotify || it.isResponse }
                messages.forEach(::ingestSsdp)
                updateStats("SSDP", messages.size)
            }
        }
        listenerExecutor.execute {
            passiveListenLoop("WS-Discovery") {
                val client = WsDiscoveryClient(timeout = passiveListenTimeout)
                val result = client.listen(ipFamily = IpFamily.BOTH, maxPackets = 250)
                rememberPassiveWarnings(result.warnings)
                val messages = result.messages.filterNot { it.messageType == "Probe" || it.messageType == "Resolve" }
                val enrichedMessages = enrichWsdMessages(client, messages)
                enrichedMessages.forEach { ingestWsd(it.message, it.metadata) }
                updateStats("WS-Discovery", enrichedMessages.size)
            }
        }
        listenerExecutor.execute {
            passiveListenLoop("LLMNR") {
                val result = LlmnrClient(timeout = passiveListenTimeout).listen(ipFamily = IpFamily.BOTH, maxPackets = 250)
                rememberPassiveWarnings(result.warnings)
                result.records.forEach(::ingestLlmnr)
                updateStats("LLMNR", result.records.size)
            }
        }
        listenerExecutor.execute {
            passiveListenLoop("NetBIOS") {
                val responses = NetbiosNameServiceClient(timeout = passiveListenTimeout).listen(maxPackets = 250)
                responses.forEach(::ingestNetbios)
                updateStats("NetBIOS", responses.size)
            }
        }
    }

    private fun passiveListenLoop(protocol: String, block: () -> Unit) {
        while (started.get() && !Thread.currentThread().isInterrupted) {
            runCatching(block).onFailure { error ->
                rememberWarning("$protocol passive listener failed: ${error.message ?: error::class.java.simpleName}")
                updateStats(protocol, 0, status = "warning")
                Thread.sleep(5_000)
            }
        }
    }

    private fun rememberPassiveWarnings(protocolWarnings: List<String>) {
        protocolWarnings
            .filter { warning -> warning.startsWith("Failed", ignoreCase = true) || warning.startsWith("Could not", ignoreCase = true) }
            .forEach(::rememberDiscoveryWarning)
    }

    @Synchronized
    private fun ingestMdnsRecords(records: List<MdnsRecord>): Int {
        val useful = records.filterNot { it.type == "NSEC" }
        useful.filter { it.type == "PTR" && it.name.equals("_services._dns-sd._udp.local.", ignoreCase = true) }
            .forEach { record ->
                val type = cleanDnsName(record.data)
                rememberServiceType("mDNS", "mdns", type, ::dnsSdServiceInfo).observedKeys += type
                rememberEvent("mDNS", "Service type $type")
            }

        val addressRecords = useful.filter { it.type == "A" || it.type == "AAAA" }
        val addressesByHost = addressRecords.groupBy { cleanDnsName(it.name) }
        val srvByInstance = useful.filter { it.type == "SRV" }.associateBy { cleanDnsName(it.name) }
        val txtByInstance = useful.filter { it.type == "TXT" }.groupBy { cleanDnsName(it.name) }
        val instances = linkedSetOf<String>()

        useful.filter { it.type == "PTR" && !it.name.equals("_services._dns-sd._udp.local.", ignoreCase = true) }
            .forEach { record ->
                val serviceType = cleanDnsName(record.name)
                val instance = cleanDnsName(record.data)
                instances += instance
                rememberServiceType("mDNS", "mdns", serviceType, ::dnsSdServiceInfo).observedKeys += instance
            }
        instances += srvByInstance.keys
        instances += txtByInstance.keys

        addressRecords.forEach { record ->
            val host = cleanDnsName(record.name)
            rememberHostnameResolution(
                hostname = host,
                protocol = "mDNS",
                addresses = listOf(record.data),
                record = ZeroconfDnsRecordView(host, record.type, record.data, record.ttl),
            )
        }

        for (instance in instances) {
            val srv = srvByInstance[instance]
            val parsedSrv = srv?.data?.let(::parseSrvData)
            val host = parsedSrv?.target ?: instance
            val serviceType = serviceTypeFromInstance(instance)
            val instanceName = serviceInstanceName(instance, serviceType)
            val category = inferDnsSdCategory(serviceType, instance)
            val known = dnsSdServiceInfo(serviceType)
            val device = device("mdns-host:${host.lowercase()}", instanceName.ifBlank { host }, category)
            device.protocols += "mDNS"
            device.hostnames += host
            addressesByHost[host]?.forEach { addressRecord ->
                device.addresses += addressRecord.data
                device.dnsRecords += ZeroconfDnsRecordView(host, addressRecord.type, addressRecord.data, addressRecord.ttl)
            }
            srv?.let { device.dnsRecords += ZeroconfDnsRecordView(instance, "SRV", it.data, it.ttl) }
            txtByInstance[instance].orEmpty().forEach { txt ->
                parseTxt(txt.data).forEach { (key, value) ->
                    device.txtRecords += ZeroconfTxtRecordView(instance, key, value)
                }
            }
            device.services += ZeroconfDashboardService(
                protocol = "mDNS",
                type = serviceType,
                name = instanceName,
                target = host,
                location = "",
                port = parsedSrv?.port,
                description = known.second,
            )
            device.details["DNS-SD Service"] = serviceType
            parsedSrv?.port?.let { device.details["Port"] = it.toString() }
            device.touch()
            rememberEvent("mDNS", "$instanceName $serviceType -> $host${parsedSrv?.port?.let { ":$it" } ?: ""}")
        }

        for ((host, hostRecords) in addressesByHost) {
            if (devices.containsKey("mdns-host:${host.lowercase()}")) continue
            val device = device("mdns-host:${host.lowercase()}", host, "Host")
            device.protocols += "mDNS"
            device.hostnames += host
            hostRecords.forEach { record ->
                device.addresses += record.data
                device.dnsRecords += ZeroconfDnsRecordView(host, record.type, record.data, record.ttl)
            }
            device.touch()
        }

        return useful.count { it.type != "PTR" || !it.name.equals("_services._dns-sd._udp.local.", ignoreCase = true) }
    }

    @Synchronized
    private fun ingestLlmnr(record: LlmnrRecord) {
        val key = "llmnr:${record.name.lowercase()}:${record.data.lowercase()}"
        val device = device(key, record.name, "Host")
        device.protocols += "LLMNR"
        val hostname = cleanDnsName(record.name)
        device.hostnames += hostname
        device.services += ZeroconfDashboardService("LLMNR", record.type, record.name, record.data, "")
        if (record.type == "A" || record.type == "AAAA") device.addresses += record.data.trim('"')
        val dnsRecord = ZeroconfDnsRecordView(hostname, record.type, record.data, record.ttl)
        device.dnsRecords += dnsRecord
        rememberHostnameResolution(
            hostname = hostname,
            protocol = "LLMNR",
            addresses = if (record.type == "A" || record.type == "AAAA") listOf(record.data.trim('"')) else emptyList(),
            record = dnsRecord,
        )
        device.details["LLMNR ${record.type}"] = record.data
        device.touch()
        rememberEvent("LLMNR", "${record.type} ${record.name} -> ${record.data}")
    }

    @Synchronized
    private fun ingestSsdp(message: SsdpMessage) {
        val key = message.uniqueServiceName?.let { "ssdp:${extractUuid(it) ?: it.lowercase()}" }
            ?: message.location?.let { "ssdp:${locationHost(it) ?: it.lowercase()}" }
            ?: "ssdp:${message.startLine}:${message.searchTarget ?: message.notificationType}"
        val label = message.notificationType ?: message.searchTarget ?: message.server ?: "SSDP device"
        val device = device(key, label, inferSsdpCategory(message))
        device.protocols += "SSDP"
        message.location?.let {
            device.locations += it
            locationHost(it)?.let { host -> device.addresses += host }
        }
        device.services += ZeroconfDashboardService(
            protocol = "SSDP",
            type = message.notificationType ?: message.searchTarget ?: if (message.isNotify) "NOTIFY" else "Response",
            name = message.notificationType ?: message.searchTarget ?: "ssdp",
            target = message.uniqueServiceName ?: "",
            location = message.location ?: "",
            description = upnpServiceInfo(message.notificationType ?: message.searchTarget ?: "").second,
        )
        val serviceType = message.notificationType ?: message.searchTarget
        if (serviceType != null) {
            rememberServiceType("SSDP", "ssdp", serviceType, ::upnpServiceInfo).observedKeys +=
                listOfNotNull(message.uniqueServiceName, message.location, message.startLine).joinToString("|")
        }
        message.server?.let { device.details["SSDP Server"] = it }
        device.touch()
        rememberEvent("SSDP", "${message.notificationType ?: message.searchTarget ?: message.startLine} ${message.location ?: ""}".trim())
    }

    private fun enrichWsdMessages(client: WsDiscoveryClient, messages: List<WsDiscoveryMessage>): List<EnrichedWsdMessage> {
        val resolved = messages
            .filter { it.xAddrs.isNullOrBlank() && !it.endpointReference.isNullOrBlank() }
            .flatMap { message ->
                runCatching {
                    val result = client.resolve(message.endpointReference!!, ipFamily = IpFamily.BOTH, maxPackets = 20)
                    result.warnings.forEach { rememberDiscoveryWarning(it) }
                    result.messages.filterNot { it.messageType == "Probe" || it.messageType == "Resolve" }
                }.getOrElse { error ->
                    rememberDiscoveryWarning("WS-Discovery resolve failed for ${message.endpointReference}: ${error.message ?: error::class.java.simpleName}")
                    emptyList()
                }
            }
        return (messages + resolved)
            .distinctBy { listOfNotNull(it.endpointReference, it.xAddrs, it.messageId, it.action).joinToString("|") }
            .map { message ->
                EnrichedWsdMessage(
                    message = message,
                    metadata = message.xAddrs?.let(::fetchWsdMetadata),
                )
            }
    }

    private fun fetchWsdMetadata(xAddrs: String): WsDiscoveryMetadata? {
        val url = xAddrs.split(Regex("\\s+")).firstOrNull { it.startsWith("http://", ignoreCase = true) }
            ?: return null
        return runCatching {
            val payload = WsDiscoverySoapCodec.buildGet()
            val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = timeout.toMillis().toInt()
                readTimeout = timeout.toMillis().toInt()
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/soap+xml")
                setRequestProperty("Content-Length", payload.size.toString())
            }
            try {
                connection.outputStream.use { output -> output.write(payload) }
                val bytes = if (connection.responseCode in 200..299) {
                    connection.inputStream.use { it.readNBytes(256 * 1024) }
                } else {
                    connection.errorStream?.use { it.readNBytes(64 * 1024) } ?: ByteArray(0)
                }
                if (bytes.isEmpty()) null else WsDiscoverySoapCodec.parseMetadata(bytes)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    @Synchronized
    private fun ingestWsd(message: WsDiscoveryMessage, metadata: WsDiscoveryMetadata? = null) {
        val xAddrLocations = splitXAddrs(message.xAddrs)
        val key = message.endpointReference?.let { "wsd:${extractUuid(it) ?: it.lowercase()}" }
            ?: xAddrLocations.firstOrNull()?.let { "wsd:${locationHost(it) ?: it.lowercase()}" }
            ?: "wsd:${message.messageId ?: message.action ?: message.rawXml.hashCode()}"
        val label = metadata?.computerName
            ?: metadata?.friendlyName
            ?: message.types
            ?: message.endpointReference
            ?: "WS-Discovery device"
        val device = device(key, label, inferWsdCategory(message, metadata))
        device.protocols += "WS-Discovery"
        if (device.documents.none { it.content == message.rawXml }) {
            device.documents += MutableDiscoveryDocument(
                protocol = "WS-Discovery",
                label = "${message.messageType} discovery XML",
                contentType = "application/xml",
                content = message.rawXml,
            )
        }
        xAddrLocations.forEach { location ->
            device.locations += location
            locationHost(location)?.let { host ->
                device.addresses += host
                if (!looksLikeIpAddress(host)) device.hostnames += host
            }
        }
        val xAddrHosts = xAddrLocations.mapNotNull(::locationHost)
        val xAddrAddresses = xAddrHosts.filter(::looksLikeIpAddress)
        val wsdHostnames = (listOfNotNull(metadata?.computerName) + xAddrHosts.filterNot(::looksLikeIpAddress))
            .map(::cleanDnsName)
            .filter { it.isNotBlank() }
            .distinct()
        wsdHostnames.forEach { hostname ->
            device.hostnames += hostname
            rememberHostnameResolution(
                hostname = hostname,
                protocol = "WS-Discovery",
                addresses = xAddrAddresses,
            )
        }
        metadata?.friendlyName?.let { device.details["WSD Friendly Name"] = it }
        metadata?.manufacturer?.let { device.details["WSD Manufacturer"] = it }
        metadata?.modelName?.let { device.details["WSD Model"] = it }
        metadata?.modelNumber?.let { device.details["WSD Model Number"] = it }
        metadata?.serialNumber?.let { device.details["WSD Serial"] = it }
        metadata?.workgroup?.let { device.details["WSD Workgroup"] = it }
        val serviceLocations = xAddrLocations.ifEmpty { listOf("") }
        serviceLocations.forEach { location ->
            device.services += ZeroconfDashboardService(
                protocol = "WS-Discovery",
                type = message.messageType,
                name = message.types ?: "",
                target = locationHost(location) ?: message.endpointReference ?: "",
                location = location,
                port = locationPort(location),
                description = wsdTypeInfo(message.types ?: "").second,
            )
        }
        message.types?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.forEach { type ->
            rememberServiceType("WS-Discovery", "wsd", type, ::wsdTypeInfo).observedKeys +=
                message.endpointReference ?: message.xAddrs ?: message.messageId ?: type
        }
        message.scopes?.let { device.details["WSD Scopes"] = it }
        message.metadataVersion?.let { device.details["WSD Metadata"] = it }
        device.touch()
        rememberEvent("WS-Discovery", "${message.messageType} ${metadata?.computerName ?: message.types ?: message.endpointReference ?: ""}".trim())
    }

    @Synchronized
    private fun ingestNetbios(response: NetbiosResponse) {
        val key = "netbios:${response.sourceAddress}"
        val name = response.names.firstOrNull()?.name ?: response.addresses.firstOrNull()?.name ?: response.sourceAddress
        val device = device(key, name, "Windows / SMB host")
        device.protocols += "NetBIOS"
        device.hostnames += name
        device.addresses += response.sourceAddress
        rememberHostnameResolution(
            hostname = name,
            protocol = "NetBIOS",
            addresses = listOf(response.sourceAddress),
        )
        response.names.forEach { nbName ->
            device.services += ZeroconfDashboardService("NetBIOS", "NBSTAT", nbName.name, "0x${nbName.suffix.toString(16)}", "")
        }
        response.addresses.forEach { address ->
            device.services += ZeroconfDashboardService("NetBIOS", "NB", address.name, address.address, "")
            device.addresses += address.address
            rememberHostnameResolution(
                hostname = address.name,
                protocol = "NetBIOS",
                addresses = listOf(address.address),
            )
        }
        device.touch()
        rememberEvent("NetBIOS", "$name from ${response.sourceAddress}")
    }

    @Synchronized
    private fun ingestSmbSweep(hit: SmbSweepHit) {
        val key = "smb:${hit.address}"
        val displayName = hit.hostname?.substringBefore('.') ?: hit.hostname ?: hit.address
        val device = device(key, displayName, "Windows / SMB host")
        device.protocols += "SMB"
        device.addresses += hit.address
        hit.hostname?.let {
            device.hostnames += it
            rememberHostnameResolution(
                hostname = it,
                protocol = "SMB",
                addresses = listOf(hit.address),
            )
            if (hit.wsdTcpOpen) {
                rememberHostnameResolution(
                    hostname = it,
                    protocol = "WS-Discovery",
                    addresses = listOf(hit.address),
                )
            }
        }
        device.services += ZeroconfDashboardService(
            protocol = "SMB",
            type = "microsoft-ds",
            name = "SMB file sharing",
            target = hit.address,
            location = "",
            port = 445,
            description = "SMB/CIFS file sharing endpoint reachable on TCP 445.",
        )
        if (hit.wsdTcpOpen) {
            device.protocols += "WS-Discovery"
            device.services += ZeroconfDashboardService(
                protocol = "WS-Discovery",
                type = "WSD metadata",
                name = "TCP 3702",
                target = hit.address,
                location = "http://${hit.address}:3702/",
                port = 3702,
                description = "WS-Discovery metadata endpoint is reachable over TCP.",
            )
        }
        device.details["SMB Port"] = "445 open"
        if (hit.wsdTcpOpen) device.details["WSD TCP Port"] = "3702 open"
        device.touch()
        rememberEvent("SMB Sweep", "$displayName ${hit.address}")
    }

    private fun rememberHostnameResolution(
        hostname: String,
        protocol: String,
        addresses: List<String> = emptyList(),
        record: ZeroconfDnsRecordView? = null,
    ) {
        val cleanHostname = cleanDnsName(hostname).takeIf { it.isNotBlank() && !looksLikeIpAddress(it) } ?: return
        val resolution = hostnameRecords.getOrPut("hostname:${cleanHostname.lowercase()}") {
            MutableHostnameResolution(cleanHostname)
        }
        resolution.protocols += protocol
        addresses
            .map { it.trim().trim('"') }
            .filter { it.isNotBlank() && looksLikeIpAddress(it) }
            .forEach { resolution.addresses += it }
        record?.let { resolution.records += it }
    }

    private fun rememberServiceType(
        protocol: String,
        namespace: String,
        type: String,
        lookup: (String) -> Pair<String, String>,
    ): MutableServiceTypeInfo =
        serviceTypes.getOrPut("$namespace:$type") {
            val known = lookup(type)
            MutableServiceTypeInfo(protocol, type, known.first, known.second)
        }

    @Synchronized
    private fun updateStats(protocol: String, observations: Int, status: String = "ok") {
        val previous = stats[protocol]
        val totalObservations = (previous?.observations ?: 0) + observations
        val now = Instant.now().toString()
        stats[protocol] = ZeroconfProtocolStat(
            protocol = protocol,
            status = status,
            observations = totalObservations,
            lastObservations = observations,
            lastSeen = if (observations > 0) now else previous?.lastSeen ?: now,
        )
    }

    @Synchronized
    private fun rememberEvent(protocol: String, summary: String) {
        events += ZeroconfDashboardEvent(Instant.now().toString(), protocol, summary)
        while (events.size > 120) events.removeFirst()
    }

    @Synchronized
    private fun rememberWarning(warning: String) {
        warnings += warning
        while (warnings.size > 60) warnings.removeFirst()
    }

    private fun rememberDiscoveryWarning(warning: String) {
        if (warning.startsWith("Joined multicast group")) return
        rememberWarning(warning)
    }

    private fun device(key: String, displayName: String, category: String): MutableDashboardDevice =
        devices.getOrPut(key) {
            MutableDashboardDevice(
                id = key,
                displayName = cleanLabel(displayName),
                category = category,
            )
        }.also { existing ->
            if (categoryRank(category) > categoryRank(existing.category)) {
                existing.category = category
            }
        }
}
