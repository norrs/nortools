package no.norrs.nortools.tools.dns.aaaa

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.Type

/**
 * AAAA Record Lookup tool — queries AAAA (IPv6) records for a domain.
 *
 * Uses RFC 3596 (DNS Extensions to Support IPv6).
 */
class AaaaLookupCommand : BaseCommand(
    name = "aaaa",
    helpText = "Look up AAAA (IPv6 address) records for a domain",
) {
    private val domain by argument(help = "Domain name to look up AAAA records for")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()

        val result = resolver.lookup(domain, Type.AAAA)

        if (!result.isSuccessful) {
            echo("AAAA lookup failed for $domain: ${result.status}")
            return
        }

        if (result.records.isEmpty()) {
            echo("No AAAA records found for $domain")
            return
        }

        val rows = result.records.map { record ->
            mapOf(
                "Address" to record.data,
                "TTL" to "${record.ttl}s",
            )
        }

        echo(formatter.format(rows))
    }
}

fun main(args: Array<String>) = AaaaLookupCommand().main(args)
