package no.norrs.nortools.tools.dnssec.nsec3param

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.NSEC3PARAMRecord
import org.xbill.DNS.Section
import org.xbill.DNS.Type

/**
 * NSEC3PARAM Record Lookup tool — queries NSEC3PARAM records for a domain.
 *
 * NSEC3PARAM records define the parameters used for NSEC3 hashed denial
 * of existence in DNSSEC. They specify the hash algorithm, flags,
 * iterations, and salt used to generate NSEC3 records.
 * Uses RFC 5155 (DNS Security (DNSSEC) Hashed Authenticated Denial of Existence).
 */
class Nsec3paramLookupCommand : BaseCommand(
    name = "nsec3param",
    helpText = "Look up NSEC3PARAM records for a domain",
) {
    private val domain by argument(help = "Domain name to look up NSEC3PARAM records for")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()

        val result = resolver.dnssecLookup(domain, Type.NSEC3PARAM)

        if (!result.isSuccessful) {
            echo("NSEC3PARAM lookup failed for $domain: ${result.status}")
            return
        }

        // Re-query for detailed parsing
        val response = resolver.dnssecQuery(domain, Type.NSEC3PARAM)
        val nsec3paramRecords = response.getSection(Section.ANSWER)
            .filterIsInstance<NSEC3PARAMRecord>()

        if (nsec3paramRecords.isEmpty()) {
            echo("No NSEC3PARAM records found for $domain")
            return
        }

        val rows = nsec3paramRecords.map { record ->
            val hashAlgName = when (record.hashAlgorithm) {
                1 -> "SHA-1"
                else -> "Unknown (${record.hashAlgorithm})"
            }
            val saltHex = record.salt?.joinToString("") { "%02X".format(it) } ?: "-"
            mapOf(
                "Hash Algorithm" to "${record.hashAlgorithm} ($hashAlgName)",
                "Flags" to "${record.flags}",
                "Iterations" to "${record.iterations}",
                "Salt" to saltHex,
                "TTL" to "${record.ttl}s",
            )
        }

        echo(formatter.format(rows))
    }
}

fun main(args: Array<String>) = Nsec3paramLookupCommand().main(args)
