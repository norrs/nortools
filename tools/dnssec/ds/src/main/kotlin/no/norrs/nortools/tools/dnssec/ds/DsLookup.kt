package no.norrs.nortools.tools.dnssec.ds

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.DNSSEC
import org.xbill.DNS.DSRecord
import org.xbill.DNS.Section
import org.xbill.DNS.Type

/**
 * DS Record Lookup tool — queries DS (Delegation Signer) records for a domain.
 *
 * DS records are used to link a child zone's DNSKEY to the parent zone,
 * forming the chain of trust in DNSSEC.
 * Uses RFC 4034 (DNSSEC Resource Records).
 */
class DsLookupCommand : BaseCommand(
    name = "ds",
    helpText = "Look up DS (Delegation Signer) records for a domain",
) {
    private val domain by argument(help = "Domain name to look up DS records for")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()

        val result = resolver.dnssecLookup(domain, Type.DS)

        if (!result.isSuccessful) {
            echo("DS lookup failed for $domain: ${result.status}")
            return
        }

        if (result.records.isEmpty()) {
            echo("No DS records found for $domain")
            return
        }

        // Re-query for detailed parsing
        val response = resolver.dnssecQuery(domain, Type.DS)
        val dsRecords = response.getSection(Section.ANSWER)
            .filterIsInstance<DSRecord>()

        if (dsRecords.isEmpty()) {
            echo("No DS records found for $domain")
            return
        }

        val rows = dsRecords.map { record ->
            val algName = DNSSEC.Algorithm.string(record.algorithm)
            val digestTypeName = when (record.digestID) {
                DSRecord.Digest.SHA1 -> "SHA-1"
                DSRecord.Digest.SHA256 -> "SHA-256"
                DSRecord.Digest.SHA384 -> "SHA-384"
                else -> "Type ${record.digestID}"
            }
            mapOf(
                "Key Tag" to "${record.footprint}",
                "Algorithm" to "${record.algorithm} ($algName)",
                "Digest Type" to "${record.digestID} ($digestTypeName)",
                "Digest" to record.digest.joinToString("") { "%02X".format(it) },
                "TTL" to "${record.ttl}s",
            )
        }

        echo(formatter.format(rows))
    }
}

fun main(args: Array<String>) = DsLookupCommand().main(args)
