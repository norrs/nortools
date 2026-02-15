package no.norrs.nortools.tools.dns.soa

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.SOARecord
import org.xbill.DNS.Type

/**
 * SOA Record Lookup tool — queries SOA (Start of Authority) records for a domain.
 *
 * Uses RFC 1035 (DNS).
 */
class SoaLookupCommand : BaseCommand(
    name = "soa",
    helpText = "Look up SOA (Start of Authority) records for a domain",
) {
    private val domain by argument(help = "Domain name to look up SOA records for")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()

        val result = resolver.lookup(domain, Type.SOA)

        if (!result.isSuccessful) {
            echo("SOA lookup failed for $domain: ${result.status}")
            return
        }

        if (result.records.isEmpty()) {
            echo("No SOA records found for $domain")
            return
        }

        // SOA has structured fields, show as detail view
        val data = linkedMapOf<String, Any?>(
            "Domain" to domain,
            "Primary NS" to result.records.first().data.split(" ").getOrNull(0),
            "Responsible" to result.records.first().data.split(" ").getOrNull(1),
            "Serial" to result.records.first().data.split(" ").getOrNull(2),
            "Refresh" to result.records.first().data.split(" ").getOrNull(3)?.let { "${it}s" },
            "Retry" to result.records.first().data.split(" ").getOrNull(4)?.let { "${it}s" },
            "Expire" to result.records.first().data.split(" ").getOrNull(5)?.let { "${it}s" },
            "Minimum TTL" to result.records.first().data.split(" ").getOrNull(6)?.let { "${it}s" },
            "TTL" to "${result.records.first().ttl}s",
        )

        echo(formatter.formatDetail(data))
    }
}

fun main(args: Array<String>) = SoaLookupCommand().main(args)
