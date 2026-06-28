package no.norrs.nortools.web

import io.javalin.http.Context
import no.norrs.nortools.lib.zeroconf.IpFamily
import no.norrs.nortools.lib.zeroconf.LlmnrRecord
import no.norrs.nortools.lib.zeroconf.MdnsRecord
import no.norrs.nortools.lib.zeroconf.NetbiosResponse
import no.norrs.nortools.lib.zeroconf.SsdpMessage
import no.norrs.nortools.lib.zeroconf.WsDiscoveryMessage
import java.time.Duration

internal fun parseIpFamily(ctx: Context, protocol: String = "NetBIOS Name Service"): IpFamily? {
    val raw = ctx.queryParam("ipFamily") ?: "ipv4"
    return runCatching { IpFamily.fromCli(raw) }.getOrElse {
        ctx.jsonResult(errorResponse("Invalid ipFamily '$raw'. Expected ipv4, ipv6, or both.", protocol))
        null
    }
}

internal fun requireNetbiosIpv4(ctx: Context, ipFamily: IpFamily): Boolean =
    requireIpv4(ctx, ipFamily, "NetBIOS Name Service", "NetBIOS Name Service uses IPv4 broadcast over UDP 137.")

internal fun requireMdnsIpv4(ctx: Context, ipFamily: IpFamily): Boolean =
    requireIpv4(ctx, ipFamily, "mDNS", "This first mDNS slice supports IPv4 multicast on 224.0.0.251 only.")

internal fun requireSsdpIpv4(ctx: Context, ipFamily: IpFamily): Boolean =
    requireIpv4(ctx, ipFamily, "SSDP", "This first SSDP slice supports IPv4 multicast on 239.255.255.250 only.")

private fun requireIpv4(ctx: Context, ipFamily: IpFamily, protocol: String, reason: String): Boolean {
    if (ipFamily.allowsIpv4()) return true
    ctx.jsonResult(
        mapOf(
            "protocol" to protocol,
            "status" to "unsupported-ip-family",
            "requestedIpFamily" to ipFamily.name.lowercase(),
            "reason" to reason,
            "rows" to emptyRows(),
        ),
    )
    return false
}

internal fun requestTimeout(ctx: Context): Duration =
    Duration.ofSeconds(ctx.queryParam("timeout")?.toLongOrNull()?.coerceIn(1, 60) ?: 5)

internal fun netbiosEnvelope(mode: String, responses: List<NetbiosResponse>): Map<String, Any?> =
    envelope(
        protocol = "NetBIOS Name Service",
        mode = mode,
        empty = responses.isEmpty(),
        responseCount = responses.size,
        rows = responses.flatMap(::netbiosRows),
        "responses" to responses,
    )

internal fun mdnsEnvelope(
    mode: String,
    records: List<MdnsRecord>,
    responseCount: Int,
    warnings: List<String> = emptyList(),
): Map<String, Any?> =
    envelope(
        protocol = "mDNS",
        mode = mode,
        empty = records.isEmpty(),
        responseCount = responseCount,
        rows = records.map { row(it.section, it.type, it.name, address = it.data, result = it.ttl) },
        "records" to records,
        "warnings" to warnings,
    )

internal fun llmnrEnvelope(
    mode: String,
    records: List<LlmnrRecord>,
    responseCount: Int,
    warnings: List<String> = emptyList(),
): Map<String, Any?> =
    envelope(
        protocol = "LLMNR",
        mode = mode,
        empty = records.isEmpty(),
        responseCount = responseCount,
        rows = records.map { row(it.section, it.type, it.name, address = it.data, group = it.dnsClass, result = it.ttl) },
        "records" to records,
        "warnings" to warnings,
    )

internal fun ssdpEnvelope(
    mode: String,
    messages: List<SsdpMessage>,
    responseCount: Int,
    searchTarget: String? = null,
    warnings: List<String> = emptyList(),
): Map<String, Any?> =
    envelope(
        protocol = "SSDP",
        mode = mode,
        empty = messages.isEmpty(),
        responseCount = responseCount,
        rows = messages.map { message ->
            row(
                type = when {
                    message.isNotify -> "NOTIFY"
                    message.isResponse -> "Response"
                    else -> "Packet"
                },
                name = message.searchTarget ?: message.notificationType ?: "",
                address = message.location ?: "",
                group = message.uniqueServiceName ?: "",
                result = message.server ?: "",
            )
        },
        "searchTarget" to searchTarget,
        "messages" to messages,
        "warnings" to warnings,
    )

internal fun wsdEnvelope(
    mode: String,
    messages: List<WsDiscoveryMessage>,
    responseCount: Int,
    probeTypes: String? = null,
    scopes: String? = null,
    warnings: List<String> = emptyList(),
): Map<String, Any?> =
    envelope(
        protocol = "WS-Discovery",
        mode = mode,
        empty = messages.isEmpty(),
        responseCount = responseCount,
        rows = messages.map {
            row(type = it.messageType, name = it.types ?: "", address = it.xAddrs ?: "", group = it.endpointReference ?: "", result = it.metadataVersion ?: "")
        },
        "probeTypes" to probeTypes,
        "scopes" to scopes,
        "messages" to messages,
        "warnings" to warnings,
    )

internal fun errorResponse(error: String, protocol: String = "NetBIOS Name Service"): Map<String, Any?> =
    mapOf("protocol" to protocol, "status" to "error", "error" to error, "rows" to emptyRows())

private fun envelope(
    protocol: String,
    mode: String,
    empty: Boolean,
    responseCount: Int,
    rows: List<Map<String, Any?>>,
    vararg extra: Pair<String, Any?>,
): Map<String, Any?> =
    linkedMapOf<String, Any?>(
        "protocol" to protocol,
        "mode" to mode,
        "status" to if (empty) "no-responses" else "ok",
        "responseCount" to responseCount,
        "rows" to rows,
    ).apply { extra.forEach { (key, value) -> this[key] = value } }

private fun netbiosRows(response: NetbiosResponse): List<Map<String, Any?>> =
    buildList {
        if (response.addresses.isEmpty() && response.names.isEmpty()) {
            add(row(response.sourceAddress, "Packet", result = response.resultCode, error = response.error ?: ""))
        }
        response.addresses.forEach {
            add(row(response.sourceAddress, "NB", it.name, "0x${it.suffix.hexByte()}", it.address, it.group, response.resultCode))
        }
        response.names.forEach {
            add(row(response.sourceAddress, "NBSTAT", it.name, "0x${it.suffix.hexByte()}", group = it.group, result = response.resultCode))
        }
    }

private fun row(
    source: String = "",
    type: String = "",
    name: String = "",
    suffix: String = "",
    address: String = "",
    group: Any? = "",
    result: Any? = "",
    error: String = "",
): Map<String, Any?> =
    linkedMapOf(
        "source" to source,
        "type" to type,
        "name" to name,
        "suffix" to suffix,
        "address" to address,
        "group" to group,
        "result" to result,
        "error" to error,
    )

private fun Int.hexByte(): String = toString(16).padStart(2, '0')

private fun emptyRows(): List<Map<String, Any?>> = emptyList()
