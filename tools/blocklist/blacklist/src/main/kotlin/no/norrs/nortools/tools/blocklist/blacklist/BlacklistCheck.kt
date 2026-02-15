package no.norrs.nortools.tools.blocklist.blacklist

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.Type

/**
 * Blacklist (DNSBL) Check tool — checks an IP address against DNS-based blacklists.
 *
 * Queries multiple DNSBL providers by reversing the IP and querying A records.
 * A positive result (A record returned) means the IP is listed.
 * Uses RFC 5782 (DNS Blacklists and Whitelists).
 */
class BlacklistCheckCommand : BaseCommand(
    name = "blacklist",
    helpText = "Check an IP address against DNS-based blacklists (DNSBL)",
) {
    private val ip by argument(help = "IP address to check against blacklists")

    private val dnsbls = listOf(
        "zen.spamhaus.org",
        "bl.spamcop.net",
        "b.barracudacentral.org",
        "dnsbl.sorbs.net",
        "spam.dnsbl.sorbs.net",
        "dul.dnsbl.sorbs.net",
        "dnsbl-1.uceprotect.net",
        "dnsbl-2.uceprotect.net",
        "dnsbl-3.uceprotect.net",
        "psbl.surriel.com",
        "dyna.spamrats.com",
        "noptr.spamrats.com",
        "spam.spamrats.com",
        "cbl.abuseat.org",
        "dnsbl.dronebl.org",
        "rbl.interserver.net",
        "db.wpbl.info",
        "all.s5h.net",
        "bl.mailspike.net",
    )

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()

        val reversed = ip.split(".").reversed().joinToString(".")

        val results = dnsbls.map { dnsbl ->
            val query = "$reversed.$dnsbl"
            val result = resolver.lookup(query, Type.A)
            val listed = result.isSuccessful && result.records.isNotEmpty()

            // Get TXT record for reason if listed
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
                "DNSBL" to dnsbl,
                "Status" to if (listed) "LISTED" else "OK",
                "Response" to if (listed) result.records.first().data else "",
                "Reason" to reason,
            )
        }

        val listedCount = results.count { it["Status"] == "LISTED" }
        echo("IP: $ip — Checked ${dnsbls.size} blacklists, listed on $listedCount")
        echo()
        echo(formatter.format(results))
    }
}

fun main(args: Array<String>) = BlacklistCheckCommand().main(args)
