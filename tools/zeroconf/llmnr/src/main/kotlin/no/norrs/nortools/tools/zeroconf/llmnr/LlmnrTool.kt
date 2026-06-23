package no.norrs.nortools.tools.zeroconf.llmnr

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import no.norrs.nortools.lib.cli.BaseCommand
import no.norrs.nortools.lib.zeroconf.IpFamily
import no.norrs.nortools.lib.zeroconf.LlmnrClient
import no.norrs.nortools.lib.zeroconf.LlmnrRecord
import java.time.Duration

class LlmnrCommand : BaseCommand(
    name = "llmnr",
    helpText = "Debug LLMNR multicast name queries and passive UDP 5355 traffic",
) {
    private val query by option("--query", "-q", help = "LLMNR name to query")
    private val listen by option("--listen", help = "Listen passively for LLMNR traffic").flag()
    private val type by option("--type", help = "Record type, for example A or AAAA").default("A")
    private val ipFamilyValue by option("--ip-family", help = "IP family: ipv4, ipv6, or both")
        .default("ipv4")
    private val maxPackets by option("--max-packets", help = "Maximum packets to collect")
        .int()
        .default(25)
    private val bindAddress by option(
        "--bind-address",
        help = "Optional local address to bind. Leave empty to use eligible multicast interfaces.",
    )

    override fun run() {
        val formatter = createFormatter()
        val ipFamily = try {
            IpFamily.fromCli(ipFamilyValue)
        } catch (_: IllegalArgumentException) {
            echo("Invalid --ip-family '$ipFamilyValue'. Expected ipv4, ipv6, or both.")
            return
        }

        if (!listen && query.isNullOrBlank()) {
            echo("Provide --query <name> or use --listen.")
            return
        }

        val client = LlmnrClient(timeout = Duration.ofSeconds(timeoutSeconds.toLong()))
        val result = try {
            if (listen) {
                client.listen(ipFamily = ipFamily, bindAddress = bindAddress, maxPackets = maxPackets)
            } else {
                client.query(
                    name = query!!,
                    type = type,
                    ipFamily = ipFamily,
                    bindAddress = bindAddress,
                    maxPackets = maxPackets,
                )
            }
        } catch (e: Exception) {
            echo(
                formatter.formatDetail(
                    linkedMapOf(
                        "Protocol" to "LLMNR",
                        "Status" to "Error",
                        "Error" to (e.message ?: e::class.java.simpleName),
                    ),
                ),
            )
            return
        }

        if (result.records.isEmpty()) {
            val detail = linkedMapOf(
                "Protocol" to result.protocol,
                "Mode" to result.mode,
                "Status" to result.status,
                "Responses" to result.responseCount,
                "Timeout" to "${timeoutSeconds}s",
                "IP Family" to ipFamilyValue,
            )
            result.queryName?.let { detail["Query Name"] = it }
            result.queryType?.let { detail["Query Type"] = it }
            if (result.warnings.isNotEmpty()) detail["Warnings"] = result.warnings.joinToString(" | ")
            echo(formatter.formatDetail(detail))
            return
        }

        echo(formatter.format(flattenRecords(result.records)))
    }

    private fun flattenRecords(records: List<LlmnrRecord>): List<Map<String, Any?>> =
        records.map { record ->
            linkedMapOf(
                "Source" to record.section,
                "Type" to record.type,
                "Name" to record.name,
                "Class" to record.dnsClass,
                "TTL" to record.ttl,
                "Data" to record.data,
            )
        }
}

fun main(args: Array<String>) = LlmnrCommand().main(args)
