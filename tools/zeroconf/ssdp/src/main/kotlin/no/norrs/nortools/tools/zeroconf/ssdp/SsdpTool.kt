package no.norrs.nortools.tools.zeroconf.ssdp

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import no.norrs.nortools.lib.cli.BaseCommand
import no.norrs.nortools.lib.zeroconf.IpFamily
import no.norrs.nortools.lib.zeroconf.SsdpClient
import no.norrs.nortools.lib.zeroconf.SsdpMessage
import java.time.Duration

class SsdpCommand : BaseCommand(
    name = "ssdp",
    helpText = "Debug SSDP / UPnP discovery traffic on UDP 1900",
) {
    private val searchTarget by option("--search", help = "Search target, for example ssdp:all or upnp:rootdevice")
        .default("ssdp:all")
    private val listen by option("--listen", help = "Listen passively for SSDP NOTIFY and response traffic").flag()
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
                        "Protocol" to "SSDP",
                        "Status" to "Unsupported IP family",
                        "Requested IP Family" to ipFamilyValue,
                        "Reason" to "This first SSDP slice supports IPv4 multicast on 239.255.255.250 only.",
                    ),
                ),
            )
            return
        }

        val client = SsdpClient(timeout = Duration.ofSeconds(timeoutSeconds.toLong()))
        val result = try {
            if (listen) {
                client.listen(bindAddress = bindAddress ?: "0.0.0.0", maxPackets = maxPackets)
            } else {
                client.search(searchTarget = searchTarget, bindAddress = bindAddress, maxPackets = maxPackets)
            }
        } catch (e: Exception) {
            echo(
                formatter.formatDetail(
                    linkedMapOf(
                        "Protocol" to "SSDP",
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
            )
            result.searchTarget?.let { detail["Search Target"] = it }
            if (result.warnings.isNotEmpty()) detail["Warnings"] = result.warnings.joinToString(" | ")
            echo(formatter.formatDetail(detail))
            return
        }

        echo(formatter.format(flattenMessages(result.messages)))
    }

    private fun flattenMessages(messages: List<SsdpMessage>): List<Map<String, Any?>> =
        messages.map { message ->
            linkedMapOf(
                "Start Line" to message.startLine,
                "Type" to when {
                    message.isNotify -> "NOTIFY"
                    message.isResponse -> "Response"
                    else -> "Packet"
                },
                "ST/NT" to (message.searchTarget ?: message.notificationType ?: ""),
                "USN" to (message.uniqueServiceName ?: ""),
                "Location" to (message.location ?: ""),
                "Server" to (message.server ?: ""),
                "DLNA" to message.isDlnaLike,
            )
        }
}

fun main(args: Array<String>) = SsdpCommand().main(args)
