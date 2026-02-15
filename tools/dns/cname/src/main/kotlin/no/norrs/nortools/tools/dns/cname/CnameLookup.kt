package no.norrs.nortools.tools.dns.cname

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.Type

/**
 * CNAME Record Lookup tool — queries CNAME (canonical name) records for a domain.
 *
 * Uses RFC 1035 (DNS).
 */
class CnameLookupCommand : BaseCommand(
    name = "cname",
    helpText = "Look up CNAME (canonical name) records for a domain",
) {
    private val domain by argument(help = "Domain name to look up CNAME records for")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()

        val result = resolver.lookup(domain, Type.CNAME)

        if (!result.isSuccessful) {
            echo("CNAME lookup failed for $domain: ${result.status}")
            return
        }

        if (result.records.isEmpty()) {
            echo("No CNAME records found for $domain")
            return
        }

        val rows = result.records.map { record ->
            mapOf(
                "Alias" to record.name,
                "Canonical Name" to record.data,
                "TTL" to "${record.ttl}s",
            )
        }

        echo(formatter.format(rows))
    }
}

fun main(args: Array<String>) = CnameLookupCommand().main(args)
