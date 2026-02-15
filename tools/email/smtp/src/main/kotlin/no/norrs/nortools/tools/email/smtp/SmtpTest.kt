package no.norrs.nortools.tools.email.smtp

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.Type
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

/**
 * SMTP Server Test tool — connects to SMTP servers and tests STARTTLS capability.
 *
 * Tests SMTP connectivity, banner, EHLO response, and STARTTLS support.
 * Uses RFC 5321 (SMTP), RFC 3207 (STARTTLS), RFC 8314 (Implicit TLS).
 */
class SmtpTestCommand : BaseCommand(
    name = "smtp",
    helpText = "Test SMTP server connectivity and STARTTLS support",
) {
    private val domain by argument(help = "Domain name to test SMTP for (resolves MX records)")
    private val port by option("--port", "-p", help = "SMTP port (default: 25)")
        .int()
        .default(25)

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()

        // Resolve MX records to find mail servers
        val mxResult = resolver.lookup(domain, Type.MX)
        val mailServers = if (mxResult.isSuccessful && mxResult.records.isNotEmpty()) {
            mxResult.records.map { it.data.split(" ").last().trimEnd('.') }
        } else {
            // Fall back to domain itself
            listOf(domain)
        }

        val results = mailServers.map { server ->
            testSmtpServer(server, port)
        }

        echo(formatter.format(results))
    }

    private fun testSmtpServer(server: String, port: Int): Map<String, String> {
        val result = linkedMapOf<String, String>(
            "Server" to server,
            "Port" to "$port",
        )

        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(server, port), timeoutSeconds * 1000)
                socket.soTimeout = timeoutSeconds * 1000

                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                // Read banner
                val banner = readSmtpResponse(reader)
                result["Banner"] = banner.lines().firstOrNull() ?: ""
                result["Connected"] = "Yes"

                // Send EHLO
                writer.println("EHLO nortools.test")
                val ehloResponse = readSmtpResponse(reader)
                val extensions = ehloResponse.lines()
                    .filter { it.startsWith("250") }
                    .map { it.removePrefix("250-").removePrefix("250 ").trim() }

                result["EHLO"] = "OK"
                result["Extensions"] = extensions.drop(1).joinToString(", ")

                // Check STARTTLS
                val hasStartTls = extensions.any { it.uppercase().startsWith("STARTTLS") }
                result["STARTTLS"] = if (hasStartTls) "Supported" else "Not Supported"

                if (hasStartTls) {
                    // Attempt STARTTLS
                    writer.println("STARTTLS")
                    val starttlsResponse = readSmtpResponse(reader)
                    if (starttlsResponse.startsWith("220")) {
                        try {
                            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                            val sslSocket = sslFactory.createSocket(
                                socket, server, port, true
                            ) as javax.net.ssl.SSLSocket
                            sslSocket.startHandshake()
                            val session = sslSocket.session
                            result["TLS Version"] = session.protocol
                            result["Cipher"] = session.cipherSuite
                        } catch (e: Exception) {
                            result["TLS Handshake"] = "Failed: ${e.message}"
                        }
                    } else {
                        result["STARTTLS Response"] = starttlsResponse.trim()
                    }
                }

                // Send QUIT
                writer.println("QUIT")
            }
        } catch (e: Exception) {
            result["Connected"] = "No"
            result["Error"] = e.message ?: "Unknown error"
        }

        return result
    }

    private fun readSmtpResponse(reader: BufferedReader): String {
        val sb = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: break
            sb.appendLine(line)
            // SMTP multi-line responses use "XXX-" prefix; last line uses "XXX "
            if (line.length >= 4 && line[3] == ' ') break
        }
        return sb.toString().trimEnd()
    }
}

fun main(args: Array<String>) = SmtpTestCommand().main(args)
