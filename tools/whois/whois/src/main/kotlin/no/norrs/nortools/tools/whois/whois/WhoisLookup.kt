package no.norrs.nortools.tools.whois.whois

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import no.norrs.nortools.lib.cli.BaseCommand
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * WHOIS Lookup tool — queries WHOIS servers for domain registration information.
 *
 * Connects to WHOIS servers on TCP port 43 per RFC 3912.
 */
class WhoisLookupCommand : BaseCommand(
    name = "whois",
    helpText = "Look up WHOIS registration information for a domain or IP",
) {
    private val query by argument(help = "Domain name or IP address to look up")
    private val whoisServer by option("--whois-server", "-w", help = "WHOIS server to query")
        .default("")
    private val raw by option("--raw", "-r", help = "Show raw WHOIS response").flag()

    override fun run() {
        val formatter = createFormatter()

        val server = if (whoisServer.isNotEmpty()) {
            whoisServer
        } else {
            determineWhoisServer(query)
        }

        val response = queryWhois(server, query)

        if (raw) {
            echo(response)
            return
        }

        // Parse key fields from WHOIS response
        val details = linkedMapOf<String, Any?>(
            "Query" to query,
            "WHOIS Server" to server,
        )

        val fields = parseWhoisFields(response)
        for ((key, value) in fields) {
            details[key] = value
        }

        echo(formatter.formatDetail(details))
    }

    private fun determineWhoisServer(query: String): String {
        // Check if it's an IP address
        if (query.matches("[0-9.]+".toRegex())) {
            return "whois.arin.net"
        }
        if (query.contains(":")) {
            return "whois.arin.net" // IPv6
        }

        // Determine by TLD
        val tld = query.substringAfterLast(".")
        return when (tld.lowercase()) {
            "com", "net" -> "whois.verisign-grs.com"
            "org" -> "whois.pir.org"
            "io" -> "whois.nic.io"
            "dev" -> "whois.nic.google"
            "app" -> "whois.nic.google"
            "no" -> "whois.norid.no"
            "se" -> "whois.iis.se"
            "dk" -> "whois.dk-hostmaster.dk"
            "uk" -> "whois.nic.uk"
            "de" -> "whois.denic.de"
            "fr" -> "whois.nic.fr"
            "eu" -> "whois.eu"
            "info" -> "whois.afilias.net"
            "biz" -> "whois.biz"
            else -> "whois.iana.org"
        }
    }

    private fun queryWhois(server: String, query: String): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(server, 43), timeoutSeconds * 1000)
            socket.soTimeout = timeoutSeconds * 1000

            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            writer.println(query)
            return reader.readText()
        }
    }

    private fun parseWhoisFields(response: String): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        val keyFields = setOf(
            "Domain Name", "Registry Domain ID", "Registrar",
            "Registrar WHOIS Server", "Registrar URL",
            "Updated Date", "Creation Date", "Registry Expiry Date",
            "Registrant Organization", "Registrant Country",
            "Name Server", "DNSSEC", "Status",
            "NetRange", "CIDR", "NetName", "OrgName", "OrgId",
        )

        for (line in response.lines()) {
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val key = line.substring(0, colonIndex).trim()
                val value = line.substring(colonIndex + 1).trim()
                if (key in keyFields && value.isNotEmpty()) {
                    if (fields.containsKey(key)) {
                        fields[key] = fields[key] + ", " + value
                    } else {
                        fields[key] = value
                    }
                }
            }
        }
        return fields
    }
}

fun main(args: Array<String>) = WhoisLookupCommand().main(args)
