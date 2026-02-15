package no.norrs.nortools.tools.email.dmarc

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.Type

/**
 * DMARC Record Lookup tool — queries and parses DMARC policy for a domain.
 *
 * DMARC (Domain-based Message Authentication, Reporting & Conformance)
 * builds on SPF and DKIM to provide email authentication policy.
 * Uses RFC 7489 (DMARC).
 */
class DmarcLookupCommand : BaseCommand(
    name = "dmarc",
    helpText = "Look up and analyze DMARC policy for a domain",
) {
    private val domain by argument(help = "Domain name to look up DMARC policy for")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()

        val dmarcDomain = "_dmarc.$domain"
        val result = resolver.lookup(dmarcDomain, Type.TXT)

        if (!result.isSuccessful) {
            echo("DMARC lookup failed for $dmarcDomain: ${result.status}")
            return
        }

        val dmarcRecords = result.records.filter { it.data.startsWith("v=DMARC1") }

        if (dmarcRecords.isEmpty()) {
            echo("No DMARC records found for $domain")
            return
        }

        val dmarcRecord = dmarcRecords.first().data
        val tags = parseDmarcTags(dmarcRecord)

        val policyDesc = when (tags["p"]) {
            "none" -> "none (monitor only)"
            "quarantine" -> "quarantine (mark as spam)"
            "reject" -> "reject (block delivery)"
            else -> tags["p"] ?: "not specified"
        }

        val subdomainPolicy = when (tags["sp"]) {
            "none" -> "none (monitor only)"
            "quarantine" -> "quarantine (mark as spam)"
            "reject" -> "reject (block delivery)"
            null -> "same as domain policy"
            else -> tags["sp"]!!
        }

        val details = linkedMapOf<String, Any?>(
            "Domain" to domain,
            "DMARC Record" to dmarcRecord,
            "Version" to (tags["v"] ?: "DMARC1"),
            "Policy" to policyDesc,
            "Subdomain Policy" to subdomainPolicy,
            "PCT" to "${tags["pct"] ?: "100"}%",
            "DKIM Alignment" to (tags["adkim"]?.let { if (it == "s") "strict" else "relaxed" } ?: "relaxed"),
            "SPF Alignment" to (tags["aspf"]?.let { if (it == "s") "strict" else "relaxed" } ?: "relaxed"),
            "Aggregate Reports (rua)" to (tags["rua"] ?: "not configured"),
            "Forensic Reports (ruf)" to (tags["ruf"] ?: "not configured"),
            "Report Format" to (tags["rf"] ?: "afrf"),
            "Report Interval" to "${tags["ri"] ?: "86400"}s",
            "Failure Options" to (tags["fo"] ?: "0"),
            "TTL" to "${dmarcRecords.first().ttl}s",
        )

        echo(formatter.formatDetail(details))
    }

    private fun parseDmarcTags(record: String): Map<String, String> {
        return record.split(";")
            .map { it.trim() }
            .filter { it.contains("=") }
            .associate { tag ->
                val (key, value) = tag.split("=", limit = 2)
                key.trim() to value.trim()
            }
    }
}

fun main(args: Array<String>) = DmarcLookupCommand().main(args)
