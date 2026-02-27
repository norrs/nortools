package no.norrs.nortools.web

import io.javalin.http.Context
import no.norrs.nortools.tools.whois.common.WhoisClient

// ─── WHOIS Tools ─────────────────────────────────────────────────────────────

fun whoisLookup(ctx: Context) {
    val query = ctx.pathParam("query")
    try {
        val result = WhoisClient.lookup(query)
        ctx.jsonResult(
            WhoisResponse(
                query = query,
                server = result.finalServer,
                servers = result.hops.map { it.server },
                fields = result.mergedFields,
                raw = result.combinedRaw(),
            ),
        )
    } catch (e: Exception) {
        ctx.jsonResult(ErrorResponse("WHOIS failed: ${e.message}"))
    }
}

// ─── Models ─────────────────────────────────────────────────────────────────

data class WhoisResponse(
    val query: String,
    val server: String,
    val servers: List<String> = emptyList(),
    val fields: Map<String, String>,
    val raw: String,
)
