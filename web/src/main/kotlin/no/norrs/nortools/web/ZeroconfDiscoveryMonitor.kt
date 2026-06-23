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

data class ZeroconfDashboardSnapshot(
    val generatedAt: String,
    val running: Boolean,
    val scanning: Boolean,
    val lastCycleStartedAt: String?,
    val lastCycleFinishedAt: String?,
    val deviceCount: Int,
    val serviceCount: Int,
    val devices: List<ZeroconfDashboardDevice>,
    val events: List<ZeroconfDashboardEvent>,
    val protocolStats: List<ZeroconfProtocolStat>,
    val warnings: List<String>,
)

data class ZeroconfDashboardDevice(
    val id: String,
    val displayName: String,
    val category: String,
    val addresses: List<String>,
    val protocols: List<String>,
    val services: List<ZeroconfDashboardService>,
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
    val lastSeen: String?,
)

object ZeroconfDiscoveryMonitor {
    private val timeout = Duration.ofSeconds(2)
    private val devices = linkedMapOf<String, MutableDashboardDevice>()
    private val events = ArrayDeque<ZeroconfDashboardEvent>()
    private val warnings = ArrayDeque<String>()
    private val stats = linkedMapOf<String, ZeroconfProtocolStat>()
    private val started = AtomicBoolean(false)
    private val scanning = AtomicBoolean(false)
    private var lastCycleStartedAt: String? = null
    private var lastCycleFinishedAt: String? = null
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "zeroconf-discovery-monitor").apply { isDaemon = true }
    }

    fun start() {
        if (started.compareAndSet(false, true)) {
            executor.scheduleWithFixedDelay({ refreshNow() }, 0, 15, TimeUnit.SECONDS)
        }
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
            records.forEach(::ingestMdns)
            updateStats("mDNS", records.size)
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
        safeProtocol("LLMNR") {
            val result = LlmnrClient(timeout = timeout).listen(ipFamily = IpFamily.BOTH, maxPackets = 30)
            result.warnings.forEach { rememberDiscoveryWarning(it) }
            result.records.forEach(::ingestLlmnr)
            updateStats("LLMNR", result.records.size)
        }
        safeProtocol("NetBIOS") {
            val responses = NetbiosNameServiceClient(timeout = timeout).listen(maxPackets = 20)
            responses.forEach(::ingestNetbios)
            updateStats("NetBIOS", responses.size)
        }
    }

    private fun safeProtocol(protocol: String, block: () -> Unit) {
        runCatching(block).onFailure { error ->
            rememberWarning("$protocol discovery failed: ${error.message ?: error::class.java.simpleName}")
            updateStats(protocol, 0, status = "warning")
        }
    }

    @Synchronized
    private fun ingestMdns(record: MdnsRecord) {
        if (record.type == "NSEC") return
        if (record.type == "PTR" && record.name.equals("_services._dns-sd._udp.local.", ignoreCase = true)) {
            rememberEvent("mDNS", "Service type ${record.data}")
            return
        }

        val key = when (record.type) {
            "A", "AAAA" -> "mdns-host:${record.name.lowercase()}"
            "SRV" -> "mdns-host:${mdnsSrvTarget(record.data)?.lowercase() ?: record.name.lowercase()}"
            else -> "mdns-service:${(record.data.takeIf { record.type == "PTR" } ?: record.name).lowercase()}"
        }
        val label = when (record.type) {
            "A", "AAAA" -> record.name
            "SRV", "TXT" -> record.name
            "PTR" -> record.data
            else -> record.data.ifBlank { record.name }
        }
        val device = device(key, label, inferMdnsCategory(record))
        device.protocols += "mDNS"
        device.services += ZeroconfDashboardService("mDNS", record.type, record.name, record.data, "")
        if (record.type == "A" || record.type == "AAAA") device.addresses += record.data.trim('"')
        device.details["mDNS ${record.type}"] = record.data
        device.touch()
        rememberEvent("mDNS", "${record.type} ${record.name} -> ${record.data}")
    }

    @Synchronized
    private fun ingestLlmnr(record: LlmnrRecord) {
        val key = "llmnr:${record.name.lowercase()}:${record.data.lowercase()}"
        val device = device(key, record.name, "Host")
        device.protocols += "LLMNR"
        device.services += ZeroconfDashboardService("LLMNR", record.type, record.name, record.data, "")
        if (record.type == "A" || record.type == "AAAA") device.addresses += record.data.trim('"')
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
            type = if (message.isNotify) "NOTIFY" else "Response",
            name = message.notificationType ?: message.searchTarget ?: "ssdp",
            target = message.uniqueServiceName ?: "",
            location = message.location ?: "",
        )
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
        )
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
        stats[protocol] = ZeroconfProtocolStat(
            protocol = protocol,
            status = status,
            observations = observations,
            lastSeen = Instant.now().toString(),
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
        }

    private fun cleanLabel(value: String): String =
        value.trim()
            .trim('"')
            .removeSuffix(".")
            .replace("\\032", " ")
            .replace("\\(", "(")
            .replace("\\)", ")")
            .ifBlank { "Unknown device" }

    private fun mdnsSrvTarget(data: String): String? =
        data.trim().split(Regex("\\s+")).lastOrNull()?.takeIf { it.contains('.') }

    private fun locationHost(location: String): String? =
        runCatching { URI(location).host }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun extractUuid(value: String): String? =
        Regex("""uuid:[a-zA-Z0-9._-]+""").find(value)?.value?.lowercase()

    private fun inferMdnsCategory(record: MdnsRecord): String {
        val text = "${record.name} ${record.data}".lowercase()
        return when {
            "_ipp" in text || "_printer" in text -> "Printer"
            "_airplay" in text || "_raop" in text || "_spotify" in text || "_googlecast" in text -> "Media"
            "_hap" in text || "_homekit" in text -> "Smart Home"
            "_http" in text -> "Web service"
            else -> "DNS-SD service"
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
    val protocols = linkedSetOf<String>()
    val services = linkedSetOf<ZeroconfDashboardService>()
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
            protocols = protocols.toList().sorted(),
            services = services.toList().takeLast(20),
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
