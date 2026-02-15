package no.norrs.nortools.tools.email.tlsrpt

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.Type

/**
 * TLSRPT Record Lookup tool — queries SMTP TLS Reporting records for a domain.
 *
 * TLSRPT (SMTP TLS Reporting) allows domains to request reports about
 * TLS connectivity problems from sending MTAs.
 * Uses RFC 8460 (SMTP TLS Reporting).
 */
class TlsrptLookupCommand : BaseCommand(
    name = "tlsrpt",
    helpText = "Look up SMTP TLS Reporting (TLSRPT) records for a domain",
) {
    private val domain by argument(help = "Domain name to look up TLSRPT records for")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()

        val tlsrptDomain = "_smtp._tls.$domain"
        val result = resolver.lookup(tlsrptDomain, Type.TXT)

        if (!result.isSuccessful) {
            echo("TLSRPT lookup failed for $tlsrptDomain: ${result.status}")
            return
        }

        val tlsrptRecords = result.records.filter { it.data.startsWith("v=TLSRPTv1") }

        if (tlsrptRecords.isEmpty()) {
            echo("No TLSRPT records found for $domain")
            return
        }

        val tlsrptRecord = tlsrptRecords.first().data
        val tags = tlsrptRecord.split(";").associate { tag ->
            val parts = tag.trim().split("=", limit = 2)
            if (parts.size == 2) parts[0].trim() to parts[1].trim() else "" to ""
        }

        val details = linkedMapOf<String, Any?>(
            "Domain" to domain,
            "TLSRPT Domain" to tlsrptDomain,
            "TLSRPT Record" to tlsrptRecord,
            "Version" to (tags["v"] ?: "TLSRPTv1"),
            "Report URI(s)" to (tags["rua"] ?: "not specified"),
            "TTL" to "${tlsrptRecords.first().ttl}s",
        )

        echo(formatter.formatDetail(details))
    }
}

fun main(args: Array<String>) = TlsrptLookupCommand().main(args)
