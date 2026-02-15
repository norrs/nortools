package no.norrs.nortools.tools.network.tcp

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import no.norrs.nortools.lib.cli.BaseCommand
import no.norrs.nortools.lib.network.TcpClient
import java.time.Duration

/**
 * TCP Port Check tool — tests TCP connectivity to a host and port.
 *
 * Checks if a TCP port is open and optionally grabs the server banner.
 * Uses standard TCP socket connections (RFC 793).
 */
class TcpCheckCommand : BaseCommand(
    name = "tcp",
    helpText = "Check TCP port connectivity and optionally grab banner",
) {
    private val host by argument(help = "Host to connect to")
    private val port by argument(help = "Port number to check").int()
    private val banner by option("--banner", "-b", help = "Attempt to grab server banner").flag()
    private val commonPorts by option("--common", "-c", help = "Scan common ports for the host").flag()

    override fun run() {
        val formatter = createFormatter()
        val client = TcpClient(timeout = Duration.ofSeconds(timeoutSeconds.toLong()))

        if (commonPorts) {
            val ports = listOf(21, 22, 25, 53, 80, 110, 143, 443, 465, 587, 993, 995, 3306, 3389, 5432, 8080, 8443)
            val results = ports.map { p ->
                val result = client.connect(host, p, grabBanner = banner)
                mapOf(
                    "Port" to "$p",
                    "Status" to if (result.connected) "OPEN" else "CLOSED",
                    "Response Time" to "${result.responseTimeMs}ms",
                    "Banner" to (result.banner ?: result.error ?: ""),
                )
            }
            echo(formatter.format(results))
        } else {
            val result = client.connect(host, port, grabBanner = banner)
            val details = linkedMapOf<String, Any?>(
                "Host" to host,
                "Port" to port,
                "Status" to if (result.connected) "OPEN" else "CLOSED",
                "Response Time" to "${result.responseTimeMs}ms",
            )
            if (result.banner != null) details["Banner"] = result.banner
            if (result.error != null) details["Error"] = result.error
            echo(formatter.formatDetail(details))
        }
    }
}

fun main(args: Array<String>) = TcpCheckCommand().main(args)
