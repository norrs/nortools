package no.norrs.nortools.web

import io.javalin.http.Context
import no.norrs.nortools.lib.dns.DnsResolver
import no.norrs.nortools.lib.network.HttpClient
import no.norrs.nortools.lib.network.TcpClient
import org.xbill.DNS.Type
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

fun traceCheck(ctx: Context) {
    val host = ctx.pathParam("host")
    val maxHops = ctx.queryParam("maxHops")?.toIntOrNull() ?: 30
    try {
        val command = listOf("traceroute", "-m", "$maxHops", "-w", "3", host)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor()

        val hops = mutableListOf<TraceHop>()
        val hopPattern = "^\\s*([0-9]+)\\s+(.+)$".toRegex()
        for (line in lines.drop(1)) {
            val match = hopPattern.find(line)
            if (match != null) {
                val hopNum = match.groupValues[1]
                val rest = match.groupValues[2].trim()
                if (rest.contains("* * *")) {
                    hops.add(TraceHop(hop = hopNum, host = "*", ip = "*", rtt = "* * *"))
                } else {
                    val hostMatch = "^([^(]+)\\(([^)]+)\\)(.*)$".toRegex().find(rest)
                    if (hostMatch != null) {
                        hops.add(
                            TraceHop(
                                hop = hopNum,
                                host = hostMatch.groupValues[1].trim(),
                                ip = hostMatch.groupValues[2].trim(),
                                rtt = hostMatch.groupValues[3].trim(),
                            )
                        )
                    } else {
                        hops.add(TraceHop(hop = hopNum, host = rest, ip = "", rtt = ""))
                    }
                }
            }
        }
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
    val maxHops = ctx.queryParam("maxHops")?.toIntOrNull() ?: 30
    val includeGeo = ctx.queryParam("geo")?.lowercase() == "true"
    try {
        // Step 1: Run traceroute
        val command = listOf("traceroute", "-m", "$maxHops", "-w", "3", host)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor()

        data class RawHop(val hop: Int, val host: String, val ip: String, val rttRaw: String)

        val rawHops = mutableListOf<RawHop>()
        val hopPattern = "^\\s*([0-9]+)\\s+(.+)$".toRegex()
        for (line in lines.drop(1)) {
            val match = hopPattern.find(line) ?: continue
            val hopNum = match.groupValues[1].toInt()
            val rest = match.groupValues[2].trim()
            if (rest.contains("* * *")) {
                rawHops.add(RawHop(hopNum, "*", "*", "* * *"))
            } else {
                val hostMatch = "^([^(]+)\\(([^)]+)\\)(.*)$".toRegex().find(rest)
                if (hostMatch != null) {
                    rawHops.add(
                        RawHop(
                            hopNum,
                            hostMatch.groupValues[1].trim(),
                            hostMatch.groupValues[2].trim(),
                            hostMatch.groupValues[3].trim(),
                        )
                    )
                } else {
                    rawHops.add(RawHop(hopNum, rest, "", ""))
                }
            }
        }

        // Step 2: Parse RTT values (extract numeric ms values)
        fun parseRtt(raw: String): Double? {
            val times = "([0-9]+\\.?[0-9]*)\\s*ms".toRegex().findAll(raw).map { it.groupValues[1].toDouble() }.toList()
            return if (times.isNotEmpty()) times.average() else null
        }

        // Step 3: Collect unique IPs for enrichment
        val validIps = rawHops.map { it.ip }.filter { it != "*" && it.isNotEmpty() && it != "127.0.0.1" }.distinct()

        // Step 4: ASN lookup via Team Cymru DNS (batch via individual TXT queries)
        val resolver = DnsResolver(timeout = Duration.ofSeconds(2))
        val asnMap = mutableMapOf<String, Map<String, String>>()
        for (ip in validIps) {
            try {
                val reversed = ip.split(".").reversed().joinToString(".")
                val dnsQuery = "$reversed.origin.asn.cymru.com"
                val result = resolver.lookup(dnsQuery, Type.TXT)
                if (result.isSuccessful && result.records.isNotEmpty()) {
                    val txt = result.records.first().data
                    val parts = txt.split("|").map { it.trim() }
                    if (parts.size >= 5) {
                        val asn = parts[0]
                        // Also get AS name
                        var asName = ""
                        try {
                            val nameResult = resolver.lookup("AS$asn.asn.cymru.com", Type.TXT)
                            if (nameResult.isSuccessful && nameResult.records.isNotEmpty()) {
                                val nameParts = nameResult.records.first().data.split("|").map { it.trim() }
                                if (nameParts.size >= 5) asName = nameParts[4]
                            }
                        } catch (_: Exception) {
                        }
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

        // Step 5: Geolocation via ip-api.com batch API (opt-in, rate-limited)
        val geoMap = mutableMapOf<String, Map<String, Any?>>()
        if (includeGeo && validIps.isNotEmpty()) {
            try {
                val batchBody = jsonString(validIps.map { mapOf("query" to it) })
                val uri = URI.create("http://ip-api.com/batch?fields=query,status,country,countryCode,regionName,city,lat,lon,isp,org")
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
            val rttAvg = parseRtt(hop.rttRaw)
            val asn = asnMap[hop.ip]
            val geo = geoMap[hop.ip]
            TraceVisualHop(
                hop = hop.hop,
                host = hop.host,
                ip = hop.ip,
                rttRaw = hop.rttRaw,
                rttAvg = rttAvg,
                asn = asn?.get("asn"),
                asName = asn?.get("asName"),
                prefix = asn?.get("prefix"),
                asnCountry = asn?.get("country"),
                lat = geo?.get("lat") as? Double,
                lon = geo?.get("lon") as? Double,
                city = geo?.get("city") as? String,
                region = geo?.get("region") as? String,
                country = geo?.get("country") as? String,
                countryCode = geo?.get("countryCode") as? String,
                isp = geo?.get("isp") as? String,
                org = geo?.get("org") as? String,
            )
        }

        ctx.jsonResult(
            TraceVisualResponse(
                host = host,
                maxHops = maxHops,
                hopCount = rawHops.size,
                hops = enrichedHops,
            )
        )
    } catch (e: Exception) {
        ctx.jsonResult(ErrorResponse("Traceroute failed: ${e.message}"))
    }
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
