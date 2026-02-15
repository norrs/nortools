package no.norrs.nortools.tools.email.dkim

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.Type

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
    "mandrill", "mcdkim", "mcdkim2",
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
    "default", "dkim", "dkim2", "email", "mailer",
    "selector", "sig1", "key1", "key2", "x",
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

/**
 * DKIM Record Lookup tool — queries DKIM selector records for a domain.
 *
 * DKIM (DomainKeys Identified Mail) uses public keys published in DNS
 * to verify email signatures.
 * Uses RFC 6376 (DKIM Signatures) and RFC 8463 (Ed25519 for DKIM).
 *
 * Supports two modes:
 * - Manual: provide a selector and domain to look up a specific DKIM record
 * - Discover: use --discover with just a domain to probe common selectors
 */
class DkimLookupCommand : BaseCommand(
    name = "dkim",
    helpText = "Look up DKIM records for a domain. Use --discover to auto-find selectors.",
) {
    private val selectorArg by argument(
        name = "selector-or-domain",
        help = "DKIM selector when looking up a specific record, or domain when using --discover",
    )
    private val domainArg by argument(
        name = "domain",
        help = "Domain name (required when providing a selector)",
    ).optional()
    private val discover by option(
        "--discover", "-d",
        help = "Auto-discover DKIM selectors by probing common selector names",
    ).flag(default = false)

    override fun run() {
        if (discover) {
            // In discover mode: first arg is the domain
            runDiscover(selectorArg)
        } else {
            if (domainArg == null) {
                echo("Error: both selector and domain are required for DKIM lookup.")
                echo("Usage: dkim <selector> <domain>")
                echo("       dkim --discover <domain>")
                return
            }
            runLookup(selectorArg, domainArg!!)
        }
    }

    private fun runLookup(sel: String, domain: String) {
        val resolver = createResolver()
        val formatter = createFormatter()

        val dkimDomain = "$sel._domainkey.$domain"
        val result = resolver.lookup(dkimDomain, Type.TXT)

        if (!result.isSuccessful) {
            if (jsonOutput) {
                echo(formatter.formatDetail(mapOf("Error" to "DKIM lookup failed for $dkimDomain: ${result.status}")))
            } else {
                echo("DKIM lookup failed for $dkimDomain: ${result.status}")
            }
            return
        }

        val dkimRecords = result.records.filter {
            it.data.contains("v=DKIM1") || it.data.contains("k=")
        }

        if (dkimRecords.isEmpty()) {
            if (jsonOutput) {
                echo(formatter.formatDetail(mapOf("Error" to "No DKIM records found for selector '$sel' at $dkimDomain")))
            } else {
                echo("No DKIM records found for selector '$sel' at $dkimDomain")
            }
            return
        }

        val dkimRecord = dkimRecords.first().data
        val tags = parseDkimTags(dkimRecord)

        val details = linkedMapOf<String, Any?>(
            "Domain" to domain,
            "Selector" to sel,
            "DKIM Domain" to dkimDomain,
            "Version" to (tags["v"] ?: "DKIM1"),
            "Key Type" to (tags["k"] ?: "rsa"),
            "Public Key" to (tags["p"]?.take(60)?.plus("...") ?: "N/A"),
            "Hash Algorithms" to (tags["h"] ?: "all"),
            "Service Type" to (tags["s"] ?: "*"),
            "Flags" to (tags["t"] ?: "none"),
            "Notes" to (tags["n"] ?: ""),
            "TTL" to "${dkimRecords.first().ttl}s",
        )

        echo(formatter.formatDetail(details))
    }

    private fun runDiscover(domain: String) {
        val resolver = createResolver()
        val formatter = createFormatter()
        val found = mutableListOf<Map<String, Any?>>()

        if (!jsonOutput) {
            echo("Discovering DKIM selectors for $domain (probing ${COMMON_DKIM_SELECTORS.size} common selectors)...")
            echo()
        }

        for (sel in COMMON_DKIM_SELECTORS) {
            val dkimDomain = "$sel._domainkey.$domain"
            try {
                val result = resolver.lookup(dkimDomain, Type.TXT)
                if (!result.isSuccessful) continue
                val dkimRecords = result.records.filter {
                    it.data.contains("v=DKIM1") || it.data.contains("k=") || it.data.contains("p=")
                }
                if (dkimRecords.isEmpty()) continue
                val record = dkimRecords.first().data
                val tags = parseDkimTags(record)
                found.add(linkedMapOf(
                    "Selector" to sel,
                    "DKIM Domain" to dkimDomain,
                    "Key Type" to (tags["k"] ?: "rsa"),
                    "Public Key" to (tags["p"]?.take(40)?.plus("...") ?: "N/A"),
                    "Flags" to (tags["t"] ?: "none"),
                    "TTL" to "${dkimRecords.first().ttl}s",
                ))
                if (!jsonOutput) {
                    echo("  ✅ Found: $sel ($dkimDomain)")
                }
            } catch (_: Exception) {
                // Timeout or DNS error — skip
            }
        }

        if (jsonOutput) {
            echo(formatter.format(found))
            return
        }

        echo()
        if (found.isEmpty()) {
            echo("No DKIM selectors found for $domain.")
            echo("The domain may use a custom selector name not in the common list.")
        } else {
            echo("Found ${found.size} DKIM selector(s) for $domain:")
            echo()
            for (entry in found) {
                echo(formatter.formatDetail(entry))
                echo()
            }
        }
    }

    private fun parseDkimTags(record: String): Map<String, String> {
        return record.split(";")
            .map { it.trim() }
            .filter { it.contains("=") }
            .associate { tag ->
                val (key, value) = tag.split("=", limit = 2)
                key.trim() to value.trim()
            }
    }
}

fun main(args: Array<String>) = DkimLookupCommand().main(args)
