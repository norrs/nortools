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
import java.net.URI
import java.time.Duration
import java.time.Instant
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

    private fun runDiscoveryCycle() {
        safeProtocol("mDNS") {
            val client = MdnsClient(timeout = timeout)
            val records = listOf(
                client.query("_services._dns-sd._udp.local", "PTR", maxPackets = 30),
                client.query("_http._tcp.local", "PTR", maxPackets = 30),
                client.query("_ipp._tcp.local", "PTR", maxPackets = 30),
            ).flatMap { result ->
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
            val result = WsDiscoveryClient(timeout = timeout).probe(maxPackets = 60)
            result.warnings.forEach { rememberDiscoveryWarning(it) }
            val messages = result.messages.filterNot { it.messageType == "Probe" || it.messageType == "Resolve" }
            messages.forEach(::ingestWsd)
            updateStats("WS-Discovery", messages.size)
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
                val result = WsDiscoveryClient(timeout = passiveListenTimeout).listen(maxPackets = 250)
                rememberPassiveWarnings(result.warnings)
                val messages = result.messages.filterNot { it.messageType == "Probe" || it.messageType == "Resolve" }
                messages.forEach(::ingestWsd)
                updateStats("WS-Discovery", messages.size)
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
            val resolution = hostnameRecords.getOrPut("mdns:$host") { MutableHostnameResolution(host) }
            resolution.protocols += "mDNS"
            resolution.addresses += record.data
            resolution.records += ZeroconfDnsRecordView(host, record.type, record.data, record.ttl)
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
        device.hostnames += cleanDnsName(record.name)
        device.services += ZeroconfDashboardService("LLMNR", record.type, record.name, record.data, "")
        if (record.type == "A" || record.type == "AAAA") device.addresses += record.data.trim('"')
        device.dnsRecords += ZeroconfDnsRecordView(cleanDnsName(record.name), record.type, record.data, record.ttl)
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

    @Synchronized
    private fun ingestWsd(message: WsDiscoveryMessage) {
        val key = message.endpointReference?.let { "wsd:${extractUuid(it) ?: it.lowercase()}" }
            ?: message.xAddrs?.let { "wsd:${locationHost(it) ?: it.lowercase()}" }
            ?: "wsd:${message.messageId ?: message.action ?: message.rawXml.hashCode()}"
        val device = device(key, message.types ?: message.endpointReference ?: "WS-Discovery device", inferWsdCategory(message))
        device.protocols += "WS-Discovery"
        message.xAddrs?.let {
            device.locations += it
            locationHost(it)?.let { host -> device.addresses += host }
        }
        device.services += ZeroconfDashboardService(
            protocol = "WS-Discovery",
            type = message.messageType,
            name = message.types ?: "",
            target = message.endpointReference ?: "",
            location = message.xAddrs ?: "",
            description = wsdTypeInfo(message.types ?: "").second,
        )
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
        rememberEvent("WS-Discovery", "${message.messageType} ${message.types ?: message.endpointReference ?: ""}".trim())
    }

    @Synchronized
    private fun ingestNetbios(response: NetbiosResponse) {
        val key = "netbios:${response.sourceAddress}"
        val name = response.names.firstOrNull()?.name ?: response.addresses.firstOrNull()?.name ?: response.sourceAddress
        val device = device(key, name, "Windows / SMB host")
        device.protocols += "NetBIOS"
        device.hostnames += name
        device.addresses += response.sourceAddress
        response.names.forEach { nbName ->
            device.services += ZeroconfDashboardService("NetBIOS", "NBSTAT", nbName.name, "0x${nbName.suffix.toString(16)}", "")
        }
        response.addresses.forEach { address ->
            device.services += ZeroconfDashboardService("NetBIOS", "NB", address.name, address.address, "")
            device.addresses += address.address
        }
        device.touch()
        rememberEvent("NetBIOS", "$name from ${response.sourceAddress}")
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

    private fun extractUuid(value: String): String? =
        Regex("""uuid:[a-zA-Z0-9._-]+""").find(value)?.value?.lowercase()

    private fun inferDnsSdCategory(serviceType: String, instance: String): String {
        val text = "$serviceType $instance".lowercase()
        return when {
            "_ipp" in text || "_printer" in text -> "Printer"
            "_airplay" in text || "_raop" in text || "_spotify" in text || "_googlecast" in text -> "Media"
            "_hap" in text || "_homekit" in text -> "Smart Home"
            "_http" in text -> "Web service"
            else -> "DNS-SD service"
        }
    }

    private fun dnsSdServiceInfo(type: String): Pair<String, String> {
        val normalized = type.lowercase().removeSuffix(".")
        return when {
            normalized == "_ipp._tcp.local" -> "IPP Printer" to "Internet Printing Protocol. AirPrint and Mopria printers commonly expose queues here."
            normalized == "_ipps._tcp.local" -> "Secure IPP Printer" to "Encrypted IPP printing over TLS."
            normalized == "_printer._tcp.local" -> "LPD Printer" to "Legacy printer service discovery."
            normalized == "_scanner._tcp.local" || normalized == "_uscan._tcp.local" -> "Scanner" to "Scanner discovery, often paired with multifunction printers."
            normalized == "_http._tcp.local" -> "HTTP Service" to "Embedded web interface or device administration endpoint."
            normalized == "_http-alt._tcp.local" -> "Alternate HTTP Service" to "HTTP service on a non-standard port."
            normalized == "_airplay._tcp.local" -> "AirPlay" to "Apple media playback or display target."
            normalized == "_raop._tcp.local" -> "AirPlay Audio" to "Remote Audio Output Protocol used by AirPlay speakers and receivers."
            normalized == "_spotify-connect._tcp.local" -> "Spotify Connect" to "Spotify playback target."
            normalized == "_googlecast._tcp.local" -> "Google Cast" to "Chromecast or Google Cast media receiver."
            normalized == "_hap._tcp.local" -> "HomeKit" to "Apple HomeKit accessory service."
            normalized == "_hue._tcp.local" -> "Hue Bridge" to "Philips Hue bridge discovery."
            else -> type to "DNS-SD service type advertised on the local link."
        }
    }

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

    private fun inferWsdCategory(message: WsDiscoveryMessage): String {
        val text = listOf(message.types, message.scopes, message.xAddrs).filterNotNull().joinToString(" ").lowercase()
        return when {
            "print" in text || "scanner" in text -> "Printer / Scanner"
            "camera" in text || "onvif" in text -> "Camera"
            "computer" in text -> "Computer"
            else -> "WSD device"
        }
    }
}

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
