package no.norrs.nortools.tools.email.bimi

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.Type

/**
 * BIMI Record Lookup tool — queries BIMI (Brand Indicators for Message Identification) records.
 *
 * BIMI allows organizations to display their brand logo in email clients
 * that support it. Requires DMARC enforcement (p=quarantine or p=reject).
 * Uses the BIMI draft specification.
 */
class BimiLookupCommand : BaseCommand(
    name = "bimi",
    helpText = "Look up BIMI (Brand Indicators for Message Identification) records for a domain",
) {
    private val domain by argument(help = "Domain name to look up BIMI records for")
    private val selector by option("--selector", "-S", help = "BIMI selector (default: 'default')")
        .default("default")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()

        val bimiDomain = "$selector._bimi.$domain"
        val result = resolver.lookup(bimiDomain, Type.TXT)

        if (!result.isSuccessful) {
            echo("BIMI lookup failed for $bimiDomain: ${result.status}")
            return
        }

        val bimiRecords = result.records.filter { it.data.startsWith("v=BIMI1") }

        if (bimiRecords.isEmpty()) {
            echo("No BIMI records found for $domain (selector: $selector)")
            return
        }

        val bimiRecord = bimiRecords.first().data
        val tags = parseBimiTags(bimiRecord)

        val details = linkedMapOf<String, Any?>(
            "Domain" to domain,
            "Selector" to selector,
            "BIMI Domain" to bimiDomain,
            "BIMI Record" to bimiRecord,
            "Version" to (tags["v"] ?: "BIMI1"),
            "Logo URL (l)" to (tags["l"] ?: "not specified"),
            "Authority URL (a)" to (tags["a"] ?: "not specified"),
            "TTL" to "${bimiRecords.first().ttl}s",
        )

        echo(formatter.formatDetail(details))
    }

    private fun parseBimiTags(record: String): Map<String, String> {
        return record.split(";")
            .map { it.trim() }
            .filter { it.contains("=") }
            .associate { tag ->
                val (key, value) = tag.split("=", limit = 2)
                key.trim() to value.trim()
            }
    }
}

fun main(args: Array<String>) = BimiLookupCommand().main(args)
