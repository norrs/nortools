package no.norrs.nortools.web

import io.javalin.http.Context
import no.norrs.nortools.lib.dns.DnsResolver
import org.xbill.DNS.Type

// ─── Blocklist Tools ────────────────────────────────────────────────────────

private val DNSBL_SERVERS = listOf(
    "zen.spamhaus.org", "bl.spamcop.net", "b.barracudacentral.org",
    "dnsbl.sorbs.net", "spam.dnsbl.sorbs.net", "dul.dnsbl.sorbs.net",
    "dnsbl-1.uceprotect.net", "dnsbl-2.uceprotect.net", "dnsbl-3.uceprotect.net",
    "psbl.surriel.com", "db.wpbl.info", "all.s5h.net",
    "dyna.spamrats.com", "noptr.spamrats.com", "spam.spamrats.com",
    "cbl.abuseat.org", "dnsbl.dronebl.org", "rbl.interserver.net",
    "truncate.gbudb.net",
)

fun blacklistCheck(ctx: Context) {
    val ip = ctx.pathParam("ip")
    val reversed = ip.split(".").reversed().joinToString(".")
    val resolver = DnsResolver()
    val results = mutableListOf<BlocklistResult>()

    for (dnsbl in DNSBL_SERVERS) {
        val query = "$reversed.$dnsbl"
        val aResult = resolver.lookup(query, Type.A)
        val listed = aResult.isSuccessful && aResult.records.isNotEmpty()
        var reason: String? = null
        if (listed) {
            val txtResult = resolver.lookup(query, Type.TXT)
            if (txtResult.isSuccessful && txtResult.records.isNotEmpty()) {
                reason = txtResult.records.first().data
            }
        }
        results.add(BlocklistResult(server = dnsbl, listed = listed, reason = reason))
    }

    val listedCount = results.count { it.listed }
    val response = BlocklistResponse(
        ip = ip,
        totalChecked = DNSBL_SERVERS.size,
        listedOn = listedCount,
        clean = listedCount == 0,
        results = results,
    )
    ctx.jsonResult(response)
}

// ─── Models ─────────────────────────────────────────────────────────────────

data class BlocklistResult(
    val server: String,
    val listed: Boolean,
    val reason: String?,
)

data class BlocklistResponse(
    val ip: String,
    val totalChecked: Int,
    val listedOn: Int,
    val clean: Boolean,
    val results: List<BlocklistResult>,
)
