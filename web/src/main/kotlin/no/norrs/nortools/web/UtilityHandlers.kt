package no.norrs.nortools.web

import io.javalin.http.Context
import no.norrs.nortools.lib.dns.DnsResolver
import no.norrs.nortools.lib.network.HttpClient
import org.xbill.DNS.Type
import java.net.Inet6Address
import java.net.InetAddress
import java.security.SecureRandom
import java.time.Duration
import kotlin.math.log2

// ─── Utility Tools ───────────────────────────────────────────────────────────

private const val OPENDNS_PROVIDER = "dns:opendns"
private val HTTP_IP_PROVIDERS = linkedMapOf(
    "https://checkip.dns.he.net/" to "checkip.dns.he.net",
    "https://ifconfig.me/ip" to "ifconfig.me",
    "https://icanhazip.com" to "icanhazip.com",
    "https://api.ipify.org" to "ipify.org",
)
private val IPV4_REGEX = Regex("""\b(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\b""")
private val HEX_COLON_TOKEN_REGEX = Regex("""\b[0-9A-Fa-f:%.]+\b""")

fun whatIsMyIp(ctx: Context) {
    val selectedProvider = ctx.queryParam("provider")?.trim()?.ifEmpty { null }
    val details = linkedMapOf<String, String>()
    val httpClient = HttpClient(timeout = Duration.ofSeconds(5))
    if (selectedProvider != null) {
        when {
            selectedProvider == OPENDNS_PROVIDER -> {
                details["opendns"] = resolveOpenDnsIp()
            }
            HTTP_IP_PROVIDERS.containsKey(selectedProvider) -> {
                val name = HTTP_IP_PROVIDERS.getValue(selectedProvider)
                details[name] = fetchHttpIp(httpClient, selectedProvider)
            }
            else -> {
                ctx.jsonResult(ErrorResponse("Unsupported provider: $selectedProvider"))
                return
            }
        }
    } else {
        details["opendns"] = resolveOpenDnsIp()
        for ((url, name) in HTTP_IP_PROVIDERS) {
            details[name] = fetchHttpIp(httpClient, url)
        }
    }
    ctx.jsonResult(details)
}

private fun resolveOpenDnsIp(): String = try {
    val resolver = DnsResolver()
    val result = resolver.lookup("myip.opendns.com", Type.A)
    if (result.isSuccessful && result.records.isNotEmpty()) result.records.first().data else "Failed"
} catch (_: Exception) {
    "Failed"
}

private fun fetchHttpIp(httpClient: HttpClient, url: String): String = try {
    val result = httpClient.get(url, includeBody = true)
    val body = result.body
    if (result.statusCode == 200 && body != null) {
        extractIpFromBody(body) ?: "No IP found"
    } else {
        "HTTP ${result.statusCode}"
    }
} catch (e: Exception) {
    "Failed: ${e.message}"
}

private fun extractIpFromBody(body: String): String? {
    IPV4_REGEX.find(body)?.value?.let { return it }

    val candidates = HEX_COLON_TOKEN_REGEX.findAll(body).map { it.value.trim() }
    for (candidate in candidates) {
        val raw = candidate.trim('.', ',', ';', ')', '(', '[', ']', '<', '>', '"', '\'')
        if (raw.count { it == ':' } < 2) continue
        val sanitized = raw.substringBefore('%')
        if (sanitized.isEmpty()) continue
        val parsed = try {
            InetAddress.getByName(sanitized)
        } catch (_: Exception) {
            null
        }
        if (parsed is Inet6Address) return sanitized
    }
    return null
}

fun subnetCalc(ctx: Context) {
    val cidr = ctx.pathParam("cidr")
    val parts = cidr.split("/")
    if (parts.size != 2) {
        ctx.jsonResult(ErrorResponse("Invalid CIDR notation"))
        return
    }
    val ipStr = parts[0]
    val prefix = parts[1].toIntOrNull()
    if (prefix == null || prefix < 0 || prefix > 32) {
        ctx.jsonResult(ErrorResponse("Invalid prefix length"))
        return
    }
    val addr = InetAddress.getByName(ipStr)
    val addrBytes = addr.address
    val ipInt = addrBytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
    val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
    val network = ipInt and mask
    val broadcast = network or mask.inv() and 0xFFFFFFFFL
    val firstHost = if (prefix < 31) network + 1 else network
    val lastHost = if (prefix < 31) broadcast - 1 else broadcast
    val totalHosts = if (prefix <= 30) (1L shl (32 - prefix)) - 2 else if (prefix == 31) 2L else 1L

    fun longToIpv4(v: Long) = "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"

    val response = SubnetCalcResponse(
        cidr = "$ipStr/$prefix",
        ip = ipStr,
        networkAddress = longToIpv4(network),
        broadcastAddress = longToIpv4(broadcast),
        subnetMask = longToIpv4(mask),
        wildcardMask = longToIpv4(mask.inv() and 0xFFFFFFFFL),
        firstHost = longToIpv4(firstHost),
        lastHost = longToIpv4(lastHost),
        totalHosts = totalHosts,
        prefixLength = "/$prefix",
    )
    ctx.jsonResult(response)
}

fun passwordGen(ctx: Context) {
    val length = ctx.queryParam("length")?.toIntOrNull() ?: 16
    val count = ctx.queryParam("count")?.toIntOrNull() ?: 5
    val upper = ctx.queryParam("upper") != "false"
    val lower = ctx.queryParam("lower") != "false"
    val digits = ctx.queryParam("digits") != "false"
    val special = ctx.queryParam("special") != "false"

    var charset = ""
    if (upper) charset += "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    if (lower) charset += "abcdefghijklmnopqrstuvwxyz"
    if (digits) charset += "0123456789"
    if (special) charset += "!@#$%^&*()-_=+[]{}|;:,.<>?"
    if (charset.isEmpty()) charset = "abcdefghijklmnopqrstuvwxyz0123456789"

    val random = SecureRandom()
    val passwords = (1..count).map {
        (1..length).map { charset[random.nextInt(charset.length)] }.joinToString("")
    }
    val entropy = length * log2(charset.length.toDouble())

    val response = PasswordGenResponse(
        passwords = passwords,
        length = length,
        count = count,
        charsetSize = charset.length,
        entropy = "%.1f bits".format(entropy),
        options = PasswordOptions(upper = upper, lower = lower, digits = digits, special = special),
    )
    ctx.jsonResult(response)
}

fun emailExtract(ctx: Context) {
    val text = ctx.body()
    val emailPattern = "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}".toRegex()
    val emails = emailPattern.findAll(text).map { it.value }.toList().distinct()
    val domainCounts = emails.groupBy { it.substringAfter("@").lowercase() }
        .mapValues { it.value.size }
        .toList().sortedByDescending { it.second }
        .associate { it.first to it.second }

    val response = EmailExtractResponse(
        totalFound = emails.size,
        emails = emails,
        uniqueDomains = domainCounts.size,
        domainBreakdown = domainCounts,
    )
    ctx.jsonResult(response)
}

// ─── Models ─────────────────────────────────────────────────────────────────

data class SubnetCalcResponse(
    val cidr: String,
    val ip: String,
    val networkAddress: String,
    val broadcastAddress: String,
    val subnetMask: String,
    val wildcardMask: String,
    val firstHost: String,
    val lastHost: String,
    val totalHosts: Long,
    val prefixLength: String,
)

data class PasswordOptions(
    val upper: Boolean,
    val lower: Boolean,
    val digits: Boolean,
    val special: Boolean,
)

data class PasswordGenResponse(
    val passwords: List<String>,
    val length: Int,
    val count: Int,
    val charsetSize: Int,
    val entropy: String,
    val options: PasswordOptions,
)

data class EmailExtractResponse(
    val totalFound: Int,
    val emails: List<String>,
    val uniqueDomains: Int,
    val domainBreakdown: Map<String, Int>,
)
