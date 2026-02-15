package no.norrs.nortools.tools.blocklist.ipseckey

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.IPSECKEYRecord
import org.xbill.DNS.Section
import org.xbill.DNS.Type

/**
 * IPSECKEY Record Lookup tool — queries DNS IPSECKEY records for a domain.
 *
 * IPSECKEY records store IPsec keying material in DNS for opportunistic encryption.
 * Uses RFC 4025 (A Method for Storing IPsec Keying Material in DNS).
 */
class IpseckeyLookupCommand : BaseCommand(
    name = "ipseckey",
    helpText = "Look up DNS IPSECKEY records for a domain or IP",
) {
    private val query by argument(help = "Domain name or reverse IP to look up IPSECKEY records for")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()

        val response = resolver.rawQuery(query, Type.IPSECKEY)
        val ipsecRecords = response.getSection(Section.ANSWER)
            .filterIsInstance<IPSECKEYRecord>()

        if (ipsecRecords.isEmpty()) {
            echo("No IPSECKEY records found for $query")
            return
        }

        val rows = ipsecRecords.map { record ->
            val gatewayType = when (record.gatewayType) {
                0 -> "No gateway"
                1 -> "IPv4"
                2 -> "IPv6"
                3 -> "Domain name"
                else -> "Unknown (${record.gatewayType})"
            }

            val algorithm = when (record.algorithmType) {
                0 -> "No key"
                1 -> "DSA"
                2 -> "RSA"
                3 -> "ECDSA"
                else -> "Algorithm ${record.algorithmType}"
            }

            mapOf(
                "Precedence" to "${record.precedence}",
                "Gateway Type" to gatewayType,
                "Algorithm" to algorithm,
                "Gateway" to (record.gateway?.toString() ?: "none"),
                "Key" to (record.key?.take(40)?.joinToString("") { "%02x".format(it) }?.plus("...") ?: "none"),
                "TTL" to "${record.ttl}s",
            )
        }

        echo(formatter.format(rows))
    }
}

fun main(args: Array<String>) = IpseckeyLookupCommand().main(args)
