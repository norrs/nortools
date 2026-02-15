package no.norrs.nortools.tools.blocklist.loc

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.LOCRecord
import org.xbill.DNS.Section
import org.xbill.DNS.Type

/**
 * LOC Record Lookup tool — queries DNS LOC (location) records for a domain.
 *
 * LOC records express geographic location information in DNS.
 * Uses RFC 1876 (A Means for Expressing Location Information in the DNS).
 */
class LocLookupCommand : BaseCommand(
    name = "loc",
    helpText = "Look up DNS LOC (geographic location) records for a domain",
) {
    private val domain by argument(help = "Domain name to look up LOC records for")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()

        val response = resolver.rawQuery(domain, Type.LOC)
        val locRecords = response.getSection(Section.ANSWER)
            .filterIsInstance<LOCRecord>()

        if (locRecords.isEmpty()) {
            echo("No LOC records found for $domain")
            return
        }

        for (record in locRecords) {
            val details = linkedMapOf<String, Any?>(
                "Domain" to domain,
                "Latitude" to record.latitude,
                "Longitude" to record.longitude,
                "Altitude" to "${record.altitude}m",
                "Size" to "${record.size}m",
                "Horizontal Precision" to "${record.hPrecision}m",
                "Vertical Precision" to "${record.vPrecision}m",
                "TTL" to "${record.ttl}s",
                "Raw" to record.rdataToString(),
            )
            echo(formatter.formatDetail(details))
        }
    }
}

fun main(args: Array<String>) = LocLookupCommand().main(args)
