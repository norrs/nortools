package no.norrs.nortools.web

import io.javalin.http.Context
import no.norrs.nortools.lib.zeroconf.MdnsClient
import org.xbill.DNS.ARecord
import org.xbill.DNS.AAAARecord
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.PTRRecord
import org.xbill.DNS.SRVRecord
import org.xbill.DNS.Section
import org.xbill.DNS.TXTRecord
import java.io.File
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.absolutePathString

private const val IPERF_SERVICE_TYPE = "_iperf3._tcp.local."
private const val DEFAULT_IPERF_PORT = 5201

fun iperfStatus(ctx: Context) {
    val locator = IperfBinaryLocator()
    val located = locator.locate()
    val binary = located?.path
    val version = located?.version
    ctx.jsonResult(
        IperfBinaryStatusResponse(
            available = located != null,
            path = binary?.absolutePathString(),
            version = version?.version,
            output = version?.output,
            error = version?.error ?: locator.rejected.firstOrNull(),
            searched = locator.searched,
            rejected = locator.rejected,
        )
    )
}

fun iperfPublicServers(ctx: Context) {
    ctx.jsonResult(PublicIperfCatalog.seed())
}

fun iperfDiscover(ctx: Context) {
    val timeoutMs = ctx.queryParam("timeoutMs")?.toLongOrNull()?.let { it.coerceIn(500, 8000) } ?: 3000
    val result = MdnsClient(timeout = Duration.ofMillis(timeoutMs)).query(
        name = IPERF_SERVICE_TYPE,
        type = "PTR",
        maxPackets = 40,
    )
    val services = parseIperfServicesFromMdns(result.records)
    ctx.jsonResult(
        IperfDiscoveryResponse(
            serviceType = IPERF_SERVICE_TYPE,
            services = services,
            records = result.records,
            warnings = result.warnings,
        )
    )
}

fun iperfRunClient(ctx: Context) {
    ctx.jsonResult(runIperfClientRequest(readIperfClientRequest(ctx)))
}

fun iperfStartClient(ctx: Context) {
    val job = IperfClientJobs.start(readIperfClientRequest(ctx))
    ctx.status(202).jsonResult(job)
}

fun iperfClientJob(ctx: Context) {
    val id = ctx.pathParam("id")
    val job = IperfClientJobs.get(id)
    if (job == null) {
        ctx.status(404).jsonResult(mapOf("error" to "iperf client job not found"))
        return
    }
    ctx.jsonResult(job)
}

private fun readIperfClientRequest(ctx: Context): IperfClientRequest {
    val body = jsonReadTree(ctx.body().ifBlank { "{}" })
    val host = body["host"]?.asText()?.trim().orEmpty()
    require(host.isNotBlank()) { "host is required" }
    return IperfClientRequest(
        host = host,
        port = body["port"]?.asInt(DEFAULT_IPERF_PORT)?.coerceIn(1, 65535) ?: DEFAULT_IPERF_PORT,
        durationSeconds = body["durationSeconds"]?.asInt(10)?.coerceIn(1, 60) ?: 10,
        protocol = body["protocol"]?.asText("tcp")?.lowercase(Locale.ROOT)?.let {
            if (it == "udp") "udp" else "tcp"
        } ?: "tcp",
        ipVersion = body["ipVersion"]?.asText("auto")?.lowercase(Locale.ROOT)?.let {
            if (it == "ipv4" || it == "ipv6") it else "auto"
        } ?: "auto",
        parallel = body["parallel"]?.asInt(1)?.coerceIn(1, 16) ?: 1,
        reverse = body["reverse"]?.asBoolean(false) ?: false,
        bitrate = body["bitrate"]?.asText()?.trim()?.takeIf { it.isNotBlank() },
    )
}

private fun runIperfClientRequest(request: IperfClientRequest): IperfClientResult {
    val binary = IperfBinaryLocator().locate()?.path
        ?: throw IllegalStateException("iperf3 binary not found")
    val args = buildList {
        add(binary.absolutePathString())
        when {
            request.ipVersion == "ipv4" -> add("-4")
            request.ipVersion == "ipv6" -> add("-6")
            isIpv4Literal(request.host) -> add("-4")
            isIpv6Literal(request.host) -> add("-6")
        }
        add("-c")
        add(request.host)
        add("-p")
        add(request.port.toString())
        add("--json")
        add("--connect-timeout")
        add("5000")
        add("-t")
        add(request.durationSeconds.toString())
        if (request.protocol == "udp") add("-u")
        if (request.protocol == "udp" && !request.bitrate.isNullOrBlank()) {
            add("-b")
            add(request.bitrate)
        }
        if (request.parallel > 1) {
            add("-P")
            add(request.parallel.toString())
        }
        if (request.reverse) add("-R")
    }

    val started = System.nanoTime()
    val process = ProcessBuilder(args)
        .directory(binary.parent?.toFile())
        .redirectErrorStream(true)
        .start()
    val outputRef = AtomicReference("")
    val reader = Thread {
        outputRef.set(process.inputStream.bufferedReader().readText())
    }.apply {
        isDaemon = true
        name = "nortools-iperf3-client-output"
        start()
    }
    val finished = process.waitFor((request.durationSeconds + 15).toLong(), TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        process.waitFor(2, TimeUnit.SECONDS)
        reader.join(1000)
        val output = outputRef.get()
        return IperfClientResult(
            ok = false,
            command = args.drop(1),
            exitCode = null,
            elapsedMs = (System.nanoTime() - started) / 1_000_000,
            summary = null,
            rawJson = null,
            rawOutput = output,
            error = "iperf3 timed out",
        )
    }
    reader.join(1000)
    val output = outputRef.get()

    val exit = process.exitValue()
    val parsed = parseIperfJson(output)
    return IperfClientResult(
        ok = exit == 0 && parsed.error == null,
        command = args.drop(1),
        exitCode = exit,
        elapsedMs = (System.nanoTime() - started) / 1_000_000,
        summary = parsed.summary,
        rawJson = parsed.rawJson,
        rawOutput = if (parsed.rawJson == null) output else null,
        error = parsed.error ?: if (exit == 0) null else output.lineSequence().firstOrNull { it.isNotBlank() },
    )
}

private object IperfClientJobs {
    private val threadCounter = AtomicLong()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable).apply {
            isDaemon = true
            name = "nortools-iperf3-client-${threadCounter.incrementAndGet()}"
        }
    }
    private val jobs = ConcurrentHashMap<String, MutableIperfClientJob>()

    fun start(request: IperfClientRequest): IperfClientJobStatus {
        cleanup()
        val id = UUID.randomUUID().toString()
        val job = MutableIperfClientJob(id = id, startedAt = Instant.now().toString())
        jobs[id] = job
        executor.submit {
            job.status = "running"
            try {
                job.result = runIperfClientRequest(request)
                job.status = "completed"
            } catch (e: Exception) {
                job.error = e.message ?: e::class.simpleName ?: "iperf client failed"
                job.status = "failed"
            } finally {
                job.completedAt = Instant.now().toString()
            }
        }
        return job.snapshot()
    }

    fun get(id: String): IperfClientJobStatus? = jobs[id]?.snapshot()

    private fun cleanup() {
        val cutoff = Instant.now().minus(Duration.ofMinutes(15))
        jobs.entries.removeIf { (_, job) ->
            val completed = job.completedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
            completed != null && completed.isBefore(cutoff)
        }
    }
}

private class MutableIperfClientJob(
    val id: String,
    val startedAt: String,
) {
    @Volatile
    var status: String = "queued"
    @Volatile
    var completedAt: String? = null
    @Volatile
    var result: IperfClientResult? = null
    @Volatile
    var error: String? = null

    fun snapshot(): IperfClientJobStatus =
        IperfClientJobStatus(
            id = id,
            status = status,
            startedAt = startedAt,
            completedAt = completedAt,
            result = result,
            error = error,
        )
}

private fun isIpv4Literal(value: String): Boolean =
    value.matches(Regex("\\d{1,3}(?:\\.\\d{1,3}){3}"))

private fun isIpv6Literal(value: String): Boolean =
    value.contains(":")

fun iperfServerStatus(ctx: Context) {
    ctx.jsonResult(IperfServerRuntime.status())
}

fun iperfServerStart(ctx: Context) {
    val body = jsonReadTree(ctx.body().ifBlank { "{}" })
    val port = body["port"]?.asInt(DEFAULT_IPERF_PORT)?.coerceIn(1, 65535) ?: DEFAULT_IPERF_PORT
    val publish = body["publishMdns"]?.asBoolean(true) ?: true
    ctx.jsonResult(IperfServerRuntime.start(port = port, publishMdns = publish))
}

fun iperfServerStop(ctx: Context) {
    ctx.jsonResult(IperfServerRuntime.stop())
}

private object IperfServerRuntime {
    private var process: Process? = null
    private var startedAt: Instant? = null
    private var port: Int = DEFAULT_IPERF_PORT
    private var output: String = ""
    private var lastError: String? = null
    private var advertiser: IperfMdnsAdvertiser? = null

    @Synchronized
    fun start(port: Int, publishMdns: Boolean): IperfServerStatusResponse {
        val current = process
        if (current != null && current.isAlive && this.port == port && isPortListening(port)) return status()
        if (current != null && current.isAlive) stop()
        val binary = IperfBinaryLocator().locate()?.path
            ?: throw IllegalStateException("iperf3 binary not found")
        output = ""
        lastError = null
        this.port = port
        val launched = ProcessBuilder(binary.absolutePathString(), "-s", "-p", port.toString(), "--json")
            .directory(binary.parent?.toFile())
            .redirectErrorStream(true)
            .start()
        process = launched
        startedAt = Instant.now()
        Thread {
            output = launched.inputStream.bufferedReader().readText().takeLast(16_000)
        }.apply {
            isDaemon = true
            name = "nortools-iperf3-server-output"
            start()
        }
        if (!waitForListeningPort(port, Duration.ofSeconds(3))) {
            lastError = "iperf3 server process started but 127.0.0.1:$port did not accept TCP connections"
            launched.destroy()
            if (!launched.waitFor(2, TimeUnit.SECONDS)) {
                launched.destroyForcibly()
            }
            process = null
            advertiser?.stop()
            advertiser = null
            return status()
        }
        advertiser?.stop()
        advertiser = if (publishMdns) {
            IperfMdnsAdvertiser(port = port, iperfVersion = runIperfVersion(binary).version).also { it.start() }
        } else {
            null
        }
        return status()
    }

    @Synchronized
    fun stop(): IperfServerStatusResponse {
        advertiser?.stop()
        advertiser = null
        process?.destroy()
        if (process?.waitFor(2, TimeUnit.SECONDS) == false) {
            process?.destroyForcibly()
        }
        process = null
        lastError = null
        output = ""
        return status()
    }

    @Synchronized
    fun status(): IperfServerStatusResponse {
        val alive = process?.isAlive == true
        val running = alive && isPortListening(port)
        if (alive && !running && lastError == null) {
            lastError = "iperf3 server process is alive but 127.0.0.1:$port is not accepting TCP connections"
        }
        val started = startedAt
        return IperfServerStatusResponse(
            running = running,
            port = if (running) port else null,
            pid = if (running) process?.pid() else null,
            startedAt = if (running) started?.toString() else null,
            uptimeSeconds = if (running && started != null) Duration.between(started, Instant.now()).seconds else null,
            mdnsPublished = advertiser?.running == true,
            serviceType = if (advertiser?.running == true) IPERF_SERVICE_TYPE else null,
            lastOutput = output.takeLast(4000),
            lastError = lastError,
        )
    }

    private fun waitForListeningPort(port: Int, timeout: Duration): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (isPortListening(port)) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun isPortListening(port: Int): Boolean {
        val process = runCatching {
            ProcessBuilder("netstat", "-an")
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return true
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return true
        }
        val output = process.inputStream.bufferedReader().readText()
        val portPattern = Regex("""(?i)(?:^|\s)(?:tcp[46]?|tcp)\s+.*(?::|\.)$port\s+.*\bLISTEN(?:ING)?\b""")
        return output.lineSequence().any { line -> portPattern.containsMatchIn(line) }
    }
}

private class IperfBinaryLocator {
    val searched = mutableListOf<String>()
    val rejected = mutableListOf<String>()

    fun locate(): LocatedIperfBinary? {
        val exeName = if (isWindowsPlatform()) "iperf3.exe" else "iperf3"
        val candidates = mutableListOf<Path>()
        currentExecutableDir()?.let { candidates.add(it.resolve(exeName)) }
        Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().let { cwd ->
            candidates.add(cwd.resolve(exeName))
            candidates.add(cwd.resolve("bazel-bin/tools/network/iperf/iperf3-host-bin"))
        }
        System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }.forEach {
            candidates.add(Path.of(it).resolve(exeName))
        }
        for (candidate in candidates.distinct()) {
            searched.add(candidate.absolutePathString())
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                val version = runIperfVersion(candidate)
                if (!version.ok) {
                    rejected.add("${candidate.absolutePathString()}: ${version.error ?: "version check failed"}")
                    continue
                }
                if (isUnsupportedWindowsIperf(version)) {
                    rejected.add("${candidate.absolutePathString()}: unsupported Cygwin iperf3 build")
                    continue
                }
                return LocatedIperfBinary(candidate, version)
            }
        }
        return null
    }

    private fun currentExecutableDir(): Path? {
        val command = ProcessHandle.current().info().command().orElse(null) ?: return null
        return runCatching { Path.of(command).toAbsolutePath().normalize().parent }.getOrNull()
    }
}

private data class LocatedIperfBinary(
    val path: Path,
    val version: IperfVersionResult,
)

private fun isWindowsPlatform(): Boolean =
    System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")

private fun isUnsupportedWindowsIperf(version: IperfVersionResult): Boolean =
    isWindowsPlatform() && version.output.orEmpty().contains("CYGWIN_NT", ignoreCase = true)

private data class IperfVersionResult(
    val ok: Boolean,
    val version: String?,
    val output: String?,
    val error: String?,
)

private fun runIperfVersion(binary: Path): IperfVersionResult {
    return try {
        val process = ProcessBuilder(binary.absolutePathString(), "--version")
            .directory(binary.parent?.toFile())
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().readText()
        if (!finished) {
            process.destroyForcibly()
            IperfVersionResult(false, null, output, "Timed out")
        } else {
            val firstLine = output.lineSequence().firstOrNull { it.isNotBlank() }
            IperfVersionResult(process.exitValue() == 0, firstLine, output, null)
        }
    } catch (e: Exception) {
        IperfVersionResult(false, null, null, e.message ?: e::class.simpleName)
    }
}

private data class ParsedIperfJson(
    val summary: IperfResultSummary?,
    val rawJson: String?,
    val error: String?,
)

private fun parseIperfJson(output: String): ParsedIperfJson {
    val jsonStart = output.indexOf('{')
    if (jsonStart < 0) return ParsedIperfJson(null, null, output.ifBlank { "No iperf output" })
    val raw = output.substring(jsonStart)
    return try {
        val root = jsonReadTree(raw)
        val error = root["error"]?.asText()
        val end = root["end"]
        val sumSent = end?.get("sum_sent")
        val sumReceived = end?.get("sum_received")
        val sum = end?.get("sum") ?: sumSent ?: sumReceived
        ParsedIperfJson(
            summary = IperfResultSummary(
                protocol = root["start"]?.get("test_start")?.get("protocol")?.asText(),
                durationSeconds = sum?.get("seconds")?.asDouble(),
                sentBitsPerSecond = sumSent?.get("bits_per_second")?.asDouble(),
                receivedBitsPerSecond = sumReceived?.get("bits_per_second")?.asDouble() ?: sum?.get("bits_per_second")?.asDouble(),
                retransmits = sumSent?.get("retransmits")?.asInt(),
                jitterMs = sum?.get("jitter_ms")?.asDouble(),
                lostPackets = sum?.get("lost_packets")?.asInt(),
                totalPackets = sum?.get("packets")?.asInt(),
                lostPercent = sum?.get("lost_percent")?.asDouble(),
            ),
            rawJson = raw,
            error = error,
        )
    } catch (e: Exception) {
        ParsedIperfJson(null, raw, e.message ?: "Failed to parse iperf JSON")
    }
}

private fun parseIperfServicesFromMdns(records: List<no.norrs.nortools.lib.zeroconf.MdnsRecord>): List<IperfDiscoveredService> {
    val instances = records.filter { it.type == "PTR" && it.name.equals(IPERF_SERVICE_TYPE, ignoreCase = true) }
        .map { it.data.trimEnd('.') }
        .distinct()
    return instances.map { instance ->
        val srv = records.firstOrNull { it.type == "SRV" && it.name.trimEnd('.').equals(instance, ignoreCase = true) }
        val txt = records.filter { it.type == "TXT" && it.name.trimEnd('.').equals(instance, ignoreCase = true) }.map { it.data }
        val addresses = records.filter { (it.type == "A" || it.type == "AAAA") && srv?.data?.contains(it.name.trimEnd('.')) == true }
            .map { it.data.trim() }
            .distinct()
        val endpointCandidates = addresses.map { discoveredAddressCandidate(it) }
        val srvParts = srv?.data?.split(Regex("\\s+")).orEmpty()
        IperfDiscoveredService(
            instance = instance,
            host = srvParts.getOrNull(3)?.trimEnd('.') ?: instance,
            port = srvParts.getOrNull(2)?.toIntOrNull() ?: DEFAULT_IPERF_PORT,
            addresses = addresses,
            endpoints = endpointCandidates,
            txt = txt,
        )
    }
}

private fun discoveredAddressCandidate(address: String): IperfDiscoveredEndpoint {
    val normalized = address.trim()
    if (isIpv4Literal(normalized)) {
        return IperfDiscoveredEndpoint(
            address = normalized,
            connectHost = normalized,
            family = "ipv4",
            interfaceName = null,
            scopeId = null,
            linkLocal = false,
        )
    }
    val linkLocal = normalized.lowercase(Locale.ROOT).startsWith("fe80:")
    val scopedInterface = if (linkLocal) interfaceForLinkLocalAddress(normalized) else null
    val scope = scopedInterface?.index?.takeIf { it > 0 }?.toString() ?: scopedInterface?.name
    val connectHost = if (linkLocal && scope != null && !normalized.contains("%")) {
        "$normalized%$scope"
    } else {
        normalized
    }
    return IperfDiscoveredEndpoint(
        address = normalized,
        connectHost = connectHost,
        family = "ipv6",
        interfaceName = scopedInterface?.name,
        scopeId = scopedInterface?.index?.takeIf { it > 0 },
        linkLocal = linkLocal,
    )
}

private fun interfaceForLinkLocalAddress(address: String): NetworkInterface? {
    val normalized = address.substringBefore('%').lowercase(Locale.ROOT)
    return NetworkInterface.getNetworkInterfaces().toList()
        .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        .firstOrNull { iface ->
            iface.inetAddresses.toList()
                .filterIsInstance<Inet6Address>()
                .any { it.hostAddress.substringBefore('%').lowercase(Locale.ROOT) == normalized }
        }
        ?.let { return it }
        ?: firstMulticastInterfaceName()
}

private fun firstMulticastInterfaceName(): NetworkInterface? =
    NetworkInterface.getNetworkInterfaces().toList()
        .firstOrNull { iface ->
            runCatching {
                iface.isUp && !iface.isLoopback && iface.supportsMulticast() &&
                    iface.inetAddresses.toList().any { it is Inet6Address }
            }.getOrDefault(false)
        }

private class IperfMdnsAdvertiser(
    private val port: Int,
    private val iperfVersion: String?,
) {
    @Volatile
    var running: Boolean = false
        private set
    private var thread: Thread? = null
    private var socket: MulticastSocket? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread { runLoop() }.apply {
            isDaemon = true
            name = "nortools-iperf3-mdns"
            start()
        }
    }

    fun stop() {
        running = false
        socket?.close()
    }

    private fun runLoop() {
        val group = InetAddress.getByName(MdnsClient.MDNS_IPV4_GROUP)
        val target = InetSocketAddress(group, MdnsClient.MDNS_PORT)
        try {
            MulticastSocket(MdnsClient.MDNS_PORT).use { ms ->
                socket = ms
                ms.reuseAddress = true
                ms.soTimeout = 1500
                runCatching { ms.joinGroup(target, NetworkInterface.getByInetAddress(InetAddress.getLocalHost())) }
                sendAnnouncement(ms, target)
                var lastAnnounce = System.currentTimeMillis()
                val buffer = ByteArray(4096)
                while (running) {
                    if (System.currentTimeMillis() - lastAnnounce > 20_000) {
                        sendAnnouncement(ms, target)
                        lastAnnounce = System.currentTimeMillis()
                    }
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        ms.receive(packet)
                        val message = Message(packet.data.copyOf(packet.length))
                        val wantsIperf = message.getSection(Section.QUESTION).any {
                            it.name.toString().equals(IPERF_SERVICE_TYPE, ignoreCase = true) ||
                                it.name.toString().contains("_iperf3._tcp.local.", ignoreCase = true)
                        }
                        if (wantsIperf) sendAnnouncement(ms, target)
                    } catch (_: java.net.SocketTimeoutException) {
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
            running = false
        }
    }

    private fun sendAnnouncement(ms: MulticastSocket, target: InetSocketAddress) {
        val message = buildAnnouncement()
        val data = message.toWire()
        ms.send(DatagramPacket(data, data.size, target))
    }

    private fun buildAnnouncement(): Message {
        val host = localHostName()
        val hostName = Name.fromString("$host.local.")
        val instance = Name.fromString("NorTools iperf3 on $host.$IPERF_SERVICE_TYPE")
        val message = Message()
        message.header.setFlag(Flags.QR.toInt())
        message.addRecord(PTRRecord(Name.fromString(IPERF_SERVICE_TYPE), DClass.IN, 120, instance), Section.ANSWER)
        message.addRecord(SRVRecord(instance, DClass.IN, 120, 0, 0, port, hostName), Section.ADDITIONAL)
        message.addRecord(
            TXTRecord(
                instance,
                DClass.IN,
                120,
                listOf("app=nortools", "proto=tcp,udp", "json=1", "iperf_version=${iperfVersion ?: "unknown"}"),
            ),
            Section.ADDITIONAL,
        )
        localAddresses().forEach { address ->
            when (address) {
                is Inet4Address -> message.addRecord(ARecord(hostName, DClass.IN, 120, address), Section.ADDITIONAL)
                is Inet6Address -> message.addRecord(AAAARecord(hostName, DClass.IN, 120, address), Section.ADDITIONAL)
                else -> Unit
            }
        }
        return message
    }

    private fun localHostName(): String =
        runCatching { InetAddress.getLocalHost().hostName.substringBefore('.').ifBlank { "nortools" } }
            .getOrDefault("nortools")
            .replace(Regex("[^A-Za-z0-9-]"), "-")

    private fun localAddresses(): List<InetAddress> =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filterNot { it.isLoopbackAddress || it.isAnyLocalAddress }
}

data class IperfBinaryStatusResponse(
    val available: Boolean,
    val path: String?,
    val version: String?,
    val output: String?,
    val error: String?,
    val searched: List<String>,
    val rejected: List<String>,
)

data class IperfServerStatusResponse(
    val running: Boolean,
    val port: Int?,
    val pid: Long?,
    val startedAt: String?,
    val uptimeSeconds: Long?,
    val mdnsPublished: Boolean,
    val serviceType: String?,
    val lastOutput: String,
    val lastError: String?,
)

data class IperfClientRequest(
    val host: String,
    val port: Int,
    val durationSeconds: Int,
    val protocol: String,
    val ipVersion: String,
    val parallel: Int,
    val reverse: Boolean,
    val bitrate: String?,
)

data class IperfClientResult(
    val ok: Boolean,
    val command: List<String>,
    val exitCode: Int?,
    val elapsedMs: Long,
    val summary: IperfResultSummary?,
    val rawJson: String?,
    val rawOutput: String?,
    val error: String?,
)

data class IperfClientJobStatus(
    val id: String,
    val status: String,
    val startedAt: String,
    val completedAt: String?,
    val result: IperfClientResult?,
    val error: String?,
)

data class IperfResultSummary(
    val protocol: String?,
    val durationSeconds: Double?,
    val sentBitsPerSecond: Double?,
    val receivedBitsPerSecond: Double?,
    val retransmits: Int?,
    val jitterMs: Double?,
    val lostPackets: Int?,
    val totalPackets: Int?,
    val lostPercent: Double?,
)

data class IperfDiscoveryResponse(
    val serviceType: String,
    val services: List<IperfDiscoveredService>,
    val records: List<no.norrs.nortools.lib.zeroconf.MdnsRecord>,
    val warnings: List<String>,
)

data class IperfDiscoveredService(
    val instance: String,
    val host: String,
    val port: Int,
    val addresses: List<String>,
    val endpoints: List<IperfDiscoveredEndpoint>,
    val txt: List<String>,
)

data class IperfDiscoveredEndpoint(
    val address: String,
    val connectHost: String,
    val family: String,
    val interfaceName: String?,
    val scopeId: Int?,
    val linkLocal: Boolean,
)

data class PublicIperfCatalogResponse(
    val source: String,
    val retrieved: String,
    val notes: List<String>,
    val servers: List<PublicIperfServer>,
)

data class PublicIperfServer(
    val host: String,
    val region: String,
    val country: String,
    val location: String,
    val ports: List<PublicIperfPortRange>,
    val protocols: List<String>,
    val ipVersions: List<String>,
    val status: String,
    val lastTested: String,
)

data class PublicIperfPortRange(
    val from: Int,
    val to: Int,
)

private object PublicIperfCatalog {
    fun seed(): PublicIperfCatalogResponse =
        PublicIperfCatalogResponse(
            source = "https://iperf.fr/iperf-servers.php",
            retrieved = "2026-07-03",
            notes = listOf("Public iperf3 servers usually allow one active test at a time; busy is normal."),
            servers = listOf(
                server("ping.online.net", "Europe", "France", "Ile-de-France", 5200, 5209, listOf("ipv4", "ipv6")),
                server("ping6.online.net", "Europe", "France", "Ile-de-France", 5200, 5209, listOf("ipv4", "ipv6")),
                server("iperf3.moji.fr", "Europe", "France", "Ile-de-France", 5200, 5240, listOf("ipv4", "ipv6")),
                server("speedtest.milkywan.fr", "Europe", "France", "Ile-de-France", 9200, 9240, listOf("ipv4", "ipv6")),
                server("speedtest.serverius.net", "Europe", "Netherlands", "Serverius", 5002, 5002, listOf("ipv4", "ipv6")),
                server("nl.iperf.014.fr", "Europe", "Netherlands", "NextGenWebs", 10415, 10420, listOf("ipv4")),
                server("ch.iperf.014.fr", "Europe", "Switzerland", "Zurich", 15315, 15320, listOf("ipv4")),
                server("iperf.volia.net", "Europe", "Ukraine", "Kiev", 5201, 5201, listOf("ipv4")),
                server("speedtest.uztelecom.uz", "Asia", "Uzbekistan", "Tashkent", 5200, 5209, listOf("ipv4", "ipv6")),
                server("iperf.angolacables.co.ao", "Africa", "Angola", "Luanda", 9200, 9240, listOf("ipv4", "ipv6")),
            ),
        )

    private fun server(
        host: String,
        region: String,
        country: String,
        location: String,
        from: Int,
        to: Int,
        ipVersions: List<String>,
    ) = PublicIperfServer(
        host = host,
        region = region,
        country = country,
        location = location,
        ports = listOf(PublicIperfPortRange(from, to)),
        protocols = listOf("tcp", "udp"),
        ipVersions = ipVersions,
        status = "ok",
        lastTested = "2025-03",
    )
}
