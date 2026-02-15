package no.norrs.nortools.tools.whois.asn

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.google.gson.JsonParser
import no.norrs.nortools.lib.cli.BaseCommand
import no.norrs.nortools.lib.network.HttpClient
import org.xbill.DNS.Type
import java.time.Duration

/**
 * ASN Lookup tool — queries Autonomous System Number information.
 *
 * Looks up ASN details using DNS TXT records at origin.asn.cymru.com
 * and RDAP for additional details.
 * Based on RFC 6483 (RPKI validation) and Team Cymru's DNS service.
 */
class AsnLookupCommand : BaseCommand(
    name = "asn",
    helpText = "Look up Autonomous System Number (ASN) information",
) {
    private val query by argument(help = "ASN number (e.g., AS13335) or IP address")

    override fun run() {
        val formatter = createFormatter()

        if (query.uppercase().startsWith("AS") || query.all { it.isDigit() }) {
            lookupAsn(query.removePrefix("AS").removePrefix("as"), formatter)
        } else {
            lookupIpAsn(query, formatter)
        }
    }

    private fun lookupAsn(asnNumber: String, formatter: no.norrs.nortools.lib.output.OutputFormatter) {
        val details = linkedMapOf<String, Any?>("ASN" to "AS$asnNumber")

        // Query Team Cymru DNS for ASN info
        val resolver = createResolver()
        val dnsQuery = "AS$asnNumber.asn.cymru.com"
        val result = resolver.lookup(dnsQuery, Type.TXT)

        if (result.isSuccessful && result.records.isNotEmpty()) {
            val txt = result.records.first().data
            // Format: ASN | CC | Registry | Allocated | AS Name
            val parts = txt.split("|").map { it.trim() }
            if (parts.size >= 5) {
                details["Country"] = parts[1]
                details["Registry"] = parts[2]
                details["Allocated"] = parts[3]
                details["AS Name"] = parts[4]
            }
        }

        // Try RDAP for more details
        try {
            val httpClient = HttpClient(timeout = Duration.ofSeconds(timeoutSeconds.toLong()))
            val rdapResult = httpClient.get("https://rdap.arin.net/registry/autnum/$asnNumber", includeBody = true)
            if (rdapResult.statusCode == 200 && rdapResult.body != null) {
                val json = JsonParser.parseString(rdapResult.body).asJsonObject
                val name = json.get("name")?.asString
                if (name != null) details["Name"] = name
                val type = json.get("type")?.asString
                if (type != null) details["Type"] = type
            }
        } catch (_: Exception) {
            // RDAP lookup is optional
        }

        echo(formatter.formatDetail(details))
    }

    private fun lookupIpAsn(ip: String, formatter: no.norrs.nortools.lib.output.OutputFormatter) {
        val resolver = createResolver()

        // Reverse the IP for DNS query
        val reversed = ip.split(".").reversed().joinToString(".")
        val dnsQuery = "$reversed.origin.asn.cymru.com"
        val result = resolver.lookup(dnsQuery, Type.TXT)

        val details = linkedMapOf<String, Any?>("IP" to ip)

        if (result.isSuccessful && result.records.isNotEmpty()) {
            for (record in result.records) {
                val txt = record.data
                // Format: ASN | IP/Prefix | CC | Registry | Allocated
                val parts = txt.split("|").map { it.trim() }
                if (parts.size >= 5) {
                    details["ASN"] = "AS${parts[0]}"
                    details["Prefix"] = parts[1]
                    details["Country"] = parts[2]
                    details["Registry"] = parts[3]
                    details["Allocated"] = parts[4]

                    // Look up ASN name
                    val asnQuery = "AS${parts[0]}.asn.cymru.com"
                    val asnResult = resolver.lookup(asnQuery, Type.TXT)
                    if (asnResult.isSuccessful && asnResult.records.isNotEmpty()) {
                        val asnTxt = asnResult.records.first().data
                        val asnParts = asnTxt.split("|").map { it.trim() }
                        if (asnParts.size >= 5) {
                            details["AS Name"] = asnParts[4]
                        }
                    }
                    break // Use first result
                }
            }
        } else {
            details["Error"] = "No ASN information found for $ip"
        }

        echo(formatter.formatDetail(details))
    }
}

fun main(args: Array<String>) = AsnLookupCommand().main(args)
