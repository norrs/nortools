package no.norrs.nortools.tools.network.ping

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import no.norrs.nortools.lib.cli.BaseCommand
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Ping tool — sends ICMP echo requests to a host using the system ping command.
 *
 * Uses the system's ping utility since ICMP requires raw sockets (privileged).
 * Based on RFC 792 (ICMP) and RFC 4443 (ICMPv6).
 */
class PingCommand : BaseCommand(
    name = "ping",
    helpText = "Ping a host using ICMP echo requests",
) {
    private val host by argument(help = "Host to ping")
    private val count by option("--count", "-c", help = "Number of ping requests to send")
        .int()
        .default(4)

    override fun run() {
        val formatter = createFormatter()

        val isLinux = System.getProperty("os.name").lowercase().contains("linux")
        val command = if (isLinux) {
            listOf("ping", "-c", "$count", "-W", "$timeoutSeconds", host)
        } else {
            listOf("ping", "-c", "$count", "-t", "$timeoutSeconds", host)
        }

        try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val lines = mutableListOf<String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                lines.add(line!!)
            }

            val exitCode = process.waitFor()

            // Parse ping output
            val results = mutableListOf<Map<String, String>>()
            val replyPattern = ".*from ([^:]+):.*time=([0-9.]+).*".toRegex()
            for (l in lines) {
                val match = replyPattern.find(l)
                if (match != null) {
                    results.add(
                        mapOf(
                            "From" to match.groupValues[1],
                            "Time" to "${match.groupValues[2]}ms",
                        )
                    )
                }
            }

            // Parse summary
            val statsLine = lines.find { it.contains("packets transmitted") }
            val rttLine = lines.find { it.contains("min/avg/max") }

            val details = linkedMapOf<String, Any?>(
                "Host" to host,
                "Packets Sent" to "$count",
            )

            if (statsLine != null) {
                val received = "([0-9]+) received".toRegex().find(statsLine)?.groupValues?.get(1)
                val loss = "([0-9.]+)% packet loss".toRegex().find(statsLine)?.groupValues?.get(1)
                details["Packets Received"] = received ?: "?"
                details["Packet Loss"] = "${loss ?: "?"}%"
            }

            if (rttLine != null) {
                val rttMatch = "= ([0-9.]+)/([0-9.]+)/([0-9.]+)/([0-9.]+)".toRegex().find(rttLine)
                if (rttMatch != null) {
                    details["Min RTT"] = "${rttMatch.groupValues[1]}ms"
                    details["Avg RTT"] = "${rttMatch.groupValues[2]}ms"
                    details["Max RTT"] = "${rttMatch.groupValues[3]}ms"
                }
            }

            details["Status"] = if (exitCode == 0) "Reachable" else "Unreachable"

            echo(formatter.formatDetail(details))

            if (results.isNotEmpty()) {
                echo()
                echo(formatter.format(results))
            }
        } catch (e: Exception) {
            echo("Ping failed: ${e.message}")
        }
    }
}

fun main(args: Array<String>) = PingCommand().main(args)
