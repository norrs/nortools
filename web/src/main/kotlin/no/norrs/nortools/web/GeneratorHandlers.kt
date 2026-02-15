package no.norrs.nortools.web

import io.javalin.http.Context

// ─── Generator Tools ────────────────────────────────────────────────────────

fun spfGenerator(ctx: Context) {
    val includes = ctx.queryParam("includes")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    val ip4 = ctx.queryParam("ip4")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    val ip6 = ctx.queryParam("ip6")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    val mx = ctx.queryParam("mx") == "true"
    val a = ctx.queryParam("a") == "true"
    val redirect = ctx.queryParam("redirect")
    val allPolicy = ctx.queryParam("all") ?: "~all"

    val parts = mutableListOf("v=spf1")
    if (a) parts.add("a")
    if (mx) parts.add("mx")
    for (inc in includes) parts.add("include:$inc")
    for (ip in ip4) parts.add("ip4:$ip")
    for (ip in ip6) parts.add("ip6:$ip")
    if (!redirect.isNullOrBlank()) {
        parts.add("redirect=$redirect")
    } else {
        parts.add(allPolicy)
    }

    val record = parts.joinToString(" ")
    val response = SpfGeneratorResponse(
        record = record,
        dnsRecordType = "TXT",
        dnsHost = "@",
        length = record.length,
        valid = record.length <= 255,
        components = parts,
    )
    ctx.jsonResult(response)
}

fun dmarcGenerator(ctx: Context) {
    val policy = ctx.queryParam("policy") ?: "none"
    val sp = ctx.queryParam("sp")
    val pct = ctx.queryParam("pct")?.toIntOrNull()
    val rua = ctx.queryParam("rua")
    val ruf = ctx.queryParam("ruf")
    val adkim = ctx.queryParam("adkim")
    val aspf = ctx.queryParam("aspf")
    val ri = ctx.queryParam("ri")?.toIntOrNull()
    val fo = ctx.queryParam("fo")

    val parts = mutableListOf("v=DMARC1", "p=$policy")
    if (!sp.isNullOrBlank()) parts.add("sp=$sp")
    if (pct != null && pct != 100) parts.add("pct=$pct")
    if (!rua.isNullOrBlank()) parts.add("rua=$rua")
    if (!ruf.isNullOrBlank()) parts.add("ruf=$ruf")
    if (!adkim.isNullOrBlank()) parts.add("adkim=$adkim")
    if (!aspf.isNullOrBlank()) parts.add("aspf=$aspf")
    if (ri != null && ri != 86400) parts.add("ri=$ri")
    if (!fo.isNullOrBlank()) parts.add("fo=$fo")

    val record = parts.joinToString("; ")
    val response = DmarcGeneratorResponse(
        record = record,
        dnsRecordType = "TXT",
        dnsHost = "_dmarc",
        components = parts,
    )
    ctx.jsonResult(response)
}

// ─── Models ─────────────────────────────────────────────────────────────────

data class SpfGeneratorResponse(
    val record: String,
    val dnsRecordType: String,
    val dnsHost: String,
    val length: Int,
    val valid: Boolean,
    val components: List<String>,
)

data class DmarcGeneratorResponse(
    val record: String,
    val dnsRecordType: String,
    val dnsHost: String,
    val components: List<String>,
)
