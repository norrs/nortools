package no.norrs.nortools.tools.dnssec.rrsig

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.DNSSEC
import org.xbill.DNS.RRSIGRecord
import org.xbill.DNS.Section
import org.xbill.DNS.Type
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * RRSIG Record Lookup tool — queries RRSIG (DNSSEC Signature) records for a domain.
 *
 * RRSIG records contain digital signatures for DNS record sets,
 * used to verify authenticity in DNSSEC.
 * Uses RFC 4034 (DNSSEC Resource Records).
 */
class RrsigLookupCommand : BaseCommand(
    name = "rrsig",
    helpText = "Look up RRSIG (DNSSEC signature) records for a domain",
) {
    private val domain by argument(help = "Domain name to look up RRSIG records for")
    private val recordType by option("--type", "-T", help = "Filter by covered record type (e.g., A, AAAA, MX)")
        .default("")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()

        // Query for the type we want signatures for, or DNSKEY to get RRSIG records
        val queryType = if (recordType.isNotEmpty()) {
            Type.value(recordType)
        } else {
            Type.DNSKEY
        }

        val response = resolver.dnssecQuery(domain, queryType)
        val rrsigRecords = response.getSection(Section.ANSWER)
            .filterIsInstance<RRSIGRecord>()

        if (rrsigRecords.isEmpty()) {
            echo("No RRSIG records found for $domain")
            return
        }

        val dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
            .withZone(ZoneId.of("UTC"))

        val rows = rrsigRecords.map { record ->
            val algName = DNSSEC.Algorithm.string(record.algorithm)
            mapOf(
                "Type Covered" to Type.string(record.typeCovered),
                "Algorithm" to "${record.algorithm} ($algName)",
                "Labels" to "${record.labels}",
                "Original TTL" to "${record.origTTL}s",
                "Expiration" to dtf.format(record.expire),
                "Inception" to dtf.format(record.timeSigned),
                "Key Tag" to "${record.footprint}",
                "Signer" to record.signer.toString(),
                "TTL" to "${record.ttl}s",
            )
        }

        echo(formatter.format(rows))
    }
}

fun main(args: Array<String>) = RrsigLookupCommand().main(args)
