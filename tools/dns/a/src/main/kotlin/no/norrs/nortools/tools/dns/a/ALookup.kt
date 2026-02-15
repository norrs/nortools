package no.norrs.nortools.tools.dns.a

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.Type

/**
 * A Record Lookup tool — queries A (IPv4) records for a domain.
 *
 * Uses RFC 1035 (DNS).
 */
class ALookupCommand : BaseCommand(
    name = "a",
    helpText = "Look up A (IPv4 address) records for a domain",
) {
    private val domain by argument(help = "Domain name to look up A records for")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()

        val result = resolver.lookup(domain, Type.A)

        if (!result.isSuccessful) {
            echo("A lookup failed for $domain: ${result.status}")
            return
        }

        if (result.records.isEmpty()) {
            echo("No A records found for $domain")
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

fun main(args: Array<String>) = ALookupCommand().main(args)
