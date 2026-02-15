package no.norrs.nortools.web

import io.javalin.http.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

// ─── WHOIS Tools ─────────────────────────────────────────────────────────────

fun whoisLookup(ctx: Context) {
    val query = ctx.pathParam("query")
    val server = determineWhoisServer(query)
    try {
        val response = queryWhois(server, query)
        val fields = parseWhoisFields(response)
        ctx.jsonResult(WhoisResponse(query = query, server = server, fields = fields, raw = response))
    } catch (e: Exception) {
        ctx.jsonResult(ErrorResponse("WHOIS failed: ${e.message}"))
    }
}

private fun determineWhoisServer(query: String): String {
    if (query.matches("[0-9.]+".toRegex()) || query.contains(":")) return "whois.arin.net"
    val tld = query.substringAfterLast(".")
    return when (tld.lowercase()) {
        "com", "net" -> "whois.verisign-grs.com"
        "org" -> "whois.pir.org"
        "io" -> "whois.nic.io"
        "dev", "app" -> "whois.nic.google"
        "no" -> "whois.norid.no"
        "se" -> "whois.iis.se"
        "dk" -> "whois.dk-hostmaster.dk"
        "uk" -> "whois.nic.uk"
        "de" -> "whois.denic.de"
        "fr" -> "whois.nic.fr"
        "eu" -> "whois.eu"
        else -> "whois.iana.org"
    }
}

private fun queryWhois(server: String, query: String): String {
    Socket().use { socket ->
        socket.connect(InetSocketAddress(server, 43), 10000)
        socket.soTimeout = 10000
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
                fields[key] = if (fields.containsKey(key)) "${fields[key]}, $value" else value
            }
        }
    }
    return fields
}

// ─── Models ─────────────────────────────────────────────────────────────────

data class WhoisResponse(
    val query: String,
    val server: String,
    val fields: Map<String, String>,
    val raw: String,
)
