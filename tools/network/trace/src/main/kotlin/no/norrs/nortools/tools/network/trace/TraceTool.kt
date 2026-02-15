package no.norrs.nortools.tools.network.trace

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import no.norrs.nortools.lib.cli.BaseCommand

/**
 * Traceroute tool — traces the network path to a host using the system traceroute command.
 *
 * Uses the system's traceroute utility to show each hop along the route.
 * Based on RFC 1393 (Traceroute Using an IP Option).
 */
class TraceCommand : BaseCommand(
    name = "trace",
    helpText = "Trace the network route to a host",
) {
    private val host by argument(help = "Host to trace route to")
    private val maxHops by option("--max-hops", "-m", help = "Maximum number of hops")
        .int()
        .default(30)

    override fun run() {
        val formatter = createFormatter()

        val command = listOf("traceroute", "-m", "$maxHops", "-w", "$timeoutSeconds", host)

        try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val lines = process.inputStream.bufferedReader().readLines()
            val exitCode = process.waitFor()

            // Parse traceroute output
            val hops = mutableListOf<Map<String, String>>()
            val hopPattern = "^\\s*([0-9]+)\\s+(.+)$".toRegex()

            for (line in lines.drop(1)) { // Skip header line
                val match = hopPattern.find(line)
                if (match != null) {
                    val hopNum = match.groupValues[1]
                    val rest = match.groupValues[2].trim()

                    if (rest.contains("* * *")) {
                        hops.add(
                            mapOf(
                                "Hop" to hopNum,
                                "Host" to "*",
                                "IP" to "*",
                                "RTT" to "* * *",
                            )
                        )
                    } else {
                        // Parse host (IP) time1 ms time2 ms time3 ms
                        val hostMatch = "^([^(]+)\\(([^)]+)\\)(.*)$".toRegex().find(rest)
                        if (hostMatch != null) {
                            val hostname = hostMatch.groupValues[1].trim()
                            val ip = hostMatch.groupValues[2].trim()
                            val times = hostMatch.groupValues[3].trim()
                            hops.add(
                                mapOf(
                                    "Hop" to hopNum,
                                    "Host" to hostname,
                                    "IP" to ip,
                                    "RTT" to times,
                                )
                            )
                        } else {
                            hops.add(
                                mapOf(
                                    "Hop" to hopNum,
                                    "Host" to rest,
                                    "IP" to "",
                                    "RTT" to "",
                                )
                            )
                        }
                    }
                }
            }

            val details = linkedMapOf<String, Any?>(
                "Target" to host,
                "Max Hops" to "$maxHops",
                "Hops Traced" to "${hops.size}",
                "Status" to if (exitCode == 0) "Complete" else "Incomplete",
            )
            echo(formatter.formatDetail(details))
            echo()

            if (hops.isNotEmpty()) {
                echo(formatter.format(hops))
            }
        } catch (e: Exception) {
            echo("Traceroute failed: ${e.message}")
        }
    }
}

fun main(args: Array<String>) = TraceCommand().main(args)
