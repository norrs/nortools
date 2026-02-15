package no.norrs.nortools.tools.email.spf

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.Type

/**
 * SPF Record Lookup tool — queries and parses SPF records for a domain.
 *
 * SPF (Sender Policy Framework) records specify which mail servers are
 * authorized to send email on behalf of a domain.
 * Uses RFC 7208 (Sender Policy Framework).
 */
class SpfLookupCommand : BaseCommand(
    name = "spf",
    helpText = "Look up and analyze SPF records for a domain",
) {
    private val domain by argument(help = "Domain name to look up SPF records for")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()

        val result = resolver.lookup(domain, Type.TXT)

        if (!result.isSuccessful) {
            echo("SPF lookup failed for $domain: ${result.status}")
            return
        }

        val spfRecords = result.records.filter { it.data.startsWith("v=spf1") }

        if (spfRecords.isEmpty()) {
            echo("No SPF records found for $domain")
            return
        }

        if (spfRecords.size > 1) {
            echo("WARNING: Multiple SPF records found (RFC 7208 violation)")
        }

        val spfRecord = spfRecords.first().data
        val mechanisms = parseSpfMechanisms(spfRecord)

        val details = linkedMapOf<String, Any?>(
            "Domain" to domain,
            "SPF Record" to spfRecord,
            "TTL" to "${spfRecords.first().ttl}s",
        )

        echo(formatter.formatDetail(details))
        echo()

        if (mechanisms.isNotEmpty()) {
            echo(formatter.format(mechanisms))
        }
    }

    private fun parseSpfMechanisms(spf: String): List<Map<String, String>> {
        val parts = spf.split(" ").drop(1) // Skip "v=spf1"
        return parts.map { part ->
            val qualifier = when {
                part.startsWith("+") -> "Pass"
                part.startsWith("-") -> "Fail"
                part.startsWith("~") -> "SoftFail"
                part.startsWith("?") -> "Neutral"
                else -> "Pass" // Default qualifier
            }
            val mechanism = part.trimStart('+', '-', '~', '?')
            val (type, value) = when {
                mechanism.startsWith("include:") -> "include" to mechanism.removePrefix("include:")
                mechanism.startsWith("a:") -> "a" to mechanism.removePrefix("a:")
                mechanism.startsWith("mx:") -> "mx" to mechanism.removePrefix("mx:")
                mechanism.startsWith("ip4:") -> "ip4" to mechanism.removePrefix("ip4:")
                mechanism.startsWith("ip6:") -> "ip6" to mechanism.removePrefix("ip6:")
                mechanism.startsWith("redirect=") -> "redirect" to mechanism.removePrefix("redirect=")
                mechanism.startsWith("exists:") -> "exists" to mechanism.removePrefix("exists:")
                mechanism == "a" -> "a" to domain
                mechanism == "mx" -> "mx" to domain
                mechanism == "all" -> "all" to ""
                mechanism == "ptr" -> "ptr" to "(deprecated)"
                else -> mechanism to ""
            }
            mapOf(
                "Qualifier" to qualifier,
                "Mechanism" to type,
                "Value" to value,
            )
        }
    }
}

fun main(args: Array<String>) = SpfLookupCommand().main(args)
