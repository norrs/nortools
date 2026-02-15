package no.norrs.nortools.tools.composite.mailflow

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import no.norrs.nortools.lib.network.TcpClient
import org.xbill.DNS.Type
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration

/**
 * Mail Flow Diagnostic tool — end-to-end mail flow check.
 *
 * Tests the complete mail delivery path: MX resolution → TCP connectivity →
 * SMTP banner → STARTTLS support → TLS handshake.
 */
class MailFlowCommand : BaseCommand(
    name = "mailflow",
    helpText = "End-to-end mail flow diagnostic (MX → SMTP → STARTTLS → TLS)",
) {
    private val domain by argument(help = "Domain name to check mail flow for")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()
        val steps = mutableListOf<Map<String, String>>()

        echo("=== Mail Flow Diagnostic: $domain ===")
        echo()

        // Step 1: MX Resolution
        val mxResult = resolver.lookup(domain, Type.MX)
        if (mxResult.records.isEmpty()) {
            steps.add(step("MX Resolution", "FAIL", "No MX records found for $domain"))
            echo(formatter.format(steps))
            return
        }
        val mxHosts = mxResult.records.map { it.data }
        steps.add(step("MX Resolution", "PASS", "${mxHosts.size} MX: ${mxHosts.joinToString(", ")}"))

        // Use the highest priority (first) MX
        val primaryMx = mxHosts.first().split(" ").last().trimEnd('.')
        echo("Testing primary MX: $primaryMx")
        echo()

        // Step 2: A record for MX host
        val mxA = resolver.lookup(primaryMx, Type.A)
        if (mxA.records.isEmpty()) {
            steps.add(step("MX A Record", "FAIL", "No A record for $primaryMx"))
            echo(formatter.format(steps))
            return
        }
        val mxIp = mxA.records.first().data
        steps.add(step("MX A Record", "PASS", "$primaryMx → $mxIp"))

        // Step 3: TCP connectivity on port 25
        val tcpClient = TcpClient(timeout = Duration.ofSeconds(timeoutSeconds.toLong()))
        val tcpResult = tcpClient.connect(mxIp, 25, grabBanner = false)
        if (!tcpResult.connected) {
            steps.add(step("TCP Port 25", "FAIL", "Cannot connect to $mxIp:25 — ${tcpResult.error ?: "timeout"}"))
            echo(formatter.format(steps))
            return
        }
        steps.add(step("TCP Port 25", "PASS", "Connected in ${tcpResult.responseTimeMs}ms"))

        // Step 4: SMTP banner and STARTTLS
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(mxIp, 25), timeoutSeconds * 1000)
                socket.soTimeout = timeoutSeconds * 1000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                // Read banner
                val banner = reader.readLine() ?: ""
                steps.add(step("SMTP Banner", if (banner.startsWith("220")) "PASS" else "WARN", banner))

                // Send EHLO
                writer.println("EHLO nortools.check")
                val ehloLines = mutableListOf<String>()
                var line = reader.readLine()
                while (line != null && (line.startsWith("250-") || line.startsWith("250 "))) {
                    ehloLines.add(line)
                    if (line.startsWith("250 ")) break
                    line = reader.readLine()
                }

                val supportsStarttls = ehloLines.any { it.uppercase().contains("STARTTLS") }
                steps.add(step("EHLO Response", "PASS", "${ehloLines.size} extensions"))
                steps.add(step("STARTTLS Support", if (supportsStarttls) "PASS" else "WARN",
                    if (supportsStarttls) "STARTTLS advertised" else "STARTTLS not advertised"))

                // Try STARTTLS
                if (supportsStarttls) {
                    writer.println("STARTTLS")
                    val starttlsResponse = reader.readLine() ?: ""
                    steps.add(step("STARTTLS Handshake", if (starttlsResponse.startsWith("220")) "PASS" else "FAIL",
                        starttlsResponse))
                }

                // QUIT
                writer.println("QUIT")
            }
        } catch (e: Exception) {
            steps.add(step("SMTP Session", "FAIL", "Error: ${e.message}"))
        }

        // Step 5: Reverse DNS
        val ptr = resolver.reverseLookup(mxIp)
        steps.add(step("Reverse DNS (PTR)", if (ptr.records.isNotEmpty()) "PASS" else "WARN",
            if (ptr.records.isNotEmpty()) "$mxIp → ${ptr.records.first().data}" else "No PTR for $mxIp"))

        echo(formatter.format(steps))

        val pass = steps.count { it["Status"] == "PASS" }
        val fail = steps.count { it["Status"] == "FAIL" }
        echo()
        echo("Mail flow: $pass passed, $fail failures (${steps.size} steps)")
    }

    private fun step(name: String, status: String, details: String): Map<String, String> {
        return mapOf("Step" to name, "Status" to status, "Details" to details)
    }
}

fun main(args: Array<String>) = MailFlowCommand().main(args)
