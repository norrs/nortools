package no.norrs.nortools.web

import io.javalin.http.Context
import no.norrs.nortools.lib.zeroconf.IpFamily
import no.norrs.nortools.lib.zeroconf.NetbiosNameServiceClient
import no.norrs.nortools.lib.zeroconf.NetbiosResponse
import java.time.Duration

fun netbiosNameQuery(ctx: Context) {
    val ipFamily = parseIpFamily(ctx) ?: return
    if (!requireNetbiosIpv4(ctx, ipFamily)) return

    val client = NetbiosNameServiceClient(timeout = requestTimeout(ctx))
    val name = ctx.pathParam("name")
    val target = ctx.queryParam("target")?.takeIf { it.isNotBlank() } ?: "255.255.255.255"
    val suffix = ctx.queryParam("suffix")?.toIntOrNull() ?: 0x20
    val responses = runCatching {
        client.queryName(name = name, suffix = suffix, target = target)
    }.getOrElse { error ->
        return ctx.jsonResult(errorResponse(error.message ?: "NetBIOS query failed"))
    }

    ctx.jsonResult(netbiosEnvelope(mode = "query", responses = responses))
}

fun netbiosNodeStatus(ctx: Context) {
    val ipFamily = parseIpFamily(ctx) ?: return
    if (!requireNetbiosIpv4(ctx, ipFamily)) return

    val client = NetbiosNameServiceClient(timeout = requestTimeout(ctx))
    val host = ctx.pathParam("host")
    val responses = runCatching {
        client.nodeStatus(host)
    }.getOrElse { error ->
        return ctx.jsonResult(errorResponse(error.message ?: "NetBIOS node status failed"))
    }

    ctx.jsonResult(netbiosEnvelope(mode = "node-status", responses = responses))
}

fun netbiosListen(ctx: Context) {
    val ipFamily = parseIpFamily(ctx) ?: return
    if (!requireNetbiosIpv4(ctx, ipFamily)) return

    val client = NetbiosNameServiceClient(timeout = requestTimeout(ctx))
    val maxPackets = ctx.queryParam("maxPackets")?.toIntOrNull()?.coerceIn(1, 250) ?: 25
    val responses = runCatching {
        client.listen(maxPackets = maxPackets)
    }.getOrElse { error ->
        return ctx.jsonResult(errorResponse(error.message ?: "NetBIOS listener failed"))
    }

    ctx.jsonResult(netbiosEnvelope(mode = "listen", responses = responses))
}

private fun parseIpFamily(ctx: Context): IpFamily? {
    val raw = ctx.queryParam("ipFamily") ?: "ipv4"
    return runCatching { IpFamily.fromCli(raw) }.getOrElse {
        ctx.jsonResult(
            mapOf(
                "protocol" to "NetBIOS Name Service",
                "status" to "error",
                "error" to "Invalid ipFamily '$raw'. Expected ipv4, ipv6, or both.",
                "rows" to emptyList<Map<String, Any?>>(),
            ),
        )
        null
    }
}

private fun requireNetbiosIpv4(ctx: Context, ipFamily: IpFamily): Boolean {
    if (ipFamily.allowsIpv4()) return true
    ctx.jsonResult(
        mapOf(
            "protocol" to "NetBIOS Name Service",
            "status" to "unsupported-ip-family",
            "requestedIpFamily" to ipFamily.name.lowercase(),
            "reason" to "NetBIOS Name Service uses IPv4 broadcast over UDP 137.",
            "rows" to emptyList<Map<String, Any?>>(),
        ),
    )
    return false
}

private fun requestTimeout(ctx: Context): Duration {
    val seconds = ctx.queryParam("timeout")?.toLongOrNull()?.coerceIn(1, 60) ?: 5
    return Duration.ofSeconds(seconds)
}

private fun netbiosEnvelope(mode: String, responses: List<NetbiosResponse>): Map<String, Any?> =
    mapOf(
        "protocol" to "NetBIOS Name Service",
        "mode" to mode,
        "status" to if (responses.isEmpty()) "no-responses" else "ok",
        "responseCount" to responses.size,
        "rows" to flattenNetbiosResponses(responses),
        "responses" to responses,
    )

private fun errorResponse(error: String): Map<String, Any?> =
    mapOf(
        "protocol" to "NetBIOS Name Service",
        "status" to "error",
        "error" to error,
        "rows" to emptyList<Map<String, Any?>>(),
    )

private fun flattenNetbiosResponses(responses: List<NetbiosResponse>): List<Map<String, Any?>> {
    val rows = mutableListOf<Map<String, Any?>>()
    for (response in responses) {
        if (response.addresses.isEmpty() && response.names.isEmpty()) {
            rows += linkedMapOf(
                "source" to response.sourceAddress,
                "type" to "Packet",
                "name" to "",
                "suffix" to "",
                "address" to "",
                "group" to "",
                "result" to response.resultCode,
                "error" to (response.error ?: ""),
            )
        }
        for (address in response.addresses) {
            rows += linkedMapOf(
                "source" to response.sourceAddress,
                "type" to "NB",
                "name" to address.name,
                "suffix" to "0x${address.suffix.toString(16).padStart(2, '0')}",
                "address" to address.address,
                "group" to address.group,
                "result" to response.resultCode,
                "error" to "",
            )
        }
        for (name in response.names) {
            rows += linkedMapOf(
                "source" to response.sourceAddress,
                "type" to "NBSTAT",
                "name" to name.name,
                "suffix" to "0x${name.suffix.toString(16).padStart(2, '0')}",
                "address" to "",
                "group" to name.group,
                "result" to response.resultCode,
                "error" to "",
            )
        }
    }
    return rows
}
