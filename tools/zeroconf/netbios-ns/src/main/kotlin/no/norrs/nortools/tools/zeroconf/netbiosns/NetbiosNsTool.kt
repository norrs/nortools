package no.norrs.nortools.tools.zeroconf.netbiosns

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import no.norrs.nortools.lib.cli.BaseCommand
import no.norrs.nortools.lib.zeroconf.IpFamily
import no.norrs.nortools.lib.zeroconf.NetbiosNameServiceClient
import no.norrs.nortools.lib.zeroconf.NetbiosResponse
import java.time.Duration

class NetbiosNsCommand : BaseCommand(
    name = "netbios-ns",
    helpText = "Debug NetBIOS Name Service queries, node status, and passive UDP 137 traffic",
) {
    private val query by option("--query", "-q", help = "NetBIOS name to query")
    private val nodeStatus by option("--node-status", help = "IPv4 host to query for NetBIOS node status")
    private val listen by option("--listen", help = "Listen passively for NetBIOS Name Service packets").flag()
    private val target by option("--target", help = "IPv4 broadcast or host target for name query")
        .default("255.255.255.255")
    private val suffix by option("--suffix", help = "NetBIOS suffix in decimal, for example 32 for file server")
        .int()
        .default(0x20)
    private val ipFamilyValue by option("--ip-family", help = "IP family: ipv4, ipv6, or both")
        .default("ipv4")
    private val maxPackets by option("--max-packets", help = "Maximum packets to collect in passive listener mode")
        .int()
        .default(25)

    override fun run() {
        val formatter = createFormatter()
        val ipFamily = try {
            IpFamily.fromCli(ipFamilyValue)
        } catch (e: IllegalArgumentException) {
            echo("Invalid --ip-family '$ipFamilyValue'. Expected ipv4, ipv6, or both.")
            return
        }
        if (!ipFamily.allowsIpv4()) {
            echo(
                formatter.formatDetail(
                    linkedMapOf(
                        "Protocol" to "NetBIOS Name Service",
                        "Status" to "Unsupported IP family",
                        "Requested IP Family" to ipFamilyValue,
                        "Reason" to "NetBIOS Name Service uses IPv4 broadcast over UDP 137",
                    ),
                ),
            )
            return
        }

        val selectedModes = listOf(query != null, nodeStatus != null, listen).count { it }
        if (selectedModes != 1) {
            echo("Choose exactly one mode: --query NAME, --node-status IPv4, or --listen")
            return
        }

        val client = NetbiosNameServiceClient(timeout = Duration.ofSeconds(timeoutSeconds.toLong()))
        val responses = try {
            when {
                query != null -> client.queryName(query!!, suffix = suffix, target = target)
                nodeStatus != null -> client.nodeStatus(nodeStatus!!)
                else -> client.listen(maxPackets = maxPackets)
            }
        } catch (e: Exception) {
            echo(
                formatter.formatDetail(
                    linkedMapOf(
                        "Protocol" to "NetBIOS Name Service",
                        "Status" to "Error",
                        "Error" to (e.message ?: e::class.java.simpleName),
                    ),
                ),
            )
            return
        }

        if (responses.isEmpty()) {
            echo(
                formatter.formatDetail(
                    linkedMapOf(
                        "Protocol" to "NetBIOS Name Service",
                        "Status" to "No responses",
                        "Timeout" to "${timeoutSeconds}s",
                    ),
                ),
            )
            return
        }

        echo(formatter.format(flattenResponses(responses)))
    }

    private fun flattenResponses(responses: List<NetbiosResponse>): List<Map<String, Any?>> {
        val rows = mutableListOf<Map<String, Any?>>()
        for (response in responses) {
            if (response.addresses.isEmpty() && response.names.isEmpty()) {
                rows += linkedMapOf(
                    "Source" to response.sourceAddress,
                    "Type" to "Packet",
                    "Name" to "",
                    "Suffix" to "",
                    "Address" to "",
                    "Group" to "",
                    "Result" to response.resultCode,
                    "Error" to (response.error ?: ""),
                )
            }
            for (address in response.addresses) {
                rows += linkedMapOf(
                    "Source" to response.sourceAddress,
                    "Type" to "NB",
                    "Name" to address.name,
                    "Suffix" to "0x${address.suffix.toString(16).padStart(2, '0')}",
                    "Address" to address.address,
                    "Group" to address.group,
                    "Result" to response.resultCode,
                    "Error" to "",
                )
            }
            for (name in response.names) {
                rows += linkedMapOf(
                    "Source" to response.sourceAddress,
                    "Type" to "NBSTAT",
                    "Name" to name.name,
                    "Suffix" to "0x${name.suffix.toString(16).padStart(2, '0')}",
                    "Address" to "",
                    "Group" to name.group,
                    "Result" to response.resultCode,
                    "Error" to "",
                )
            }
        }
        return rows
    }
}

fun main(args: Array<String>) = NetbiosNsCommand().main(args)
