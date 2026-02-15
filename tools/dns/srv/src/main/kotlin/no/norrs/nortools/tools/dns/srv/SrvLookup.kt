package no.norrs.nortools.tools.dns.srv

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.Type

/**
 * SRV Record Lookup tool — queries SRV (Service) records for a domain.
 *
 * Uses RFC 2782 (DNS SRV RR).
 * SRV records specify the location of services (e.g., _sip._tcp.example.com).
 */
class SrvLookupCommand : BaseCommand(
    name = "srv",
    helpText = "Look up SRV (Service) records for a domain",
) {
    private val domain by argument(
        help = "Service domain to look up (e.g., _sip._tcp.example.com)",
    )

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()

        val result = resolver.lookup(domain, Type.SRV)

        if (!result.isSuccessful) {
            echo("SRV lookup failed for $domain: ${result.status}")
            return
        }

        if (result.records.isEmpty()) {
            echo("No SRV records found for $domain")
            return
        }

        val rows = result.records.map { record ->
            val parts = record.data.split(" ", limit = 4)
            mapOf(
                "Priority" to (parts.getOrNull(0) ?: ""),
                "Weight" to (parts.getOrNull(1) ?: ""),
                "Port" to (parts.getOrNull(2) ?: ""),
                "Target" to (parts.getOrNull(3) ?: record.data),
                "TTL" to "${record.ttl}s",
            )
        }.sortedWith(compareBy({ (it["Priority"] ?: "0").toIntOrNull() ?: 0 }, { (it["Weight"] ?: "0").toIntOrNull() ?: 0 }))

        echo(formatter.format(rows))
    }
}

fun main(args: Array<String>) = SrvLookupCommand().main(args)
