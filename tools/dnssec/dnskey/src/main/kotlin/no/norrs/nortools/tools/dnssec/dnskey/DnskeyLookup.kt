package no.norrs.nortools.tools.dnssec.dnskey

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.DNSKEYRecord
import org.xbill.DNS.DNSSEC
import org.xbill.DNS.Section
import org.xbill.DNS.Type

/**
 * DNSKEY Record Lookup tool — queries DNSKEY records for a domain.
 *
 * DNSKEY records hold public keys used for DNSSEC validation.
 * Uses RFC 4034 (DNSSEC Resource Records) and RFC 5702 (SHA-2 in DNSSEC).
 */
class DnskeyLookupCommand : BaseCommand(
    name = "dnskey",
    helpText = "Look up DNSKEY (DNSSEC public key) records for a domain",
) {
    private val domain by argument(help = "Domain name to look up DNSKEY records for")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()

        val result = resolver.dnssecLookup(domain, Type.DNSKEY)

        if (!result.isSuccessful) {
            echo("DNSKEY lookup failed for $domain: ${result.status}")
            return
        }

        if (result.records.isEmpty()) {
            echo("No DNSKEY records found for $domain")
            return
        }

        // Re-query to get the raw records for detailed parsing
        val response = resolver.dnssecQuery(domain, Type.DNSKEY)
        val dnskeyRecords = response.getSection(Section.ANSWER)
            .filterIsInstance<DNSKEYRecord>()

        if (dnskeyRecords.isEmpty()) {
            echo("No DNSKEY records found for $domain")
            return
        }

        val rows = dnskeyRecords.map { record ->
            val flagDesc = when {
                record.flags == DNSKEYRecord.Flags.ZONE_KEY or DNSKEYRecord.Flags.SEP_KEY -> "KSK (Key Signing Key)"
                record.flags == DNSKEYRecord.Flags.ZONE_KEY -> "ZSK (Zone Signing Key)"
                else -> "Flags: ${record.flags}"
            }
            val algName = DNSSEC.Algorithm.string(record.algorithm)
            mapOf(
                "Flags" to "${record.flags} ($flagDesc)",
                "Protocol" to "${record.protocol}",
                "Algorithm" to "${record.algorithm} ($algName)",
                "Key Tag" to "${record.footprint}",
                "Key Length" to "${record.key.size * 8} bits",
                "TTL" to "${record.ttl}s",
            )
        }

        echo(formatter.format(rows))
    }
}

fun main(args: Array<String>) = DnskeyLookupCommand().main(args)
