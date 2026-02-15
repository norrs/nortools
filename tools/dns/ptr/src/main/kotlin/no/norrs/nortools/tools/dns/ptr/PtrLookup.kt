package no.norrs.nortools.tools.dns.ptr

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand

/**
 * PTR Record Lookup tool — performs reverse DNS lookup for an IP address.
 *
 * Uses RFC 1035 (DNS) and RFC 2317 (Classless IN-ADDR.ARPA delegation).
 */
class PtrLookupCommand : BaseCommand(
    name = "ptr",
    helpText = "Perform reverse DNS (PTR) lookup for an IP address",
) {
    private val ip by argument(help = "IP address to perform reverse DNS lookup for")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()

        val result = resolver.reverseLookup(ip)

        if (!result.isSuccessful) {
            echo("PTR lookup failed for $ip: ${result.status}")
            return
        }

        if (result.records.isEmpty()) {
            echo("No PTR records found for $ip")
            return
        }

        val rows = result.records.map { record ->
            mapOf(
                "IP Address" to ip,
                "Hostname" to record.data,
                "TTL" to "${record.ttl}s",
            )
        }

        echo(formatter.format(rows))
    }
}

fun main(args: Array<String>) = PtrLookupCommand().main(args)
