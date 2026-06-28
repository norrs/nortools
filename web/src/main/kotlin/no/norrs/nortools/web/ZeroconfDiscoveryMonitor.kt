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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.NetworkInterface
import java.net.Socket
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class ZeroconfDashboardSnapshot(
    val generatedAt: String,
    val running: Boolean,
    val scanning: Boolean,
    val lastCycleStartedAt: String?,
    val lastCycleFinishedAt: String?,
    val deviceCount: Int,
    val serviceCount: Int,
    val devices: List<ZeroconfDashboardDevice>,
    val hostnames: List<ZeroconfHostnameResolution>,
    val serviceCatalog: List<ZeroconfServiceTypeInfo>,
    val events: List<ZeroconfDashboardEvent>,
    val protocolStats: List<ZeroconfProtocolStat>,
    val warnings: List<String>,
)

data class ZeroconfDashboardDevice(
    val id: String,
    val displayName: String,
    val category: String,
    val addresses: List<String>,
    val hostnames: List<String>,
    val protocols: List<String>,
    val services: List<ZeroconfDashboardService>,
    val dnsRecords: List<ZeroconfDnsRecordView>,
    val txtRecords: List<ZeroconfTxtRecordView>,
    val locations: List<String>,
    val documents: List<ZeroconfDiscoveryDocument>,
    val firstSeen: String,
    val lastSeen: String,
    val evidenceCount: Int,
    val confidence: String,
    val details: Map<String, String>,
)

data class ZeroconfDashboardService(
    val protocol: String,
    val type: String,
    val name: String,
    val target: String,
    val location: String,
    val port: Int? = null,
    val description: String = "",
)

data class ZeroconfDiscoveryDocument(
    val index: Int,
    val protocol: String,
    val label: String,
    val contentType: String,
    val sizeBytes: Int,
)

data class ZeroconfDnsRecordView(
    val hostname: String,
    val type: String,
    val value: String,
    val ttl: Long,
)

data class ZeroconfTxtRecordView(
    val service: String,
    val key: String,
    val value: String,
)

data class ZeroconfHostnameResolution(
    val hostname: String,
    val addresses: List<String>,
    val protocols: List<String>,
    val records: List<ZeroconfDnsRecordView>,
)

data class ZeroconfServiceTypeInfo(
    val protocol: String,
    val type: String,
    val title: String,
    val description: String,
    val observed: Int,
)

data class ZeroconfDashboardEvent(
    val seenAt: String,
    val protocol: String,
    val summary: String,
)

data class ZeroconfProtocolStat(
    val protocol: String,
    val status: String,
    val observations: Int,
    val lastObservations: Int,
    val lastSeen: String?,
)

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
                val known = dnsSdServiceInfo(type)
                val info = serviceTypes.getOrPut("mdns:$type") {
                    MutableServiceTypeInfo("mDNS", type, known.first, known.second)
                }
                info.observedKeys += type
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
                val known = dnsSdServiceInfo(serviceType)
                val info = serviceTypes.getOrPut("mdns:$serviceType") {
                    MutableServiceTypeInfo("mDNS", serviceType, known.first, known.second)
                }
                info.observedKeys += instance
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
            val known = upnpServiceInfo(serviceType)
            val info = serviceTypes.getOrPut("ssdp:$serviceType") {
                MutableServiceTypeInfo("SSDP", serviceType, known.first, known.second)
            }
            info.observedKeys += listOfNotNull(message.uniqueServiceName, message.location, message.startLine).joinToString("|")
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
            val known = wsdTypeInfo(type)
            val info = serviceTypes.getOrPut("wsd:$type") {
                MutableServiceTypeInfo("WS-Discovery", type, known.first, known.second)
            }
            info.observedKeys += message.endpointReference ?: message.xAddrs ?: message.messageId ?: type
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

    private fun scanLocalSmbHosts(): List<SmbSweepHit> {
        val candidates = localPrivateIpv4Candidates()
        if (candidates.isEmpty()) return emptyList()
        val executor = Executors.newFixedThreadPool(32) { runnable ->
            Thread(runnable, "smb-sweep-worker").apply { isDaemon = true }
        }
        return try {
            executor.invokeAll(
                candidates.map { address ->
                    Callable {
                        if (!isTcpOpen(address, 445, 250)) return@Callable null
                        SmbSweepHit(
                            address = address,
                            hostname = reverseHostname(address),
                            wsdTcpOpen = isTcpOpen(address, 3702, 250),
                        )
                    }
                },
                8,
                TimeUnit.SECONDS,
            ).mapNotNull { future ->
                runCatching { future.get() }.getOrNull()
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun localPrivateIpv4Candidates(): List<String> {
        val ranges = linkedSetOf<String>()
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface -> iface.interfaceAddresses.asSequence() }
            .mapNotNull { address -> address.address as? Inet4Address }
            .map { it.hostAddress }
            .filter(::isPrivateIpv4)
            .forEach { address ->
                val parts = address.split('.').mapNotNull { it.toIntOrNull() }
                if (parts.size == 4) {
                    for (host in 1..254) {
                        val candidate = "${parts[0]}.${parts[1]}.${parts[2]}.$host"
                        if (candidate != address) ranges += candidate
                    }
                }
            }
        return ranges.toList()
    }

    private fun isPrivateIpv4(address: String): Boolean {
        val parts = address.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return parts[0] == 10 ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168)
    }

    private fun isTcpOpen(address: String, port: Int, timeoutMs: Int): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, port), timeoutMs)
            }
            true
        }.getOrDefault(false)

    private fun reverseHostname(address: String): String? =
        runCatching { InetAddress.getByName(address).canonicalHostName }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != address }

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

    private fun categoryRank(category: String): Int =
        when (category) {
            "Printer", "Printer / Scanner" -> 90
            "Media", "Smart Home", "Camera", "Router" -> 80
            "Windows / SMB host", "Computer", "Host" -> 70
            "Web service" -> 50
            else -> 10
        }

    private fun cleanLabel(value: String): String =
        value.trim()
            .trim('"')
            .removeSuffix(".")
            .replace("\\032", " ")
            .replace("\\(", "(")
            .replace("\\)", ")")
            .ifBlank { "Unknown device" }

    private fun cleanDnsName(value: String): String = cleanLabel(value)

    private fun mdnsSrvTarget(data: String): String? =
        data.trim().split(Regex("\\s+")).lastOrNull()?.takeIf { it.contains('.') }

    private data class ParsedSrv(val priority: Int, val weight: Int, val port: Int, val target: String)

    private fun parseSrvData(data: String): ParsedSrv? {
        val parts = data.trim().split(Regex("\\s+"))
        if (parts.size < 4) return null
        return ParsedSrv(
            priority = parts[0].toIntOrNull() ?: 0,
            weight = parts[1].toIntOrNull() ?: 0,
            port = parts[2].toIntOrNull() ?: 0,
            target = cleanDnsName(parts.drop(3).joinToString(" ")),
        )
    }

    private fun parseTxt(data: String): List<Pair<String, String>> {
        val quoted = Regex(""""([^"]*)"""").findAll(data).map { it.groupValues[1] }.toList()
        val tokens = quoted.ifEmpty { data.split(Regex("\\s+")).filter { it.isNotBlank() } }
        return tokens.map { token ->
            val idx = token.indexOf('=')
            if (idx < 0) token to "" else token.substring(0, idx) to token.substring(idx + 1)
        }
    }

    private fun serviceTypeFromInstance(instance: String): String {
        val idx = instance.indexOf("._")
        return if (idx >= 0) instance.substring(idx + 1) else instance
    }

    private fun serviceInstanceName(instance: String, serviceType: String): String =
        instance.removeSuffix(".$serviceType").removeSuffix(serviceType).trim('.')

    private fun locationHost(location: String): String? =
        runCatching { URI(location).host }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun locationPort(location: String): Int? =
        runCatching { URI(location).port }.getOrNull()?.takeIf { it > 0 }

    private fun splitXAddrs(xAddrs: String?): List<String> =
        xAddrs
            ?.split(Regex("\\s+"))
            ?.map { it.trim() }
            ?.filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            ?.filter { runCatching { URI(it) }.isSuccess }
            ?.distinct()
            ?: emptyList()

    private fun looksLikeIpAddress(value: String): Boolean {
        val clean = value.trim().trim('[', ']')
        return Regex("""\d{1,3}(?:\.\d{1,3}){3}""").matches(clean) || ':' in clean
    }

    private fun extractUuid(value: String): String? =
        Regex("""uuid:[a-zA-Z0-9._-]+""").find(value)?.value?.lowercase()

    private fun inferDnsSdCategory(serviceType: String, instance: String): String {
        val text = "$serviceType $instance".lowercase()
        return when {
            "_smb" in text -> "Windows / SMB host"
            "_device-info" in text -> "Device"
            "_ipp" in text || "_printer" in text -> "Printer"
            "_scanner" in text || "_uscan" in text -> "Printer / Scanner"
            "_airplay" in text || "_raop" in text || "_spotify" in text || "_googlecast" in text -> "Media"
            "_mediaremotetv" in text || "_companion-link" in text -> "Media"
            "_hap" in text || "_homekit" in text -> "Smart Home"
            "_hue" in text -> "Smart Home"
            "_ssh" in text || "_sftp-ssh" in text || "_rfb" in text -> "Remote access"
            "_afpovertcp" in text -> "File sharing"
            "_workstation" in text -> "Host"
            "_http" in text -> "Web service"
            else -> "DNS-SD service"
        }
    }

    private fun dnsSdServiceInfo(type: String): Pair<String, String> {
        val normalized = type.lowercase().removeSuffix(".")
        return when {
            normalized == "_smb._tcp.local" -> "SMB File Sharing" to "Samba or SMB/CIFS file sharing advertised through DNS-SD."
            normalized == "_device-info._tcp.local" -> "Device Info" to "DNS-SD metadata record with model and device-class hints."
            normalized == "_ipp._tcp.local" -> "IPP Printer" to "Internet Printing Protocol. AirPrint and Mopria printers commonly expose queues here."
            normalized == "_ipps._tcp.local" -> "Secure IPP Printer" to "Encrypted IPP printing over TLS."
            normalized == "_printer._tcp.local" -> "LPD Printer" to "Legacy printer service discovery."
            normalized == "_scanner._tcp.local" || normalized == "_uscan._tcp.local" -> "Scanner" to "Scanner discovery, often paired with multifunction printers."
            normalized == "_http._tcp.local" -> "HTTP Service" to "Embedded web interface or device administration endpoint."
            normalized == "_http-alt._tcp.local" -> "Alternate HTTP Service" to "HTTP service on a non-standard port."
            normalized == "_ssh._tcp.local" -> "SSH" to "Secure Shell remote login or administration endpoint."
            normalized == "_sftp-ssh._tcp.local" -> "SFTP" to "SSH File Transfer Protocol endpoint."
            normalized == "_rfb._tcp.local" -> "VNC Remote Desktop" to "Remote Framebuffer service, commonly used by VNC remote desktop servers."
            normalized == "_afpovertcp._tcp.local" -> "Apple File Sharing" to "Apple Filing Protocol file sharing over TCP."
            normalized == "_workstation._tcp.local" -> "Workstation" to "Generic host identity advertised by Avahi and similar mDNS responders."
            normalized == "_airplay._tcp.local" -> "AirPlay" to "Apple media playback or display target."
            normalized == "_raop._tcp.local" -> "AirPlay Audio" to "Remote Audio Output Protocol used by AirPlay speakers and receivers."
            normalized == "_companion-link._tcp.local" -> "Apple Companion Link" to "Apple continuity, remote control, and device companion service."
            normalized == "_mediaremotetv._tcp.local" -> "Apple TV Remote" to "Apple TV media remote control endpoint."
            normalized == "_sleep-proxy._udp.local" -> "Sleep Proxy" to "Bonjour Sleep Proxy wake-on-demand service."
            normalized == "_spotify-connect._tcp.local" -> "Spotify Connect" to "Spotify playback target."
            normalized == "_googlecast._tcp.local" -> "Google Cast" to "Chromecast or Google Cast media receiver."
            normalized == "_hap._tcp.local" -> "HomeKit" to "Apple HomeKit accessory service."
            normalized == "_hue._tcp.local" -> "Hue Bridge" to "Philips Hue bridge discovery."
            else -> type to "DNS-SD service type advertised on the local link."
        }
    }

    private fun mdnsSeedServiceTypes(): List<String> =
        listOf(
            "_smb._tcp.local",
            "_device-info._tcp.local",
            "_http._tcp.local",
            "_http-alt._tcp.local",
            "_ipp._tcp.local",
            "_ipps._tcp.local",
        )

    private fun upnpServiceInfo(type: String): Pair<String, String> {
        val normalized = type.lowercase()
        return when {
            "mediarenderer" in normalized -> "UPnP Media Renderer" to "DLNA/UPnP playback target such as a receiver, TV, or speaker."
            "mediaserver" in normalized -> "UPnP Media Server" to "DLNA/UPnP content library source."
            "internetgatewaydevice" in normalized -> "Internet Gateway Device" to "Router or gateway service exposed through UPnP IGD."
            "rootdevice" in normalized -> "UPnP Root Device" to "Top-level UPnP device advertisement."
            else -> type.ifBlank { "UPnP device" } to "UPnP/SSDP advertised device or service."
        }
    }

    private fun wsdTypeInfo(type: String): Pair<String, String> {
        val normalized = type.lowercase()
        return when {
            "print" in normalized -> "WSD Printer" to "Windows Web Services for Devices printer endpoint."
            "scanner" in normalized || "scan" in normalized -> "WSD Scanner" to "Windows Web Services for Devices scanner endpoint."
            "computer" in normalized -> "Windows Computer" to "Computer advertised through WS-Discovery."
            "networkvideotransmitter" in normalized || "onvif" in normalized -> "ONVIF Camera" to "Network camera or video device using WS-Discovery."
            else -> type.ifBlank { "WSD device" } to "SOAP-over-UDP WS-Discovery device type."
        }
    }

    private fun inferSsdpCategory(message: SsdpMessage): String {
        val text = listOf(message.notificationType, message.searchTarget, message.server, message.uniqueServiceName)
            .filterNotNull()
            .joinToString(" ")
            .lowercase()
        return when {
            "mediarenderer" in text || "mediaserver" in text || "dlna" in text -> "Media"
            "printer" in text || "scanner" in text -> "Printer / Scanner"
            "hue" in text || "bridge" in text -> "Smart Home"
            "internetgatewaydevice" in text || "wanipconnection" in text -> "Router"
            else -> "UPnP device"
        }
    }

    private fun inferWsdCategory(message: WsDiscoveryMessage, metadata: WsDiscoveryMetadata? = null): String {
        val text = listOf(message.types, message.scopes, message.xAddrs, metadata?.computerName, metadata?.friendlyName)
            .filterNotNull()
            .joinToString(" ")
            .lowercase()
        return when {
            "print" in text || "scanner" in text -> "Printer / Scanner"
            "camera" in text || "onvif" in text -> "Camera"
            "computer" in text -> "Computer"
            else -> "WSD device"
        }
    }
}

private data class EnrichedWsdMessage(
    val message: WsDiscoveryMessage,
    val metadata: WsDiscoveryMetadata?,
)

private data class MutableDiscoveryDocument(
    val protocol: String,
    val label: String,
    val contentType: String,
    val content: String,
) {
    fun toDocument(index: Int): ZeroconfDiscoveryDocument =
        ZeroconfDiscoveryDocument(
            index = index,
            protocol = protocol,
            label = label,
            contentType = contentType,
            sizeBytes = content.toByteArray(Charsets.UTF_8).size,
        )
}

private data class SmbSweepHit(
    val address: String,
    val hostname: String?,
    val wsdTcpOpen: Boolean,
)

private class MutableDashboardDevice(
    val id: String,
    var displayName: String,
    var category: String,
) {
    val addresses = linkedSetOf<String>()
    val hostnames = linkedSetOf<String>()
    val protocols = linkedSetOf<String>()
    val services = linkedSetOf<ZeroconfDashboardService>()
    val dnsRecords = linkedSetOf<ZeroconfDnsRecordView>()
    val txtRecords = linkedSetOf<ZeroconfTxtRecordView>()
    val locations = linkedSetOf<String>()
    val documents = mutableListOf<MutableDiscoveryDocument>()
    val details = linkedMapOf<String, String>()
    val firstSeen: String = Instant.now().toString()
    var lastSeen: String = firstSeen
    var evidenceCount: Int = 0

    fun touch() {
        lastSeen = Instant.now().toString()
        evidenceCount += 1
    }

    fun toDevice(): ZeroconfDashboardDevice =
        ZeroconfDashboardDevice(
            id = id,
            displayName = displayName,
            category = category,
            addresses = addresses.toList().sorted(),
            hostnames = hostnames.toList().sorted(),
            protocols = protocols.toList().sorted(),
            services = services.toList().takeLast(20),
            dnsRecords = dnsRecords.toList().takeLast(30),
            txtRecords = txtRecords.toList().sortedWith(compareBy({ it.service }, { it.key })),
            locations = locations.toList().sorted(),
            documents = documents.mapIndexed { index, document -> document.toDocument(index) },
            firstSeen = firstSeen,
            lastSeen = lastSeen,
            evidenceCount = evidenceCount,
            confidence = when {
                protocols.size >= 2 -> "high"
                evidenceCount >= 3 -> "medium"
                else -> "low"
            },
            details = details.toMap(),
        )
}

private class MutableHostnameResolution(
    val hostname: String,
) {
    val addresses = linkedSetOf<String>()
    val protocols = linkedSetOf<String>()
    val records = linkedSetOf<ZeroconfDnsRecordView>()

    fun toResolution(): ZeroconfHostnameResolution =
        ZeroconfHostnameResolution(
            hostname = hostname,
            addresses = addresses.toList().sorted(),
            protocols = protocols.toList().sorted(),
            records = records.toList(),
        )
}

private class MutableServiceTypeInfo(
    val protocol: String,
    val type: String,
    val title: String,
    val description: String,
) {
    val observedKeys = linkedSetOf<String>()

    fun toInfo(): ZeroconfServiceTypeInfo =
        ZeroconfServiceTypeInfo(
            protocol = protocol,
            type = type,
            title = title,
            description = description,
            observed = observedKeys.size,
        )
}
