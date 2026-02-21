package no.norrs.nortools.web

import io.javalin.http.Context
import no.norrs.nortools.lib.dns.DnsResolver
import no.norrs.nortools.tools.whois.asn.RouteValidationVrpObjects
import no.norrs.nortools.tools.whois.asn.RoutinatorRouteValidator
import org.xbill.DNS.Type
import java.net.Inet6Address
import java.net.InetAddress

fun rpkiRouteValidation(ctx: Context) {
    val input = ctx.pathParam("ip").trim()
    val manualAsn = ctx.queryParam("asn")?.trim()?.ifBlank { null }
    if (manualAsn != null) {
        val manualTarget = parseManualRpkiTarget(input = input, asnInput = manualAsn)
        if (manualTarget == null) {
            ctx.jsonResult(
                RpkiRouteValidationResponse(
                    input = input,
                    inputType = "manual",
                    ip = "n/a",
                    asn = null,
                    prefix = null,
                    validationState = "UNAVAILABLE",
                    validationSource = "routinator",
                    validationReason = "invalid-input",
                    validationDetails = "Manual mode requires IPv4/IPv6 address or CIDR plus numeric ASN (e.g. AS13335)",
                    validationVrpObjects = null,
                )
            )
            return
        }
        val validation = RoutinatorRouteValidator().validate(prefix = manualTarget.prefix, asn = manualTarget.asn)
        ctx.jsonResult(
            RpkiRouteValidationResponse(
                input = input,
                inputType = "manual",
                ip = manualTarget.ip,
                asn = "AS${manualTarget.asn}",
                prefix = manualTarget.prefix,
                validationState = validation.state,
                validationSource = validation.source,
                validationReason = validation.reason,
                validationDetails = mergeValidationDetails(validation.details, manualTarget.note),
                validationVrpObjects = validation.vrpObjects,
            )
        )
        return
    }

    val resolver = DnsResolver()
    val resolution = resolveRpkiTarget(input, resolver)
    val resolvedTarget = resolution.target
    if (resolvedTarget == null) {
        ctx.jsonResult(ErrorResponse(resolution.error ?: "Invalid input"))
        return
    }
    val ip = resolvedTarget.ip

    val dnsQuery = buildCymruOriginQuery(ip)
    if (dnsQuery == null) {
        ctx.jsonResult(ErrorResponse("Unsupported IP format for RPKI route validation: $ip"))
        return
    }

    val result = resolver.lookup(dnsQuery, Type.TXT)
    if (!result.isSuccessful || result.records.isEmpty()) {
        ctx.jsonResult(
            RpkiRouteValidationResponse(
                input = input,
                inputType = resolvedTarget.inputType,
                ip = ip,
                asn = null,
                prefix = null,
                validationState = "UNAVAILABLE",
                validationSource = "routinator",
                validationReason = "asn-lookup-failed",
                validationDetails = "Could not resolve ASN/prefix via Team Cymru for $ip",
                validationVrpObjects = null,
            )
        )
        return
    }

    val parsed = parseCymruOrigin(result.records.first().data)
    if (parsed == null) {
        ctx.jsonResult(
            RpkiRouteValidationResponse(
                input = input,
                inputType = resolvedTarget.inputType,
                ip = ip,
                asn = null,
                prefix = null,
                validationState = "UNAVAILABLE",
                validationSource = "routinator",
                validationReason = "asn-parse-failed",
                validationDetails = "Could not parse Team Cymru response",
                validationVrpObjects = null,
            )
        )
        return
    }

    val (asn, prefix) = parsed
    val validation = RoutinatorRouteValidator().validate(prefix = prefix, asn = asn)
    ctx.jsonResult(
        RpkiRouteValidationResponse(
            input = input,
            inputType = resolvedTarget.inputType,
            ip = ip,
            asn = "AS$asn",
            prefix = prefix,
            validationState = validation.state,
            validationSource = validation.source,
            validationReason = validation.reason,
            validationDetails = validation.details,
            validationVrpObjects = validation.vrpObjects,
        )
    )
}

private fun parseCymruOrigin(txt: String): Pair<String, String>? {
    val parts = txt.split("|").map { it.trim() }
    if (parts.size < 2) return null
    val asn = parts[0]
    val prefix = parts[1]
    if (asn.isBlank() || prefix.isBlank()) return null
    return asn to prefix
}

private data class ManualRpkiTarget(
    val asn: String,
    val prefix: String,
    val ip: String,
    val note: String? = null,
)

private data class PrefixNormalizationResult(
    val prefix: String,
    val note: String? = null,
)

private fun parseManualRpkiTarget(input: String, asnInput: String): ManualRpkiTarget? {
    val asn = asnInput.trim().removePrefix("AS").removePrefix("as")
    if (asn.isBlank() || !asn.all { it.isDigit() }) return null

    val routeInput = input.trim()
    if (routeInput.isBlank()) return null

    if (isIpv4(routeInput)) {
        return ManualRpkiTarget(
            asn = asn,
            prefix = "$routeInput/32",
            ip = routeInput,
            note = "Manual IPv4 input validated as $routeInput/32",
        )
    }

    if (isIpv6(routeInput)) {
        return ManualRpkiTarget(
            asn = asn,
            prefix = "$routeInput/128",
            ip = routeInput,
            note = "Manual IPv6 input validated as $routeInput/128",
        )
    }

    val normalized = normalizeIpPrefix(routeInput) ?: return null
    val ip = normalized.prefix.substringBefore("/")
    return ManualRpkiTarget(asn = asn, prefix = normalized.prefix, ip = ip, note = normalized.note)
}

private fun normalizeIpPrefix(value: String): PrefixNormalizationResult? {
    val parts = value.split("/", limit = 2)
    if (parts.size != 2) return null
    val ip = parts[0].trim()
    val length = parts[1].trim().toIntOrNull() ?: return null

    if (isIpv4(ip) && length in 0..32) {
        val normalizedIp = normalizeIpv4Network(ip, length) ?: return null
        val normalizedPrefix = "$normalizedIp/$length"
        val note = if (normalizedIp != ip) "Normalized prefix to $normalizedPrefix (host bits cleared)" else null
        return PrefixNormalizationResult(prefix = normalizedPrefix, note = note)
    }
    if (isIpv6(ip) && length in 0..128) {
        val normalizedIp = normalizeIpv6Network(ip, length) ?: return null
        val normalizedPrefix = "$normalizedIp/$length"
        val note = if (!ipv6TextEquals(normalizedIp, ip)) "Normalized prefix to $normalizedPrefix (host bits cleared)" else null
        return PrefixNormalizationResult(prefix = normalizedPrefix, note = note)
    }
    return null
}

private fun normalizeIpv4Network(ip: String, length: Int): String? {
    val octets = ip.split(".")
    if (octets.size != 4) return null
    val values = octets.map { it.toIntOrNull() ?: return null }
    if (values.any { it !in 0..255 }) return null

    val address = values.fold(0L) { acc, part -> (acc shl 8) or part.toLong() }
    val mask = if (length == 0) 0L else (0xffffffffL shl (32 - length)) and 0xffffffffL
    val network = address and mask
    return listOf(
        (network shr 24) and 0xff,
        (network shr 16) and 0xff,
        (network shr 8) and 0xff,
        network and 0xff,
    ).joinToString(".") { it.toString() }
}

private fun normalizeIpv6Network(ip: String, length: Int): String? {
    val parsed = ipv6Address(ip) ?: return null
    val bytes = parsed.address.copyOf()
    var bits = length
    for (index in bytes.indices) {
        when {
            bits >= 8 -> bits -= 8
            bits <= 0 -> bytes[index] = 0
            else -> {
                val mask = ((0xff shl (8 - bits)) and 0xff)
                bytes[index] = (bytes[index].toInt() and mask).toByte()
                bits = 0
            }
        }
    }
    return InetAddress.getByAddress(bytes).hostAddress
}

private fun ipv6TextEquals(a: String, b: String): Boolean {
    val aa = ipv6Address(a) ?: return false
    val bb = ipv6Address(b) ?: return false
    return aa.address.contentEquals(bb.address)
}

private fun mergeValidationDetails(primary: String?, secondary: String?): String? {
    val merged = listOfNotNull(primary?.trim()?.ifBlank { null }, secondary?.trim()?.ifBlank { null })
    return merged.joinToString("; ").ifBlank { null }
}

private data class ResolvedRpkiTarget(
    val ip: String,
    val inputType: String,
)

private data class RpkiTargetResolution(
    val target: ResolvedRpkiTarget?,
    val error: String? = null,
)

private fun resolveRpkiTarget(input: String, resolver: DnsResolver): RpkiTargetResolution {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return RpkiTargetResolution(null, "IP address or domain is required")

    if (isIpv4(trimmed) || isIpv6(trimmed)) {
        return RpkiTargetResolution(ResolvedRpkiTarget(ip = trimmed, inputType = "ip"))
    }

    val domain = trimmed.removeSuffix(".")
    if (domain.isBlank()) return RpkiTargetResolution(null, "IP address or domain is required")

    val aResult = resolver.lookup(domain, Type.A)
    val resolvedIpv4 = aResult.records
        .asSequence()
        .map { it.data.trim() }
        .firstOrNull { isIpv4(it) }
    if (aResult.isSuccessful && resolvedIpv4 != null) {
        return RpkiTargetResolution(ResolvedRpkiTarget(ip = resolvedIpv4, inputType = "domain"))
    }

    val aaaaResult = resolver.lookup(domain, Type.AAAA)
    val resolvedIpv6 = aaaaResult.records
        .asSequence()
        .map { it.data.trim() }
        .firstOrNull { isIpv6(it) }
    if (aaaaResult.isSuccessful && resolvedIpv6 != null) {
        return RpkiTargetResolution(ResolvedRpkiTarget(ip = resolvedIpv6, inputType = "domain"))
    }

    return RpkiTargetResolution(null, "Could not resolve IP address (A or AAAA) for domain $domain")
}

private fun buildCymruOriginQuery(ip: String): String? {
    if (isIpv4(ip)) {
        val reversed = ip.split(".").reversed().joinToString(".")
        return "$reversed.origin.asn.cymru.com"
    }

    val ipv6 = ipv6Address(ip) ?: return null
    val hex = ipv6.address.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    val reversedNibbles = hex.reversed().toCharArray().joinToString(".") { nibble -> nibble.toString() }
    return "$reversedNibbles.origin6.asn.cymru.com"
}

private fun ipv6Address(value: String): Inet6Address? {
    if (!value.contains(':')) return null
    return try {
        val parsed = InetAddress.getByName(value.trim())
        parsed as? Inet6Address
    } catch (_: Exception) {
        null
    }
}

private fun isIpv6(value: String): Boolean = ipv6Address(value) != null

private val IPV4_REGEX =
    Regex("""^(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$""")

private fun isIpv4(value: String): Boolean = IPV4_REGEX.matches(value)

data class RpkiRouteValidationResponse(
    val input: String,
    val inputType: String,
    val ip: String,
    val asn: String?,
    val prefix: String?,
    val validationState: String,
    val validationSource: String,
    val validationReason: String?,
    val validationDetails: String?,
    val validationVrpObjects: RouteValidationVrpObjects? = null,
)
