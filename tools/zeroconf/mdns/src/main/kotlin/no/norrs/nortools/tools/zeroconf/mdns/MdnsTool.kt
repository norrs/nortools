package no.norrs.nortools.tools.zeroconf.mdns

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import no.norrs.nortools.lib.cli.BaseCommand
import no.norrs.nortools.lib.zeroconf.IpFamily
import no.norrs.nortools.lib.zeroconf.MdnsClient
import no.norrs.nortools.lib.zeroconf.MdnsRecord
import java.time.Duration

class MdnsCommand : BaseCommand(
    name = "mdns",
    helpText = "Debug mDNS queries and passive UDP 5353 traffic",
) {
    private val query by option("--query", "-q", help = "mDNS name to query, for example _services._dns-sd._udp.local")
    private val recordType by option("--type", help = "DNS record type to query")
        .default("PTR")
    private val listen by option("--listen", help = "Listen passively for mDNS packets on UDP 5353").flag()
    private val ipFamilyValue by option("--ip-family", help = "IP family: ipv4, ipv6, or both")
        .default("ipv4")
    private val maxPackets by option("--max-packets", help = "Maximum packets to collect")
        .int()
        .default(25)
    private val bindAddress by option(
        "--bind-address",
        help = "Local IPv4 address to bind. Defaults to all IPv4 interfaces.",
    )

    override fun run() {
        val formatter = createFormatter()
        val ipFamily = try {
            IpFamily.fromCli(ipFamilyValue)
        } catch (_: IllegalArgumentException) {
            echo("Invalid --ip-family '$ipFamilyValue'. Expected ipv4, ipv6, or both.")
            return
        }

        if (!ipFamily.allowsIpv4()) {
            echo(
                formatter.formatDetail(
                    linkedMapOf(
                        "Protocol" to "mDNS",
                        "Status" to "Unsupported IP family",
                        "Requested IP Family" to ipFamilyValue,
                        "Reason" to "This first mDNS slice supports IPv4 multicast on 224.0.0.251 only.",
                    ),
                ),
            )
            return
        }

        val selectedModes = listOf(query != null, listen).count { it }
        if (selectedModes != 1) {
            echo("Choose exactly one mode: --query NAME or --listen")
            return
        }

        val client = MdnsClient(timeout = Duration.ofSeconds(timeoutSeconds.toLong()))
        val result = try {
            if (listen) {
                client.listen(bindAddress = bindAddress ?: "0.0.0.0", maxPackets = maxPackets)
            } else {
                client.query(
                    name = query!!,
                    type = recordType,
                    bindAddress = bindAddress,
                    maxPackets = maxPackets,
                )
            }
        } catch (e: Exception) {
            echo(
                formatter.formatDetail(
                    linkedMapOf(
                        "Protocol" to "mDNS",
                        "Status" to "Error",
                        "Error" to (e.message ?: e::class.java.simpleName),
                    ),
                ),
            )
            return
        }

        if (result.records.isEmpty()) {
            echo(
                formatter.formatDetail(
                    linkedMapOf(
                        "Protocol" to result.protocol,
                        "Mode" to result.mode,
                        "Status" to result.status,
                        "Responses" to result.responseCount,
                        "Timeout" to "${timeoutSeconds}s",
                    ),
                ),
            )
            return
        }

        echo(formatter.format(flattenRecords(result.records)))
    }

    private fun flattenRecords(records: List<MdnsRecord>): List<Map<String, Any?>> =
        records.map { record ->
            linkedMapOf(
                "Section" to record.section,
                "Name" to record.name,
                "Type" to record.type,
                "Class" to record.dnsClass,
                "TTL" to record.ttl,
                "Data" to record.data,
            )
        }
}

fun main(args: Array<String>) = MdnsCommand().main(args)
