package no.norrs.nortools.tools.util.dnspropagation

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import no.norrs.nortools.lib.cli.BaseCommand
import no.norrs.nortools.lib.dns.DnsResolver
import org.xbill.DNS.Type
import java.time.Duration

/**
 * DNS Propagation Check tool — checks DNS record propagation across multiple public resolvers.
 *
 * Queries the same record from multiple well-known DNS servers worldwide
 * to verify that DNS changes have propagated globally.
 */
class DnsPropagationCommand : BaseCommand(
    name = "dns-propagation",
    helpText = "Check DNS propagation across multiple public DNS servers worldwide",
) {
    private val domain by argument(help = "Domain name to check")
    private val type by option("--type", "-t", help = "Record type (A, AAAA, MX, TXT, CNAME, NS, SOA)")
        .default("A")

    private val publicDnsServers = listOf(
        "8.8.8.8" to "Google (Primary)",
        "8.8.4.4" to "Google (Secondary)",
        "1.1.1.1" to "Cloudflare (Primary)",
        "1.0.0.1" to "Cloudflare (Secondary)",
        "9.9.9.9" to "Quad9",
        "208.67.222.222" to "OpenDNS (Primary)",
        "208.67.220.220" to "OpenDNS (Secondary)",
        "185.228.168.9" to "CleanBrowsing",
        "76.76.19.19" to "Alternate DNS",
        "94.140.14.14" to "AdGuard DNS",
        "77.88.8.8" to "Yandex DNS",
        "156.154.70.1" to "Neustar (Primary)",
    )

    override fun run() {
        val formatter = createFormatter()
        val recordType = Type.value(type.uppercase())

        if (recordType == -1) {
            echo("Unknown record type: $type")
            return
        }

        val rows = publicDnsServers.map { (server, name) ->
            try {
                val resolver = DnsResolver(server = server, timeout = Duration.ofSeconds(timeoutSeconds.toLong()))
                val result = resolver.lookup(domain, recordType)

                val records = if (result.isSuccessful && result.records.isNotEmpty()) {
                    result.records.joinToString(", ") { it.data }
                } else {
                    "No records"
                }

                mapOf(
                    "Server" to server,
                    "Provider" to name,
                    "Status" to if (result.isSuccessful) "OK" else result.status,
                    "Records" to records,
                    "TTL" to if (result.records.isNotEmpty()) "${result.records.first().ttl}s" else "-",
                )
            } catch (e: Exception) {
                mapOf(
                    "Server" to server,
                    "Provider" to name,
                    "Status" to "ERROR",
                    "Records" to (e.message ?: "Unknown error"),
                    "TTL" to "-",
                )
            }
        }

        echo("DNS Propagation Check: $domain ($type)")
        echo()
        echo(formatter.format(rows))

        // Summary
        val okCount = rows.count { it["Status"] == "OK" }
        val uniqueRecords = rows.filter { it["Status"] == "OK" }.map { it["Records"] }.distinct()
        echo()
        echo("Propagation: $okCount/${publicDnsServers.size} servers responding")
        if (uniqueRecords.size > 1) {
            echo("WARNING: Inconsistent results detected — ${uniqueRecords.size} different responses")
        } else if (uniqueRecords.size == 1) {
            echo("All responding servers return consistent results")
        }
    }
}

fun main(args: Array<String>) = DnsPropagationCommand().main(args)
