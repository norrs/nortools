package no.norrs.nortools.tools.dnssec.nsec

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.NSECRecord
import org.xbill.DNS.Section
import org.xbill.DNS.Type

/**
 * NSEC Record Lookup tool — queries NSEC (Next Secure) records for a domain.
 *
 * NSEC records prove the non-existence of a DNS name or type in DNSSEC.
 * They list the next domain name in the zone and the record types that exist.
 * Uses RFC 4034 (DNSSEC Resource Records).
 */
class NsecLookupCommand : BaseCommand(
    name = "nsec",
    helpText = "Look up NSEC (Next Secure) records for a domain",
) {
    private val domain by argument(help = "Domain name to look up NSEC records for")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()

        // NSEC records are typically returned in the AUTHORITY section
        // when querying for a non-existent name, or in ANSWER for direct queries
        val response = resolver.dnssecQuery(domain, Type.NSEC)
        val nsecRecords = mutableListOf<NSECRecord>()

        // Check ANSWER section
        nsecRecords.addAll(
            response.getSection(Section.ANSWER).filterIsInstance<NSECRecord>()
        )

        // Also check AUTHORITY section (NSEC often appears here)
        nsecRecords.addAll(
            response.getSection(Section.AUTHORITY).filterIsInstance<NSECRecord>()
        )

        if (nsecRecords.isEmpty()) {
            echo("No NSEC records found for $domain")
            return
        }

        val rows = nsecRecords.map { record ->
            val types = record.types.joinToString(", ") { Type.string(it) }
            mapOf(
                "Name" to record.name.toString(),
                "Next Domain" to record.next.toString(),
                "Types" to types,
                "TTL" to "${record.ttl}s",
            )
        }

        echo(formatter.format(rows))
    }
}

fun main(args: Array<String>) = NsecLookupCommand().main(args)
