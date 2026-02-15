package no.norrs.nortools.tools.dns.txt

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.Type

/**
 * TXT Record Lookup tool — queries TXT records for a domain.
 *
 * Uses RFC 1035 (DNS). TXT records are used for SPF, DKIM, DMARC,
 * domain verification, and other purposes.
 */
class TxtLookupCommand : BaseCommand(
    name = "txt",
    helpText = "Look up TXT (text) records for a domain",
) {
    private val domain by argument(help = "Domain name to look up TXT records for")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()

        val result = resolver.lookup(domain, Type.TXT)

        if (!result.isSuccessful) {
            echo("TXT lookup failed for $domain: ${result.status}")
            return
        }

        if (result.records.isEmpty()) {
            echo("No TXT records found for $domain")
            return
        }

        val rows = result.records.map { record ->
            mapOf(
                "Record" to record.data,
                "TTL" to "${record.ttl}s",
            )
        }

        echo(formatter.format(rows))
    }
}

fun main(args: Array<String>) = TxtLookupCommand().main(args)
