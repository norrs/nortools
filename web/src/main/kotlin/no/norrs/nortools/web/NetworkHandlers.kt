package no.norrs.nortools.web

import io.javalin.http.Context
import no.norrs.nortools.lib.dns.DnsResolver
import no.norrs.nortools.lib.network.HttpClient
import no.norrs.nortools.lib.network.TcpClient
import org.xbill.DNS.Type
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.naming.ldap.LdapName
import javax.net.ssl.HttpsURLConnection
import java.net.http.HttpClient as JHttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

// ─── Network Tools ───────────────────────────────────────────────────────────

fun tcpCheck(ctx: Context) {
    val host = ctx.pathParam("host")
    val port = ctx.pathParam("port").toIntOrNull() ?: 80
    val banner = ctx.queryParam("banner") == "true"
    val client = TcpClient(timeout = Duration.ofSeconds(10))
    val result = client.connect(host, port, grabBanner = banner)
    ctx.jsonResult(result)
}

fun httpCheck(ctx: Context) {
    val url = ctx.pathParam("url")
    val fullUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) "http://$url" else url
    val client = HttpClient(timeout = Duration.ofSeconds(10))
    val result = client.get(fullUrl, includeBody = false)
    val response = HttpCheckResponse(
        url = result.url,
        statusCode = result.statusCode,
        responseTimeMs = result.responseTimeMs,
        error = result.error,
        headers = result.headers.entries.take(20).associate { it.key to it.value.joinToString(", ") },
    )
    ctx.jsonResult(response)
}

fun httpsCheck(ctx: Context) {
    val host = ctx.pathParam("host")
    val client = HttpClient(timeout = Duration.ofSeconds(10))
    val result = client.get("https://$host", includeBody = false)
    var certificateError: String? = null
    val certificateChain = try {
        fetchCertificateChain(host, timeoutSeconds = 10)
    } catch (e: Exception) {
        certificateError = e.message ?: "Failed to fetch certificate chain"
        null
    }
    val response = HttpsCheckResponse(
        url = result.url,
        statusCode = result.statusCode,
        responseTimeMs = result.responseTimeMs,
        error = result.error,
        ssl = result.sslSession?.let { SslInfo(protocol = it.protocol, cipherSuite = it.cipherSuite) },
        certificateChain = certificateChain,
        certificateError = certificateError,
        headers = result.headers.entries.take(20).associate { it.key to it.value.joinToString(", ") },
    )
    ctx.jsonResult(response)
}

fun networkInterfaces(ctx: Context) {
    val generatedAt = Instant.now().toString()
    val hostName = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown")
    val osName = System.getProperty("os.name").orEmpty()
    val osVersion = System.getProperty("os.version").orEmpty()
    val osArch = System.getProperty("os.arch").orEmpty()
    val platform = detectPlatformName()

    val interfaces = loadInterfaceInventory()
    val routesResult = loadRouteTable(platform)
    val dnsResult = loadDnsInfo(platform)
    val netbiosName = loadNetbiosName(platform, hostName)
    val dhcpByInterface = loadDhcpStatusByInterface(platform)

    val enriched = interfaces.map { info ->
        info.copy(
            dhcp = dhcpByInterface[info.name] ?: dhcpByInterface[info.displayName] ?: "unknown",
            dnsServers = dnsResult.interfaceDns[info.name]
                ?: dnsResult.interfaceDns[info.displayName]
                ?: emptyList(),
        )
    }

    val response = LocalNetworkSnapshot(
        generatedAt = generatedAt,
        host = HostIdentity(
            hostname = hostName,
            netbios = netbiosName,
            osName = osName,
            osVersion = osVersion,
            osArch = osArch,
            platform = platform,
        ),
        interfaces = enriched,
        routes = routesResult.routes,
        routingTableRaw = routesResult.raw,
        defaultDnsServers = dnsResult.defaultDns,
        interfaceDnsServers = dnsResult.interfaceDns,
        notes = dnsResult.notes + routesResult.notes,
    )
    ctx.jsonResult(response)
}

private fun fetchCertificateChain(hostname: String, timeoutSeconds: Int): List<CertificateInfo> {
    val dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.of("UTC"))
    val uri = URI.create("https://$hostname")
    val conn = uri.toURL().openConnection() as HttpsURLConnection
    conn.connectTimeout = timeoutSeconds * 1000
    conn.readTimeout = timeoutSeconds * 1000
    conn.instanceFollowRedirects = true

    try {
        conn.connect()
        val certs = conn.serverCertificates.filterIsInstance<X509Certificate>()
        val now = Instant.now()
        return certs.mapIndexed { index, cert ->
            val notBefore = cert.notBefore.toInstant()
            val notAfter = cert.notAfter.toInstant()
            val daysRemaining = ChronoUnit.DAYS.between(now, notAfter)
            val subject = cert.subjectX500Principal.name
            val issuer = cert.issuerX500Principal.name
            val publicKey = cert.publicKey
            val keySize = when (publicKey) {
                is RSAPublicKey -> publicKey.modulus.bitLength()
                is ECPublicKey -> publicKey.params.curve.field.fieldSize
                else -> null
            }
            CertificateInfo(
                index = index,
                subject = subject,
                issuer = issuer,
                commonName = dnAttribute(subject, "CN"),
                issuerCommonName = dnAttribute(issuer, "CN"),
                subjectAltNames = subjectAltNames(cert),
                validFrom = dtf.format(notBefore),
                validUntil = dtf.format(notAfter),
                validFromEpochMs = notBefore.toEpochMilli(),
                validUntilEpochMs = notAfter.toEpochMilli(),
                daysRemaining = daysRemaining,
                expired = now.isAfter(notAfter),
                serialNumber = cert.serialNumber.toString(16),
                signatureAlgorithm = cert.sigAlgName,
                publicKeyType = publicKey.algorithm,
                publicKeySize = keySize,
                isCA = cert.basicConstraints >= 0,
                keyUsage = keyUsage(cert),
                extendedKeyUsage = extendedKeyUsage(cert),
                sha256Fingerprint = sha256Fingerprint(cert),
                selfSigned = subject == issuer,
            )
        }
    } finally {
        conn.disconnect()
    }
}

internal fun detectPlatformName(): String {
    val os = System.getProperty("os.name").lowercase(Locale.ROOT)
    return when {
        "win" in os -> "windows"
        "mac" in os || "darwin" in os -> "macos"
        else -> "linux"
    }
}

private fun loadInterfaceInventory(): List<LocalInterfaceInfo> {
    val all = mutableListOf<LocalInterfaceInfo>()
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return all
    while (interfaces.hasMoreElements()) {
        val nif = interfaces.nextElement()
        val addresses = nif.interfaceAddresses.mapNotNull { ia ->
            val addr = ia.address ?: return@mapNotNull null
            LocalAddress(
                ip = addr.hostAddress.substringBefore("%"),
                family = if (addr.hostAddress.contains(":")) "IPv6" else "IPv4",
                prefixLength = ia.networkPrefixLength.toInt().takeIf { it >= 0 },
            )
        }
        all.add(
            LocalInterfaceInfo(
                name = nif.name.orEmpty(),
                displayName = nif.displayName ?: nif.name.orEmpty(),
                up = runCatching { nif.isUp }.getOrDefault(false),
                loopback = runCatching { nif.isLoopback }.getOrDefault(false),
                virtual = runCatching { nif.isVirtual }.getOrDefault(false),
                mtu = runCatching { nif.mtu }.getOrNull(),
                macAddress = nif.hardwareAddress?.joinToString(":") { b -> "%02X".format(b) },
                addresses = addresses,
                dhcp = "unknown",
                dnsServers = emptyList(),
            )
        )
    }
    return all.sortedBy { it.name }
}

private fun loadRouteTable(platform: String): RouteTableResult {
    val notes = mutableListOf<String>()
    val cmdCandidates = when (platform) {
        "windows" -> listOf(
            listOf("route", "print", "-4"),
            listOf("route", "print"),
        )
        "macos" -> listOf(
            listOf("netstat", "-rn"),
            listOf("route", "-n", "get", "default"),
        )
        else -> listOf(
            listOf("ip", "route", "show"),
            listOf("netstat", "-rn"),
        )
    }

    for (cmd in cmdCandidates) {
        val out = runCommand(cmd) ?: continue
        val rows = parseRoutes(out)
        if (rows.isNotEmpty() || out.isNotBlank()) {
            return RouteTableResult(routes = rows, raw = out, notes = notes)
        }
    }
    notes += "Could not read routing table from platform tools."
    return RouteTableResult(routes = emptyList(), raw = null, notes = notes)
}

fun parseRoutes(raw: String): List<RouteEntry> {
    val routes = mutableListOf<RouteEntry>()
    val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
    for (line in lines) {
        if (line.startsWith("Kernel", ignoreCase = true)) continue
        if (line.startsWith("Destination", ignoreCase = true)) continue
        if (line.startsWith("Routing", ignoreCase = true)) continue
        if (line.startsWith("Interface", ignoreCase = true)) continue
        if (line.startsWith("IPv4 Route", ignoreCase = true)) continue
        if (line.startsWith("Active Routes", ignoreCase = true)) continue
        if (line.startsWith("Persistent Routes", ignoreCase = true)) continue

        if (line.startsWith("default ") || line.startsWith("0.0.0.0 ")) {
            val parts = line.split(Regex("\\s+"))
            val destination = parts.getOrNull(0) ?: ""
            val gateway = if (parts.getOrNull(1) == "via") parts.getOrNull(2) else parts.getOrNull(1)
            val iface = if ("dev" in parts) {
                parts.getOrNull(parts.indexOf("dev") + 1)
            } else {
                parts.findLast {
                    it.startsWith("eth") || it.startsWith("en") || it.startsWith("wl") ||
                        it.startsWith("wlan") || it.startsWith("br") || it.startsWith("tun")
                }
            }
            routes += RouteEntry(destination = destination, gateway = gateway, interfaceName = iface, metric = parts.lastOrNull { it.toIntOrNull() != null }?.toIntOrNull(), raw = line)
            continue
        }

        val parts = line.split(Regex("\\s+"))
        if (parts.size >= 4 && parts[0].any { it.isDigit() || it == '.' || it == '/' }) {
            val destination = parts[0]
            val gateway = parts.getOrNull(1)
            val metric = parts.lastOrNull { it.toIntOrNull() != null }?.toIntOrNull()
            val iface = parts.findLast { it.startsWith("eth") || it.startsWith("en") || it.startsWith("wl") || it.startsWith("wlan") || it.startsWith("br") }
            routes += RouteEntry(destination = destination, gateway = gateway, interfaceName = iface, metric = metric, raw = line)
        }
    }
    return routes
}

private fun loadDnsInfo(platform: String): DnsInfoResult {
    return when (platform) {
        "windows" -> loadWindowsDnsInfo()
        "macos" -> loadMacDnsInfo()
        else -> loadLinuxDnsInfo()
    }
}

private fun loadWindowsDnsInfo(): DnsInfoResult {
    val notes = mutableListOf<String>()
    val interfaceDns = mutableMapOf<String, List<String>>()
    val defaultDns = mutableListOf<String>()
    val output = runCommand(listOf("netsh", "interface", "ip", "show", "dnsservers"))
        ?: runCommand(listOf("ipconfig", "/all"))
        ?: ""
    if (output.isBlank()) {
        notes += "Could not collect DNS settings from netsh/ipconfig."
        return DnsInfoResult(defaultDns = emptyList(), interfaceDns = emptyMap(), notes = notes)
    }

    var current = ""
    output.lines().forEach { raw ->
        val line = raw.trim()
        if (line.startsWith("Configuration for interface", ignoreCase = true)) {
            current = line.substringAfter("\"").substringBeforeLast("\"")
            if (current.isNotBlank() && current !in interfaceDns) interfaceDns[current] = emptyList()
        } else if (line.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
            if (current.isNotBlank()) {
                val cur = interfaceDns[current].orEmpty().toMutableList()
                cur += line
                interfaceDns[current] = cur
            }
            if (line !in defaultDns) defaultDns += line
        }
    }
    if (defaultDns.isEmpty()) {
        val fromIpconfig = Regex("DNS Servers[ .:]+([0-9.]+)").findAll(output).map { it.groupValues[1] }.toList()
        defaultDns += fromIpconfig.distinct()
    }
    return DnsInfoResult(defaultDns = defaultDns, interfaceDns = interfaceDns, notes = notes)
}

private fun loadMacDnsInfo(): DnsInfoResult {
    val notes = mutableListOf<String>()
    val interfaceDns = mutableMapOf<String, List<String>>()
    val defaultDns = mutableListOf<String>()

    val servicesOut = runCommand(listOf("networksetup", "-listallnetworkservices")) ?: ""
    val services = servicesOut.lines().drop(1).map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("*") }
    if (services.isEmpty()) {
        notes += "Could not enumerate macOS network services."
    }
    for (service in services) {
        val dnsOut = runCommand(listOf("networksetup", "-getdnsservers", service)) ?: continue
        val servers = dnsOut.lines().map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("There aren't any DNS Servers", ignoreCase = true) && it[0].isDigit() }
        if (servers.isNotEmpty()) {
            interfaceDns[service] = servers
            for (s in servers) if (s !in defaultDns) defaultDns += s
        }
    }
    if (defaultDns.isEmpty()) {
        defaultDns += parseResolvConfNameservers()
    }
    return DnsInfoResult(defaultDns = defaultDns.distinct(), interfaceDns = interfaceDns, notes = notes)
}

private fun loadLinuxDnsInfo(): DnsInfoResult {
    val notes = mutableListOf<String>()
    val interfaceDns = mutableMapOf<String, List<String>>()
    val defaultDns = mutableListOf<String>()

    val resolvectl = runCommand(listOf("resolvectl", "status"))
    if (!resolvectl.isNullOrBlank()) {
        val (parsedInterfaceDns, parsedDefaultDns) = parseLinuxResolvectlDns(resolvectl)
        for ((name, servers) in parsedInterfaceDns) {
            interfaceDns[name] = servers
        }
        for (dns in parsedDefaultDns) {
            if (dns !in defaultDns) defaultDns += dns
        }
    }

    if (defaultDns.isEmpty()) {
        val resolv = parseResolvConfNameservers()
        defaultDns += resolv
    }

    if (defaultDns.isEmpty()) {
        notes += "Could not find DNS servers via resolvectl or /etc/resolv.conf."
    }
    return DnsInfoResult(defaultDns = defaultDns.distinct(), interfaceDns = interfaceDns, notes = notes)
}

private fun parseResolvConfNameservers(): List<String> {
    val output = runCommand(listOf("cat", "/etc/resolv.conf")) ?: return emptyList()
    return parseResolvConfNameserversText(output)
}

fun parseLinuxResolvectlDns(raw: String): Pair<Map<String, List<String>>, List<String>> {
    val interfaceDns = mutableMapOf<String, List<String>>()
    val defaultDns = mutableListOf<String>()
    var currentIf: String? = null
    for (lineRaw in raw.lines()) {
        val line = lineRaw.trim()
        val linkMatch = Regex("^Link \\d+ \\(([^)]+)\\)").find(line)
        if (linkMatch != null) {
            currentIf = linkMatch.groupValues[1]
            continue
        }
        if (line.startsWith("DNS Servers:", ignoreCase = true)) {
            val servers = line.substringAfter(":").trim().split(Regex("\\s+"))
                .filter { it.matches(Regex("[0-9a-fA-F:.]+")) }
            if (servers.isNotEmpty()) {
                if (currentIf != null) interfaceDns[currentIf!!] = servers
                for (s in servers) if (s !in defaultDns) defaultDns += s
            }
        }
    }
    return interfaceDns to defaultDns
}

fun parseResolvConfNameserversText(output: String): List<String> {
    return output.lines()
        .map { it.trim() }
        .filter { it.startsWith("nameserver ") }
        .mapNotNull { it.substringAfter("nameserver ").trim().takeIf { v -> v.matches(Regex("[0-9a-fA-F:.]+")) } }
        .distinct()
}

private fun loadDhcpStatusByInterface(platform: String): Map<String, String> {
    return when (platform) {
        "windows" -> loadWindowsDhcpStatus()
        "macos" -> loadMacDhcpStatus()
        else -> loadLinuxDhcpStatus()
    }
}

private fun loadWindowsDhcpStatus(): Map<String, String> {
    val result = mutableMapOf<String, String>()
    val output = runCommand(listOf("netsh", "interface", "ip", "show", "config"))
        ?: runCommand(listOf("ipconfig", "/all"))
        ?: return result
    var current = ""
    output.lines().forEach { raw ->
        val line = raw.trim()
        if (line.startsWith("Configuration for interface", ignoreCase = true)) {
            current = line.substringAfter("\"").substringBeforeLast("\"")
        } else if (line.startsWith("DHCP enabled:", ignoreCase = true) && current.isNotBlank()) {
            val enabled = line.substringAfter(":").trim().lowercase(Locale.ROOT).startsWith("yes")
            result[current] = if (enabled) "enabled" else "disabled"
        }
    }
    return result
}

private fun loadMacDhcpStatus(): Map<String, String> {
    val result = mutableMapOf<String, String>()
    val interfaces = NetworkInterface.getNetworkInterfaces()
    while (interfaces != null && interfaces.hasMoreElements()) {
        val ifName = interfaces.nextElement().name ?: continue
        val out = runCommand(listOf("ipconfig", "getpacket", ifName))
        result[ifName] = if (!out.isNullOrBlank() && "yiaddr" in out) "enabled" else "unknown"
    }
    return result
}

private fun loadLinuxDhcpStatus(): Map<String, String> {
    val result = mutableMapOf<String, String>()
    val nmcli = runCommand(listOf("nmcli", "-t", "-f", "DEVICE,DHCP4.OPTION", "device", "show"))
    if (!nmcli.isNullOrBlank()) {
        for (line in nmcli.lines()) {
            val parts = line.trim().split(":")
            if (parts.size >= 2) {
                val dev = parts[0]
                val hasDhcp = parts.drop(1).joinToString(":").contains("dhcp", ignoreCase = true)
                result[dev] = if (hasDhcp) "enabled" else "unknown"
            }
        }
    }
    return result
}

private fun loadNetbiosName(platform: String, hostName: String): String? {
    if (platform == "windows") {
        val envName = System.getenv("COMPUTERNAME")?.trim().takeIf { !it.isNullOrEmpty() }
        if (envName != null) return envName
        val nbt = runCommand(listOf("nbtstat", "-n")) ?: return hostName
        val line = nbt.lines().firstOrNull { "<00>" in it && "UNIQUE" in it.uppercase(Locale.ROOT) }
        return line?.trim()?.split(Regex("\\s+"))?.firstOrNull() ?: hostName
    }
    return null
}

private fun runCommand(command: List<String>, timeoutSeconds: Long = 8): String? {
    return try {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return null
        }
        process.inputStream.bufferedReader().use { it.readText() }.trim()
    } catch (_: Exception) {
        null
    }
}

private fun dnAttribute(dn: String, attribute: String): String? {
    return try {
        LdapName(dn).rdns.firstOrNull { it.type.equals(attribute, ignoreCase = true) }?.value?.toString()
    } catch (_: Exception) {
        null
    }
}

private fun subjectAltNames(cert: X509Certificate): List<String> {
    val sans = cert.subjectAlternativeNames ?: return emptyList()
    return sans.mapNotNull { entry ->
        if (entry.size < 2) return@mapNotNull null
        val type = entry[0] as? Int ?: return@mapNotNull null
        val value = entry[1]?.toString() ?: return@mapNotNull null
        val label = when (type) {
            2 -> "DNS"
            7 -> "IP"
            1 -> "RFC822"
            6 -> "URI"
            8 -> "RID"
            4 -> "DirName"
            else -> "Type$type"
        }
        "$label:$value"
    }
}

private fun keyUsage(cert: X509Certificate): List<String> {
    val usage = cert.keyUsage ?: return emptyList()
    val names = listOf(
        "Digital Signature",
        "Content Commitment",
        "Key Encipherment",
        "Data Encipherment",
        "Key Agreement",
        "Key Cert Sign",
        "CRL Sign",
        "Encipher Only",
        "Decipher Only",
    )
    return usage.indices.mapNotNull { idx ->
        if (usage[idx]) names.getOrNull(idx) else null
    }
}

private fun extendedKeyUsage(cert: X509Certificate): List<String> {
    val eku = cert.extendedKeyUsage ?: return emptyList()
    val names = mapOf(
        "1.3.6.1.5.5.7.3.1" to "TLS Web Server Authentication",
        "1.3.6.1.5.5.7.3.2" to "TLS Web Client Authentication",
        "1.3.6.1.5.5.7.3.3" to "Code Signing",
        "1.3.6.1.5.5.7.3.4" to "Email Protection",
        "1.3.6.1.5.5.7.3.8" to "Time Stamping",
        "1.3.6.1.5.5.7.3.9" to "OCSP Signing",
    )
    return eku.map { names[it] ?: it }
}

private fun sha256Fingerprint(cert: X509Certificate): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
    return digest.joinToString(":") { "%02X".format(it) }
}

fun pingCheck(ctx: Context) {
    val host = ctx.pathParam("host")
    val count = ctx.queryParam("count")?.toIntOrNull() ?: 4
    try {
        val command = listOf("ping", "-c", "$count", "-W", "5", host)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().readLines()
        val exitCode = process.waitFor()

        val replies = mutableListOf<PingReply>()
        val replyPattern = ".*from ([^:]+):.*time=([0-9.]+).*".toRegex()
        for (l in lines) {
            val match = replyPattern.find(l)
            if (match != null) {
                replies.add(PingReply(from = match.groupValues[1], time = "${match.groupValues[2]}ms"))
            }
        }

        val statsLine = lines.find { it.contains("packets transmitted") }
        val rttLine = lines.find { it.contains("min/avg/max") }
        val received = "([0-9]+) received".toRegex().find(statsLine ?: "")?.groupValues?.get(1)
        val loss = "([0-9.]+)% packet loss".toRegex().find(statsLine ?: "")?.groupValues?.get(1)
        val rttMatch = "= ([0-9.]+)/([0-9.]+)/([0-9.]+)/([0-9.]+)".toRegex().find(rttLine ?: "")

        val response = PingResponse(
            host = host,
            packetsSent = count,
            packetsReceived = received,
            packetLoss = "${loss ?: "?"}%",
            minRtt = rttMatch?.groupValues?.get(1)?.plus("ms"),
            avgRtt = rttMatch?.groupValues?.get(2)?.plus("ms"),
            maxRtt = rttMatch?.groupValues?.get(3)?.plus("ms"),
            status = if (exitCode == 0) "Reachable" else "Unreachable",
            replies = replies,
        )
        ctx.jsonResult(response)
    } catch (e: Exception) {
        ctx.jsonResult(ErrorResponse("Ping failed: ${e.message}"))
    }
}

fun pingStream(ctx: Context) {
    val host = ctx.pathParam("host")
    val continuous = ctx.queryParam("continuous")?.lowercase() == "true"
    val count = if (continuous) null else (ctx.queryParam("count")?.toIntOrNull() ?: 4).coerceIn(1, 200)
    val timeoutSeconds = (ctx.queryParam("timeout")?.toIntOrNull() ?: 5).coerceIn(1, 30)
    val intervalMs = 1000L
    val isWindows = isWindowsHost()

    ctx.res().status = 200
    ctx.res().characterEncoding = "UTF-8"
    ctx.res().contentType = "text/event-stream"
    ctx.res().setHeader("Cache-Control", "no-cache")
    ctx.res().setHeader("Connection", "keep-alive")

    val writer = ctx.res().writer
    fun emit(event: String, payload: Any) {
        writer.write("event: $event\n")
        writer.write("data: ${jsonString(payload)}\n\n")
        writer.flush()
    }

    val replies = mutableListOf<PingReply>()
    val rtts = mutableListOf<Double>()
    var sent = 0
    var received = 0
    var completed = false

    try {
        emit(
            "start",
            mapOf(
                "host" to host,
                "continuous" to continuous,
                "count" to count,
                "timeoutSeconds" to timeoutSeconds,
                "intervalMs" to intervalMs,
            ),
        )

        while (continuous || sent < (count ?: 0)) {
            val probe = runSinglePingProbe(host, timeoutSeconds, isWindows)
            sent += 1
            if (probe.success) {
                received += 1
                probe.timeMs?.let { rtts.add(it) }
                replies.add(
                    PingReply(
                        from = probe.from ?: host,
                        time = probe.timeMs?.let { formatMs(it) } ?: "?",
                    )
                )
            }

            emit(
                "sample",
                mapOf(
                    "seq" to sent,
                    "from" to probe.from,
                    "timeMs" to probe.timeMs,
                    "status" to if (probe.success) "reply" else "timeout",
                    "message" to if (probe.success) {
                        "${probe.from ?: host} ${probe.timeMs?.let { formatMs(it) } ?: ""}".trim()
                    } else {
                        "No response within ${timeoutSeconds}s"
                    },
                ),
            )

            val moreProbes = continuous || sent < (count ?: 0)
            if (moreProbes) {
                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        completed = true
        val packetLoss = if (sent > 0) ((sent - received) * 100.0) / sent else 0.0
        val summary = PingResponse(
            host = host,
            packetsSent = sent,
            packetsReceived = received.toString(),
            packetLoss = "${"%.1f".format(packetLoss)}%",
            minRtt = rtts.minOrNull()?.let { formatMs(it) },
            avgRtt = if (rtts.isNotEmpty()) formatMs(rtts.average()) else null,
            maxRtt = rtts.maxOrNull()?.let { formatMs(it) },
            status = if (received > 0) "Reachable" else "Unreachable",
            replies = replies,
        )
        emit("summary", summary)
        emit("done", summary)
    } catch (e: Exception) {
        // Client disconnected (Stop clicked) or probe execution failed.
        if (sent == 0) {
            try {
                emit("error", mapOf("message" to ("Ping failed: ${e.message ?: "unknown error"}")))
            } catch (_: Exception) {
                // Connection already closed.
            }
            return
        }
        if (!completed) {
            val packetLoss = if (sent > 0) ((sent - received) * 100.0) / sent else 0.0
            val partial = PingResponse(
                host = host,
                packetsSent = sent,
                packetsReceived = received.toString(),
                packetLoss = "${"%.1f".format(packetLoss)}%",
                minRtt = rtts.minOrNull()?.let { formatMs(it) },
                avgRtt = if (rtts.isNotEmpty()) formatMs(rtts.average()) else null,
                maxRtt = rtts.maxOrNull()?.let { formatMs(it) },
                status = if (received > 0) "Reachable" else "Unreachable",
                replies = replies,
            )
            try {
                emit("done", partial)
            } catch (_: Exception) {
                // Connection already closed.
            }
        }
    } finally {
        writer.close()
    }
}

private fun runSinglePingProbe(host: String, timeoutSeconds: Int, isWindows: Boolean): PingProbeResult {
    val command = if (isWindows) {
        listOf("ping", "-n", "1", "-w", "${timeoutSeconds * 1000}", host)
    } else {
        listOf("ping", "-n", "-c", "1", "-W", "$timeoutSeconds", host)
    }
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val lines = process.inputStream.bufferedReader().readLines()
    process.waitFor()

    val regex = if (isWindows) {
        ".*Reply from ([^:]+):.*time[=<]([0-9.]+)ms.*".toRegex(RegexOption.IGNORE_CASE)
    } else {
        ".*from ([^: ]+):.*time[=<]?([0-9.]+)\\s*ms.*".toRegex(RegexOption.IGNORE_CASE)
    }
    val match = lines.firstNotNullOfOrNull { regex.find(it) }
    if (match != null) {
        return PingProbeResult(
            success = true,
            from = match.groupValues[1],
            timeMs = match.groupValues[2].toDoubleOrNull(),
            raw = lines.joinToString("\n"),
        )
    }
    return PingProbeResult(
        success = false,
        from = null,
        timeMs = null,
        raw = lines.joinToString("\n"),
    )
}

private fun formatMs(value: Double): String = "${"%.1f".format(value)}ms"

fun traceCheck(ctx: Context) {
    val host = ctx.pathParam("host")
    val maxHops = (ctx.queryParam("maxHops")?.toIntOrNull() ?: 15).coerceIn(1, 30)
    try {
        val lines = runTraceCommand(host, maxHops)
        val hops = parseTraceHopsFromOutput(lines)
        ctx.jsonResult(TraceResponse(host = host, maxHops = maxHops, hops = hops))
    } catch (e: Exception) {
        ctx.jsonResult(ErrorResponse("Traceroute failed: ${e.message}"))
    }
}

/**
 * Enhanced traceroute with ASN and geolocation enrichment.
 * For each hop, looks up:
 * - ASN via Team Cymru DNS (reversed-ip.origin.asn.cymru.com TXT)
 * - Geolocation via ip-api.com batch API (free, no key needed)
 * Returns enriched hop data suitable for map and diagram visualization.
 */
fun traceVisual(ctx: Context) {
    val host = ctx.pathParam("host")
    val maxHops = (ctx.queryParam("maxHops")?.toIntOrNull() ?: 15).coerceIn(1, 30)
    val lookupMode = parseLookupMode(ctx.queryParam("lookupMode"))
    val includePtr = ctx.queryParam("ptr")?.lowercase() != "false"
    try {
        // Step 1: Run traceroute
        val lines = runTraceCommand(host, maxHops)
        val rawHops = parseTraceHopsFromOutput(lines)
        ctx.jsonResult(buildTraceVisualResponse(host, maxHops, rawHops, lookupMode, includePtr))
    } catch (e: Exception) {
        ctx.jsonResult(ErrorResponse("Traceroute failed: ${e.message}"))
    }
}

fun traceVisualStream(ctx: Context) {
    val host = ctx.pathParam("host")
    val maxHops = (ctx.queryParam("maxHops")?.toIntOrNull() ?: 15).coerceIn(1, 30)
    val lookupMode = parseLookupMode(ctx.queryParam("lookupMode"))
    val includePtr = ctx.queryParam("ptr")?.lowercase() != "false"

    ctx.res().status = 200
    ctx.res().characterEncoding = "UTF-8"
    ctx.res().contentType = "text/event-stream"
    ctx.res().setHeader("Cache-Control", "no-cache")
    ctx.res().setHeader("Connection", "keep-alive")

    val writer = ctx.res().writer
    fun emit(event: String, payload: Any) {
        writer.write("event: $event\n")
        writer.write("data: ${jsonString(payload)}\n\n")
        writer.flush()
    }

    try {
        emit("start", mapOf("host" to host, "maxHops" to maxHops, "lookupMode" to lookupMode, "ptr" to includePtr))

        val isWindows = isWindowsHost()
        val command = if (isWindows) {
            listOf("tracert", "-d", "-h", "$maxHops", "-w", "700", host)
        } else {
            listOf("traceroute", "-n", "-q", "1", "-m", "$maxHops", "-w", "1", host)
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val rawHops = mutableListOf<TraceHop>()
        val ptrResolver = DnsResolver(timeout = Duration.ofMillis(300))
        val ptrCache = mutableMapOf<String, String?>()

        process.inputStream.bufferedReader().useLines { seq ->
            seq.forEach { line ->
                val hop = parseTraceHopLine(line, isWindows) ?: return@forEach
                rawHops.add(hop)
                val ptr = if (includePtr) resolvePtrName(hop.ip, ptrResolver, ptrCache) else null
                val visualHop = TraceVisualHop(
                    hop = hop.hop.toIntOrNull() ?: 0,
                    host = hop.host,
                    ip = hop.ip,
                    rttRaw = hop.rtt,
                    rttAvg = parseAverageRttMs(hop.rtt),
                    ptr = ptr,
                )
                emit("hop", visualHop)
            }
        }
        process.waitFor()

        val finalResponse = buildTraceVisualResponse(host, maxHops, rawHops, lookupMode, includePtr)
        emit("done", finalResponse)
    } catch (e: Exception) {
        emit("error", mapOf("message" to ("Traceroute failed: ${e.message}")))
    } finally {
        writer.close()
    }
}

private fun buildTraceVisualResponse(
    host: String,
    maxHops: Int,
    rawHops: List<TraceHop>,
    lookupMode: String,
    includePtr: Boolean,
): TraceVisualResponse {
    // Step 2: Parse RTT values (extract numeric ms values)
    // Step 3: Collect unique IPs for enrichment
    val validIps = rawHops.map { it.ip }.filter { it != "*" && it.isNotEmpty() && it != "127.0.0.1" }.distinct()
    val enrichIps = validIps.take(12)

    // Step 4: ASN lookup via Team Cymru DNS (batch via individual TXT queries)
    val resolver = DnsResolver(timeout = Duration.ofSeconds(1))
    val asnMap = mutableMapOf<String, Map<String, String>>()
    val asNameCache = mutableMapOf<String, String>()
    val ptrCache = mutableMapOf<String, String?>()
    for (ip in enrichIps) {
        try {
            val reversed = ip.split(".").reversed().joinToString(".")
            val dnsQuery = "$reversed.origin.asn.cymru.com"
            val result = resolver.lookup(dnsQuery, Type.TXT)
            if (result.isSuccessful && result.records.isNotEmpty()) {
                val txt = result.records.first().data
                val parts = txt.split("|").map { it.trim() }
                if (parts.size >= 5) {
                    val asn = parts[0].split(" ").firstOrNull().orEmpty()
                    if (asn.isBlank()) continue
                    val asName = lookupAsName(asn, resolver, asNameCache, parts.getOrNull(5) ?: "")
                    asnMap[ip] = mapOf(
                        "asn" to "AS$asn",
                        "prefix" to parts[1],
                        "country" to parts[2],
                        "registry" to parts[3],
                        "asName" to asName,
                    )
                }
            }
        } catch (_: Exception) {
        }
    }
    // Step 5: Geolocation via ip-api.com batch API (geo mode only, rate-limited)
    val geoMap = mutableMapOf<String, Map<String, Any?>>()
    if (lookupMode == "geo" && validIps.isNotEmpty()) {
        try {
            val batchBody = jsonString(validIps.map { mapOf("query" to it) })
            val fields = "query,status,country,countryCode,regionName,city,lat,lon,isp,org"
            val uri = URI.create("http://ip-api.com/batch?fields=$fields")
            val request = HttpRequest.newBuilder()
                .uri(uri).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(batchBody)).build()
            val client = JHttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val arr = jsonReadTree(response.body())
                if (arr.isArray) {
                    arr.forEach { node ->
                        if (node.get("status")?.asText() == "success") {
                            val q = node.get("query")?.asText() ?: return@forEach
                            geoMap[q] = mapOf(
                                "country" to node.get("country")?.asText(),
                                "countryCode" to node.get("countryCode")?.asText(),
                                "region" to node.get("regionName")?.asText(),
                                "city" to node.get("city")?.asText(),
                                "lat" to node.get("lat")?.asDouble(),
                                "lon" to node.get("lon")?.asDouble(),
                                "isp" to node.get("isp")?.asText(),
                                "org" to node.get("org")?.asText(),
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Geo lookup is best-effort
        }
    }

    // Step 6: Build enriched response
    val enrichedHops = rawHops.map { hop ->
        val rttAvg = parseAverageRttMs(hop.rtt)
        val asn = asnMap[hop.ip]
        val geo = geoMap[hop.ip]
        val asnCountry = asn?.get("country")
        val geoCountryCode = if (lookupMode == "geo") geo?.get("countryCode") as? String else null
        val geoCountry = if (lookupMode == "geo") geo?.get("country") as? String else null
        val countryCode = if (lookupMode == "asn-country") asnCountry else geoCountryCode
        val ptr = if (includePtr) resolvePtrName(hop.ip, resolver, ptrCache) else null
        TraceVisualHop(
            hop = hop.hop.toIntOrNull() ?: 0,
            host = hop.host,
            ip = hop.ip,
            rttRaw = hop.rtt,
            rttAvg = rttAvg,
            ptr = ptr,
            asn = asn?.get("asn"),
            asName = asn?.get("asName"),
            prefix = asn?.get("prefix"),
            asnCountry = asnCountry,
            lat = if (lookupMode == "geo") geo?.get("lat") as? Double else null,
            lon = if (lookupMode == "geo") geo?.get("lon") as? Double else null,
            city = if (lookupMode == "geo") geo?.get("city") as? String else null,
            region = if (lookupMode == "geo") geo?.get("region") as? String else null,
            country = if (lookupMode == "geo") geoCountry else countryCode,
            countryCode = countryCode,
            isp = if (lookupMode == "geo") geo?.get("isp") as? String else null,
            org = if (lookupMode == "geo") geo?.get("org") as? String else null,
        )
    }

    return TraceVisualResponse(
        host = host,
        maxHops = maxHops,
        hopCount = rawHops.size,
        hops = enrichedHops,
    )
}

private fun parseLookupMode(value: String?): String {
    return when (value?.lowercase()) {
        "asn-country" -> "asn-country"
        else -> "geo"
    }
}

private fun lookupAsName(
    asn: String,
    resolver: DnsResolver,
    cache: MutableMap<String, String>,
    fallback: String = "",
): String {
    if (asn.isBlank()) return fallback
    cache[asn]?.let { return it }
    val value = try {
        val result = resolver.lookup("AS$asn.asn.cymru.com", Type.TXT)
        if (result.isSuccessful && result.records.isNotEmpty()) {
            val parts = result.records.first().data.split("|").map { it.trim() }
            parts.getOrNull(4)?.takeIf { it.isNotBlank() } ?: fallback
        } else {
            fallback
        }
    } catch (_: Exception) {
        fallback
    }
    cache[asn] = value
    return value
}

private fun resolvePtrName(
    ip: String,
    resolver: DnsResolver,
    cache: MutableMap<String, String?>,
): String? {
    if (ip.isBlank() || ip == "*" || ip == "127.0.0.1") return null
    if (cache.containsKey(ip)) return cache[ip]
    val value = try {
        val result = resolver.reverseLookup(ip)
        if (result.isSuccessful && result.records.isNotEmpty()) {
            result.records.first().data.trimEnd('.').ifBlank { null }
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
    cache[ip] = value
    return value
}

private fun isWindowsHost(): Boolean =
    System.getProperty("os.name").lowercase().contains("win")

private fun runTraceCommand(host: String, maxHops: Int): List<String> {
    val command = if (isWindowsHost()) {
        // -d skips reverse DNS lookups, which dramatically improves response time.
        listOf("tracert", "-d", "-h", "$maxHops", "-w", "700", host)
    } else {
        // -n avoids reverse lookups, -q 1 sends one probe per hop for faster API responses.
        listOf("traceroute", "-n", "-q", "1", "-m", "$maxHops", "-w", "1", host)
    }
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val lines = process.inputStream.bufferedReader().readLines()
    process.waitFor()
    return lines
}

fun parseTraceHopsFromOutput(lines: List<String>, isWindows: Boolean = isWindowsHost()): List<TraceHop> {
    val hops = mutableListOf<TraceHop>()
    for (line in lines) {
        val parsed = parseTraceHopLine(line, isWindows) ?: continue
        hops.add(parsed)
    }
    return hops
}

fun parseTraceHopLine(line: String, isWindows: Boolean = isWindowsHost()): TraceHop? {
    val hopPattern = "^\\s*(\\d+)\\s+(.+)$".toRegex()
    val ipRegex = "(\\d{1,3}(?:\\.\\d{1,3}){3})".toRegex()
    val msRegex = "<?\\d+\\s*ms".toRegex()

    val match = hopPattern.find(line) ?: return null
    val hopNum = match.groupValues[1]
    val rest = match.groupValues[2].trim()

    if (rest.contains("Request timed out", ignoreCase = true) || rest.contains("* * *")) {
        return TraceHop(hopNum, "*", "*", "* * *")
    }

    if (isWindows) {
        val ip = ipRegex.find(rest)?.groupValues?.get(1) ?: ""
        val rtts = msRegex.findAll(rest).map { it.value.trim() }.toList()
        val rttRaw = if (rtts.isNotEmpty()) rtts.joinToString(" ") else ""
        val hostPart = rest.replaceFirst("^(?:<?\\d+\\s*ms\\s+){1,3}".toRegex(), "").trim()
        val host = if (ip.isNotEmpty()) {
            val bracketed = "\\[(.*?)\\]".toRegex().find(hostPart)?.groupValues?.get(1)
            if (!bracketed.isNullOrBlank()) {
                hostPart.substringBefore("[").trim().ifBlank { ip }
            } else {
                hostPart.ifBlank { ip }
            }
        } else {
            hostPart.ifBlank { rest }
        }
        return TraceHop(hopNum, host, ip, rttRaw)
    }

    val hostMatch = "^([^(]+)\\(([^)]+)\\)(.*)$".toRegex().find(rest)
    return if (hostMatch != null) {
        TraceHop(
            hopNum,
            hostMatch.groupValues[1].trim(),
            hostMatch.groupValues[2].trim(),
            hostMatch.groupValues[3].trim(),
        )
    } else {
        val ip = ipRegex.find(rest)?.groupValues?.get(1) ?: ""
        if (ip.isNotEmpty()) {
            val rtt = rest.removePrefix(ip).trim().ifBlank { rest }
            TraceHop(hopNum, ip, ip, rtt)
        } else {
            TraceHop(hopNum, rest, ip, rest)
        }
    }
}

fun parseAverageRttMs(raw: String): Double? {
    val times = "<?([0-9]+\\.?[0-9]*)\\s*ms".toRegex()
        .findAll(raw)
        .map { it.groupValues[1].toDouble() }
        .toList()
    return if (times.isNotEmpty()) times.average() else null
}

// ─── Models ─────────────────────────────────────────────────────────────────

data class HttpCheckResponse(
    val url: String,
    val statusCode: Int,
    val responseTimeMs: Long,
    val error: String?,
    val headers: Map<String, String>,
)

data class SslInfo(
    val protocol: String,
    val cipherSuite: String,
)

data class HttpsCheckResponse(
    val url: String,
    val statusCode: Int,
    val responseTimeMs: Long,
    val error: String?,
    val ssl: SslInfo?,
    val certificateChain: List<CertificateInfo>?,
    val certificateError: String?,
    val headers: Map<String, String>,
)

data class CertificateInfo(
    val index: Int,
    val subject: String,
    val issuer: String,
    val commonName: String?,
    val issuerCommonName: String?,
    val subjectAltNames: List<String>,
    val validFrom: String,
    val validUntil: String,
    val validFromEpochMs: Long,
    val validUntilEpochMs: Long,
    val daysRemaining: Long,
    val expired: Boolean,
    val serialNumber: String,
    val signatureAlgorithm: String,
    val publicKeyType: String,
    val publicKeySize: Int?,
    val isCA: Boolean,
    val keyUsage: List<String>,
    val extendedKeyUsage: List<String>,
    val sha256Fingerprint: String,
    val selfSigned: Boolean,
)

data class PingReply(
    val from: String,
    val time: String,
)

data class PingStreamSample(
    val seq: Int,
    val from: String?,
    val timeMs: Double?,
    val status: String,
    val message: String,
)

private data class PingProbeResult(
    val success: Boolean,
    val from: String?,
    val timeMs: Double?,
    val raw: String,
)

data class PingResponse(
    val host: String,
    val packetsSent: Int,
    val packetsReceived: String?,
    val packetLoss: String,
    val minRtt: String?,
    val avgRtt: String?,
    val maxRtt: String?,
    val status: String,
    val replies: List<PingReply>,
)

data class TraceHop(
    val hop: String,
    val host: String,
    val ip: String,
    val rtt: String,
)

data class TraceResponse(
    val host: String,
    val maxHops: Int,
    val hops: List<TraceHop>,
)

data class TraceVisualHop(
    val hop: Int,
    val host: String,
    val ip: String,
    val rttRaw: String,
    val rttAvg: Double?,
    val ptr: String? = null,
    val asn: String? = null,
    val asName: String? = null,
    val prefix: String? = null,
    val asnCountry: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val city: String? = null,
    val region: String? = null,
    val country: String? = null,
    val countryCode: String? = null,
    val isp: String? = null,
    val org: String? = null,
)

data class TraceVisualResponse(
    val host: String,
    val maxHops: Int,
    val hopCount: Int,
    val hops: List<TraceVisualHop>,
)

data class HostIdentity(
    val hostname: String,
    val netbios: String? = null,
    val osName: String,
    val osVersion: String,
    val osArch: String,
    val platform: String,
)

data class LocalAddress(
    val ip: String,
    val family: String,
    val prefixLength: Int? = null,
)

data class LocalInterfaceInfo(
    val name: String,
    val displayName: String,
    val up: Boolean,
    val loopback: Boolean,
    val virtual: Boolean,
    val mtu: Int? = null,
    val macAddress: String? = null,
    val addresses: List<LocalAddress>,
    val dhcp: String,
    val dnsServers: List<String>,
)

data class RouteEntry(
    val destination: String,
    val gateway: String? = null,
    val interfaceName: String? = null,
    val metric: Int? = null,
    val raw: String,
)

data class LocalNetworkSnapshot(
    val generatedAt: String,
    val host: HostIdentity,
    val interfaces: List<LocalInterfaceInfo>,
    val routes: List<RouteEntry>,
    val routingTableRaw: String? = null,
    val defaultDnsServers: List<String>,
    val interfaceDnsServers: Map<String, List<String>>,
    val notes: List<String>,
)

private data class DnsInfoResult(
    val defaultDns: List<String>,
    val interfaceDns: Map<String, List<String>>,
    val notes: List<String>,
)

private data class RouteTableResult(
    val routes: List<RouteEntry>,
    val raw: String?,
    val notes: List<String>,
)
