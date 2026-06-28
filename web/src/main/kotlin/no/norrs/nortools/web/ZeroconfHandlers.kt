package no.norrs.nortools.web

import io.javalin.http.Context

fun zeroconfDashboard(ctx: Context) {
    ZeroconfDiscoveryMonitor.start()
    ctx.jsonResult(ZeroconfDiscoveryMonitor.snapshot())
}

fun zeroconfDashboardRefresh(ctx: Context) {
    ZeroconfDiscoveryMonitor.start()
    Thread {
        ZeroconfDiscoveryMonitor.refreshNow()
    }.apply {
        isDaemon = true
        name = "zeroconf-dashboard-refresh"
        start()
    }
    ctx.jsonResult(ZeroconfDiscoveryMonitor.snapshot())
}

fun zeroconfDeviceDetails(ctx: Context) {
    ZeroconfDiscoveryMonitor.start()
    val deviceId = ctx.pathParam("id")
    val device = ZeroconfDiscoveryMonitor.deviceById(deviceId)
        ?: return ctx.jsonResult(errorResponse(protocol = "ZeroConf Device Detail", error = "Unknown device id: $deviceId"))
    val includeSmb = ctx.queryParam("includeSmb")?.equals("true", ignoreCase = true) == true
    ctx.jsonResult(ZeroconfHostInspector.inspect(device, requestTimeout(ctx), includeSmb = includeSmb))
}

fun zeroconfDeviceDocument(ctx: Context) {
    ZeroconfDiscoveryMonitor.start()
    val deviceId = ctx.pathParam("id")
    val index = ctx.pathParam("index").toIntOrNull()
        ?: return ctx.jsonResult(errorResponse(protocol = "ZeroConf Device Document", error = "Invalid document index"))
    val document = ZeroconfDiscoveryMonitor.documentById(deviceId, index)
        ?: return ctx.jsonResult(errorResponse(protocol = "ZeroConf Device Document", error = "Unknown device document: $deviceId/$index"))
    ctx.contentType(document.first)
    ctx.result(document.second)
}
