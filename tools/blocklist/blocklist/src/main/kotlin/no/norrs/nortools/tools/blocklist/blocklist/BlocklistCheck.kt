package no.norrs.nortools.tools.blocklist.blocklist

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.Type

/**
 * Domain Blocklist Check tool — checks a domain against URI/domain-based blocklists.
 *
 * Queries URIBL/SURBL/DBL providers to check if a domain is listed.
 * Uses RFC 5782 (DNS Blacklists and Whitelists).
 */
class BlocklistCheckCommand : BaseCommand(
    name = "blocklist",
    helpText = "Check a domain against URI/domain-based blocklists",
) {
    private val domain by argument(help = "Domain name to check against blocklists")

    private val uribls = listOf(
        "dbl.spamhaus.org",
        "multi.surbl.org",
        "multi.uribl.com",
        "black.uribl.com",
        "rhsbl.sorbs.net",
        "dnsbl.sorbs.net",
        "nomail.rhsbl.sorbs.net",
        "fresh.spameatingmonkey.net",
        "fresh15.spameatingmonkey.net",
        "uribl.spameatingmonkey.net",
    )

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()

        val results = uribls.map { uribl ->
            val query = "$domain.$uribl"
            val result = resolver.lookup(query, Type.A)
            val listed = result.isSuccessful && result.records.isNotEmpty()

            val reason = if (listed) {
                val txtResult = resolver.lookup(query, Type.TXT)
                if (txtResult.isSuccessful && txtResult.records.isNotEmpty()) {
                    txtResult.records.first().data
                } else {
                    ""
                }
            } else {
                ""
            }

            mapOf(
                "Blocklist" to uribl,
                "Status" to if (listed) "LISTED" else "OK",
                "Response" to if (listed) result.records.first().data else "",
                "Reason" to reason,
            )
        }

        val listedCount = results.count { it["Status"] == "LISTED" }
        echo("Domain: $domain — Checked ${uribls.size} blocklists, listed on $listedCount")
        echo()
        echo(formatter.format(results))
    }
}

fun main(args: Array<String>) = BlocklistCheckCommand().main(args)
