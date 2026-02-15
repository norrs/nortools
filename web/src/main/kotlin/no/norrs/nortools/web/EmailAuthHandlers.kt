package no.norrs.nortools.web

import io.javalin.http.Context
import no.norrs.nortools.lib.dns.DnsResolver
import org.xbill.DNS.Type
import java.time.Duration

// ─── Email Auth Tools ────────────────────────────────────────────────────────

fun spfLookup(ctx: Context) {
    val domain = ctx.pathParam("domain")
    val resolver = DnsResolver()
    val result = resolver.lookup(domain, Type.TXT)
    val spfRecords = if (result.isSuccessful) {
        result.records.filter { it.data.startsWith("v=spf1") }
    } else emptyList()

    val spfRecord = spfRecords.firstOrNull()?.data
    val mechanisms = if (spfRecord != null) parseSpfMechanisms(spfRecord, domain) else emptyList()

    val response = SpfLookupResponse(
        domain = domain,
        found = spfRecord != null,
        record = spfRecord,
        ttl = spfRecords.firstOrNull()?.ttl,
        mechanisms = mechanisms,
        multipleRecords = spfRecords.size > 1,
    )
    ctx.jsonResult(response)
}

private fun parseSpfMechanisms(spf: String, domain: String): List<SpfMechanism> {
    val parts = spf.split(" ").drop(1)
    return parts.map { part ->
        val qualifier = when {
            part.startsWith("+") -> "Pass"
            part.startsWith("-") -> "Fail"
            part.startsWith("~") -> "SoftFail"
            part.startsWith("?") -> "Neutral"
            else -> "Pass"
        }
        val mechanism = part.trimStart('+', '-', '~', '?')
        val (type, value) = when {
            mechanism.startsWith("include:") -> "include" to mechanism.removePrefix("include:")
            mechanism.startsWith("a:") -> "a" to mechanism.removePrefix("a:")
            mechanism.startsWith("mx:") -> "mx" to mechanism.removePrefix("mx:")
            mechanism.startsWith("ip4:") -> "ip4" to mechanism.removePrefix("ip4:")
            mechanism.startsWith("ip6:") -> "ip6" to mechanism.removePrefix("ip6:")
            mechanism.startsWith("redirect=") -> "redirect" to mechanism.removePrefix("redirect=")
            mechanism.startsWith("exists:") -> "exists" to mechanism.removePrefix("exists:")
            mechanism == "a" -> "a" to domain
            mechanism == "mx" -> "mx" to domain
            mechanism == "all" -> "all" to ""
            mechanism == "ptr" -> "ptr" to "(deprecated)"
            else -> mechanism to ""
        }
        SpfMechanism(qualifier = qualifier, mechanism = type, value = value)
    }
}

fun dkimLookup(ctx: Context) {
    val selector = ctx.pathParam("selector")
    val domain = ctx.pathParam("domain")
    val resolver = DnsResolver()
    val dkimDomain = "$selector._domainkey.$domain"
    val result = resolver.lookup(dkimDomain, Type.TXT)
    val dkimRecords = if (result.isSuccessful) {
        result.records.filter { it.data.contains("v=DKIM1") || it.data.contains("k=") }
    } else emptyList()

    val dkimRecord = dkimRecords.firstOrNull()?.data
    val tags = if (dkimRecord != null) parseTags(dkimRecord) else emptyMap()

    val response = DkimLookupResponse(
        domain = domain,
        selector = selector,
        dkimDomain = dkimDomain,
        found = dkimRecord != null,
        record = dkimRecord,
        version = tags["v"] ?: "DKIM1",
        keyType = tags["k"] ?: "rsa",
        publicKey = tags["p"]?.take(60)?.plus("...") ?: "N/A",
        hashAlgorithms = tags["h"] ?: "all",
        serviceType = tags["s"] ?: "*",
        flags = tags["t"] ?: "none",
        ttl = dkimRecords.firstOrNull()?.ttl,
    )
    ctx.jsonResult(response)
}

/** Well-known DKIM selectors used by major email providers and platforms. */
private val COMMON_DKIM_SELECTORS = listOf(
    // Google Workspace / Gmail
    "google", "google2048", "20161025", "20230601", "20210112",
    // Microsoft 365 / Outlook
    "selector1", "selector2", "selector1-azurecomm-prod-net",
    // Amazon SES
    "ses", "amazonses", "ug7nbtf4gccmlpwj322ax3p6ow6yfsug",
    "3gvwdm5gzn4xnm6dyqmyqisoha5r57tv", "k1", "k2", "k3",
    // Mailchimp / Mandrill
    "k1", "mandrill", "mcdkim", "mcdkim2",
    // SendGrid
    "s1", "s2", "smtpapi", "sgrid",
    // Salesforce / Pardot
    "sf", "sf1", "sf2", "pardot",
    // Mailgun
    "mg", "mailo", "smtp", "pic",
    // Postmark
    "pm", "20240913",
    // Brevo (Sendinblue)
    "mail", "mail2",
    // Zoho
    "zoho", "zoho1",
    // ProtonMail
    "protonmail", "protonmail2", "protonmail3",
    // Fastmail
    "fm1", "fm2", "fm3", "mesmtp",
    // Yahoo / AOL
    "yahoo", "s1024", "s2048",
    // SparkPost
    "sparkpost", "scph0316",
    // Constant Contact
    "ctct1", "ctct2",
    // HubSpot
    "hs1", "hs2", "hubspot",
    // Klaviyo
    "kl", "kl2",
    // Intercom
    "ic",
    // Zendesk
    "zendesk1", "zendesk2",
    // Freshdesk
    "freshdesk",
    // Mimecast
    "mimecast20190104",
    // Generic / common defaults
    "default", "dkim", "dkim2", "email", "mail", "mailer",
    "selector", "sig1", "key1", "key2", "smtp", "x",
    // Numeric patterns
    "1", "2", "3", "201505", "20150623", "20170101",
    // Cloudflare
    "cf", "cf2",
    // Twilio
    "twilio",
    // Mailjet
    "mailjet",
    // Campaign Monitor
    "cm",
    // ActiveCampaign
    "dk",
    // Elastic Email
    "api",
    // Postfix / cPanel / Plesk
    "mx", "main", "a1", "a2",
).distinct()

fun dkimDiscover(ctx: Context) {
    val domain = ctx.pathParam("domain")
    val resolver = DnsResolver(timeout = Duration.ofSeconds(3))
    val found = mutableListOf<DkimDiscoverEntry>()

    for (selector in COMMON_DKIM_SELECTORS) {
        val dkimDomain = "$selector._domainkey.$domain"
        try {
            val result = resolver.lookup(dkimDomain, Type.TXT)
            if (!result.isSuccessful) continue
            val dkimRecords = result.records.filter {
                it.data.contains("v=DKIM1") || it.data.contains("k=") || it.data.contains("p=")
            }
            if (dkimRecords.isEmpty()) continue
            val record = dkimRecords.first().data
            val tags = parseTags(record)
            found.add(
                DkimDiscoverEntry(
                    selector = selector,
                    dkimDomain = dkimDomain,
                    record = record,
                    keyType = tags["k"] ?: "rsa",
                    publicKey = tags["p"]?.take(60)?.plus("...") ?: "N/A",
                    flags = tags["t"] ?: "none",
                    ttl = dkimRecords.first().ttl,
                )
            )
        } catch (_: Exception) {
            // Timeout or DNS error — skip this selector
        }
    }

    val response = DkimDiscoverResponse(
        domain = domain,
        selectorsProbed = COMMON_DKIM_SELECTORS.size,
        selectorsFound = found.size,
        selectors = found,
    )
    ctx.jsonResult(response)
}

fun dmarcLookup(ctx: Context) {
    val domain = ctx.pathParam("domain")
    val resolver = DnsResolver()
    val dmarcDomain = "_dmarc.$domain"
    val result = resolver.lookup(dmarcDomain, Type.TXT)
    val dmarcRecords = if (result.isSuccessful) {
        result.records.filter { it.data.startsWith("v=DMARC1") }
    } else emptyList()

    val dmarcRecord = dmarcRecords.firstOrNull()?.data
    val tags = if (dmarcRecord != null) parseTags(dmarcRecord) else emptyMap()

    val policyDesc = when (tags["p"]) {
        "none" -> "none (monitor only)"
        "quarantine" -> "quarantine (mark as spam)"
        "reject" -> "reject (block delivery)"
        else -> tags["p"] ?: "not specified"
    }

    val response = DmarcLookupResponse(
        domain = domain,
        found = dmarcRecord != null,
        record = dmarcRecord,
        policy = policyDesc,
        subdomainPolicy = tags["sp"] ?: "same as domain",
        pct = tags["pct"] ?: "100",
        dkimAlignment = tags["adkim"]?.let { if (it == "s") "strict" else "relaxed" } ?: "relaxed",
        spfAlignment = tags["aspf"]?.let { if (it == "s") "strict" else "relaxed" } ?: "relaxed",
        rua = tags["rua"] ?: "not configured",
        ruf = tags["ruf"] ?: "not configured",
        ttl = dmarcRecords.firstOrNull()?.ttl,
    )
    ctx.jsonResult(response)
}

private fun parseTags(record: String): Map<String, String> {
    return record.split(";")
        .map { it.trim() }
        .filter { it.contains("=") }
        .associate { tag ->
            val (key, value) = tag.split("=", limit = 2)
            key.trim() to value.trim()
        }
}

// ─── Models ─────────────────────────────────────────────────────────────────

data class SpfLookupResponse(
    val domain: String,
    val found: Boolean,
    val record: String?,
    val ttl: Long?,
    val mechanisms: List<SpfMechanism>,
    val multipleRecords: Boolean,
)

data class SpfMechanism(
    val qualifier: String,
    val mechanism: String,
    val value: String,
)

data class DkimLookupResponse(
    val domain: String,
    val selector: String,
    val dkimDomain: String,
    val found: Boolean,
    val record: String?,
    val version: String,
    val keyType: String,
    val publicKey: String,
    val hashAlgorithms: String,
    val serviceType: String,
    val flags: String,
    val ttl: Long?,
)

data class DkimDiscoverEntry(
    val selector: String,
    val dkimDomain: String,
    val record: String,
    val keyType: String,
    val publicKey: String,
    val flags: String,
    val ttl: Long?,
)

data class DkimDiscoverResponse(
    val domain: String,
    val selectorsProbed: Int,
    val selectorsFound: Int,
    val selectors: List<DkimDiscoverEntry>,
)

data class DmarcLookupResponse(
    val domain: String,
    val found: Boolean,
    val record: String?,
    val policy: String,
    val subdomainPolicy: String,
    val pct: String,
    val dkimAlignment: String,
    val spfAlignment: String,
    val rua: String,
    val ruf: String,
    val ttl: Long?,
)
