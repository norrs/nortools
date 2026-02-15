package no.norrs.nortools.tools.dns.ns

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.Type

/**
 * NS Record Lookup tool — queries NS (Name Server) records for a domain.
 *
 * Uses RFC 1035 (DNS).
 */
class NsLookupCommand : BaseCommand(
    name = "ns",
    helpText = "Look up NS (Name Server) records for a domain",
) {
    private val domain by argument(help = "Domain name to look up NS records for")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()

        val result = resolver.lookup(domain, Type.NS)

        if (!result.isSuccessful) {
            echo("NS lookup failed for $domain: ${result.status}")
            return
        }

        if (result.records.isEmpty()) {
            echo("No NS records found for $domain")
            return
        }

        val rows = result.records.map { record ->
            mapOf(
                "Name Server" to record.data,
                "TTL" to "${record.ttl}s",
            )
        }

        echo(formatter.format(rows))
    }
}

fun main(args: Array<String>) = NsLookupCommand().main(args)
