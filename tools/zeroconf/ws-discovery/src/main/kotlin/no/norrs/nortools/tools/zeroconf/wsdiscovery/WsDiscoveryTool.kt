package no.norrs.nortools.tools.zeroconf.wsdiscovery

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import no.norrs.nortools.lib.cli.BaseCommand
import no.norrs.nortools.lib.zeroconf.IpFamily
import no.norrs.nortools.lib.zeroconf.WsDiscoveryClient
import no.norrs.nortools.lib.zeroconf.WsDiscoveryMessage
import java.time.Duration

class WsDiscoveryCommand : BaseCommand(
    name = "ws-discovery",
    helpText = "Debug WS-Discovery probe and passive UDP 3702 traffic",
) {
    private val probeTypes by option("--types", help = "Probe types, for example wsdp:Device or dn:NetworkVideoTransmitter")
    private val scopes by option("--scopes", help = "Optional WS-Discovery scopes filter")
    private val listen by option("--listen", help = "Listen passively for WS-Discovery traffic").flag()
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

        val client = WsDiscoveryClient(timeout = Duration.ofSeconds(timeoutSeconds.toLong()))
        val result = try {
            if (listen) {
                client.listen(bindAddress = bindAddress ?: "0.0.0.0", ipFamily = ipFamily, maxPackets = maxPackets)
            } else {
                client.probe(types = probeTypes, scopes = scopes, ipFamily = ipFamily, bindAddress = bindAddress, maxPackets = maxPackets)
            }
        } catch (e: Exception) {
            echo(
                formatter.formatDetail(
                    linkedMapOf(
                        "Protocol" to "WS-Discovery",
                        "Status" to "Error",
                        "Error" to (e.message ?: e::class.java.simpleName),
                    ),
                ),
            )
            return
        }

        if (result.messages.isEmpty()) {
            val detail = linkedMapOf(
                "Protocol" to result.protocol,
                "Mode" to result.mode,
                "Status" to result.status,
                "Responses" to result.responseCount,
                "Timeout" to "${timeoutSeconds}s",
                "IP Family" to ipFamilyValue,
            )
            result.probeTypes?.let { detail["Types"] = it }
            result.scopes?.let { detail["Scopes"] = it }
            if (result.warnings.isNotEmpty()) detail["Warnings"] = result.warnings.joinToString(" | ")
            echo(formatter.formatDetail(detail))
            return
        }

        echo(formatter.format(flattenMessages(result.messages)))
    }

    private fun flattenMessages(messages: List<WsDiscoveryMessage>): List<Map<String, Any?>> =
        messages.map { message ->
            linkedMapOf(
                "Type" to message.messageType,
                "Action" to (message.action ?: ""),
                "Endpoint" to (message.endpointReference ?: ""),
                "Types" to (message.types ?: ""),
                "Scopes" to (message.scopes ?: ""),
                "XAddrs" to (message.xAddrs ?: ""),
                "MetadataVersion" to (message.metadataVersion ?: ""),
            )
        }
}

fun main(args: Array<String>) = WsDiscoveryCommand().main(args)
