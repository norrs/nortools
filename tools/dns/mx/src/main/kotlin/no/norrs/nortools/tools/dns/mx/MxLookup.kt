package no.norrs.nortools.tools.dns.mx

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.Type

/**
 * MX Lookup tool — queries MX records for a domain.
 *
 * Equivalent to MXToolbox "MX Lookup".
 * Uses RFC 1035 (DNS) and RFC 7505 (Null MX).
 */
class MxLookupCommand : BaseCommand(
    name = "mx",
    helpText = "Look up MX (Mail Exchange) records for a domain",
) {
    private val domain by argument(help = "Domain name to look up MX records for")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()

        val result = resolver.lookup(domain, Type.MX)

        if (!result.isSuccessful) {
            echo("MX lookup failed for $domain: ${result.status}")
            return
        }

        if (result.records.isEmpty()) {
            echo("No MX records found for $domain")
            return
        }

        val rows = result.records.map { record ->
            val parts = record.data.split(" ", limit = 2)
            mapOf(
                "Preference" to (parts.getOrNull(0) ?: ""),
                "Hostname" to (parts.getOrNull(1) ?: record.data),
                "TTL" to "${record.ttl}s",
            )
        }.sortedBy { (it["Preference"] ?: "0").toIntOrNull() ?: 0 }

        echo(formatter.format(rows))
    }
}

fun main(args: Array<String>) = MxLookupCommand().main(args)
