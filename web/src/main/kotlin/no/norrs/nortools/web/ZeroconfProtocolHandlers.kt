package no.norrs.nortools.web

import io.javalin.http.Context
import no.norrs.nortools.lib.zeroconf.LlmnrClient
import no.norrs.nortools.lib.zeroconf.MdnsClient
import no.norrs.nortools.lib.zeroconf.NetbiosNameServiceClient
import no.norrs.nortools.lib.zeroconf.SsdpClient
import no.norrs.nortools.lib.zeroconf.WsDiscoveryClient

fun netbiosNameQuery(ctx: Context) {
    val ipFamily = parseIpFamily(ctx) ?: return
    if (!requireNetbiosIpv4(ctx, ipFamily)) return

    val client = NetbiosNameServiceClient(timeout = requestTimeout(ctx))
    val responses = ctx.resultOrError("NetBIOS query failed") {
        client.queryName(
            name = ctx.pathParam("name"),
            suffix = ctx.queryParam("suffix")?.toIntOrNull() ?: 0x20,
            target = ctx.queryParam("target")?.takeIf { it.isNotBlank() } ?: "255.255.255.255",
        )
    } ?: return
    ctx.jsonResult(netbiosEnvelope(mode = "query", responses = responses))
}

fun netbiosNodeStatus(ctx: Context) {
    val ipFamily = parseIpFamily(ctx) ?: return
    if (!requireNetbiosIpv4(ctx, ipFamily)) return

    val client = NetbiosNameServiceClient(timeout = requestTimeout(ctx))
    val responses = ctx.resultOrError("NetBIOS node status failed") { client.nodeStatus(ctx.pathParam("host")) } ?: return
    ctx.jsonResult(netbiosEnvelope(mode = "node-status", responses = responses))
}

fun netbiosListen(ctx: Context) {
    val ipFamily = parseIpFamily(ctx) ?: return
    if (!requireNetbiosIpv4(ctx, ipFamily)) return

    val client = NetbiosNameServiceClient(timeout = requestTimeout(ctx))
    val responses = ctx.resultOrError("NetBIOS listener failed") {
        client.listen(bindAddress = ctx.bindAddress("0.0.0.0"), maxPackets = ctx.maxPackets())
    } ?: return
    ctx.jsonResult(netbiosEnvelope(mode = "listen", responses = responses))
}

fun llmnrQuery(ctx: Context) {
    val ipFamily = parseIpFamily(ctx, protocol = "LLMNR") ?: return
    val client = LlmnrClient(timeout = requestTimeout(ctx))
    val result = ctx.resultOrError("LLMNR query failed", "LLMNR") {
        client.query(
            name = ctx.pathParam("name"),
            type = ctx.queryParam("type")?.takeIf { it.isNotBlank() } ?: "A",
            ipFamily = ipFamily,
            bindAddress = ctx.bindAddress(),
            maxPackets = ctx.maxPackets(),
        )
    } ?: return
    ctx.jsonResult(llmnrEnvelope("query", result.records, result.responseCount, result.warnings))
}

fun llmnrListen(ctx: Context) {
    val ipFamily = parseIpFamily(ctx, protocol = "LLMNR") ?: return
    val client = LlmnrClient(timeout = requestTimeout(ctx))
    val result = ctx.resultOrError("LLMNR listener failed", "LLMNR") {
        client.listen(ipFamily = ipFamily, bindAddress = ctx.bindAddress(), maxPackets = ctx.maxPackets())
    } ?: return
    ctx.jsonResult(llmnrEnvelope("listen", result.records, result.responseCount, result.warnings))
}

fun mdnsQuery(ctx: Context) {
    val ipFamily = parseIpFamily(ctx, protocol = "mDNS") ?: return
    if (!requireMdnsIpv4(ctx, ipFamily)) return

    val client = MdnsClient(timeout = requestTimeout(ctx))
    val result = ctx.resultOrError("mDNS query failed", "mDNS") {
        client.query(
            name = ctx.pathParam("name"),
            type = ctx.queryParam("type")?.takeIf { it.isNotBlank() } ?: "PTR",
            bindAddress = ctx.bindAddress(),
            maxPackets = ctx.maxPackets(),
        )
    } ?: return
    ctx.jsonResult(mdnsEnvelope("query", result.records, result.responseCount))
}

fun mdnsListen(ctx: Context) {
    val ipFamily = parseIpFamily(ctx, protocol = "mDNS") ?: return
    if (!requireMdnsIpv4(ctx, ipFamily)) return

    val client = MdnsClient(timeout = requestTimeout(ctx))
    val result = ctx.resultOrError("mDNS listener failed", "mDNS") {
        client.listen(bindAddress = ctx.bindAddress("0.0.0.0"), maxPackets = ctx.maxPackets())
    } ?: return
    ctx.jsonResult(mdnsEnvelope("listen", result.records, result.responseCount, result.warnings))
}

fun ssdpSearch(ctx: Context) {
    val ipFamily = parseIpFamily(ctx, protocol = "SSDP") ?: return
    if (!requireSsdpIpv4(ctx, ipFamily)) return

    val client = SsdpClient(timeout = requestTimeout(ctx))
    val result = ctx.resultOrError("SSDP search failed", "SSDP") {
        client.search(
            searchTarget = ctx.queryParam("searchTarget")?.takeIf { it.isNotBlank() } ?: "ssdp:all",
            bindAddress = ctx.bindAddress(),
            maxPackets = ctx.maxPackets(),
        )
    } ?: return
    ctx.jsonResult(ssdpEnvelope(result.mode, result.messages, result.responseCount, result.searchTarget, result.warnings))
}

fun ssdpListen(ctx: Context) {
    val ipFamily = parseIpFamily(ctx, protocol = "SSDP") ?: return
    if (!requireSsdpIpv4(ctx, ipFamily)) return

    val client = SsdpClient(timeout = requestTimeout(ctx))
    val result = ctx.resultOrError("SSDP listener failed", "SSDP") {
        client.listen(bindAddress = ctx.bindAddress("0.0.0.0"), maxPackets = ctx.maxPackets())
    } ?: return
    ctx.jsonResult(ssdpEnvelope(result.mode, result.messages, result.responseCount, result.searchTarget, result.warnings))
}

fun wsdProbe(ctx: Context) {
    val ipFamily = parseIpFamily(ctx, protocol = "WS-Discovery") ?: return
    val client = WsDiscoveryClient(timeout = requestTimeout(ctx))
    val result = ctx.resultOrError("WS-Discovery probe failed", "WS-Discovery") {
        client.probe(
            types = ctx.queryParam("types")?.takeIf { it.isNotBlank() },
            scopes = ctx.queryParam("scopes")?.takeIf { it.isNotBlank() },
            ipFamily = ipFamily,
            bindAddress = ctx.bindAddress(),
            maxPackets = ctx.maxPackets(),
        )
    } ?: return
    ctx.jsonResult(wsdEnvelope(result.mode, result.messages, result.responseCount, result.probeTypes, result.scopes, result.warnings))
}

fun wsdListen(ctx: Context) {
    val ipFamily = parseIpFamily(ctx, protocol = "WS-Discovery") ?: return
    val client = WsDiscoveryClient(timeout = requestTimeout(ctx))
    val result = ctx.resultOrError("WS-Discovery listener failed", "WS-Discovery") {
        client.listen(bindAddress = ctx.bindAddress("0.0.0.0"), ipFamily = ipFamily, maxPackets = ctx.maxPackets())
    } ?: return
    ctx.jsonResult(wsdEnvelope(result.mode, result.messages, result.responseCount, result.probeTypes, result.scopes, result.warnings))
}

private inline fun <T> Context.resultOrError(errorMessage: String, protocol: String = "NetBIOS Name Service", block: () -> T): T? =
    runCatching(block).getOrElse { error ->
        jsonResult(errorResponse(protocol = protocol, error = error.message ?: errorMessage))
        null
    }

private fun Context.bindAddress(default: String? = null): String? =
    queryParam("bindAddress")?.takeIf { it.isNotBlank() } ?: default

private fun Context.maxPackets(): Int =
    queryParam("maxPackets")?.toIntOrNull()?.coerceIn(1, 250) ?: 25
