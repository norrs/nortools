package no.norrs.nortools.tools.whois.common

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

private const val NORID_DISCLAIMER = "Norid AS holds the copyright to the lookup service, content, layout and the underlying collections of information used in the service (cf. the Act on Intellectual Property of May 2, 1961, No. 2). Any commercial use of information from the service, including targeted marketing, is prohibited. Using information from the domain registration directory service in violation of the terms and conditions may result in legal prosecution."
private const val NORID_TERMS_URL = "https://www.norid.no/en/domeneoppslag/vilkar"

data class WhoisHop(
    val server: String,
    val query: String,
    val fields: Map<String, String>,
    val raw: String,
)

data class WhoisResult(
    val query: String,
    val initialServer: String,
    val hops: List<WhoisHop>,
) {
    val finalServer: String get() = hops.lastOrNull()?.server ?: initialServer

    val mergedFields: Map<String, String>
        get() {
            val merged = linkedMapOf<String, String>()
            for (hop in hops) {
                for ((key, value) in hop.fields) {
                    if (value.isBlank()) continue
                    val previous = merged[key]
                    merged[key] = if (previous == null || previous.equals(value, ignoreCase = true)) {
                        value
                    } else {
                        "$previous, $value"
                    }
                }
            }
            if (query.endsWith(".no", ignoreCase = true)) {
                merged["Disclaimer"] = NORID_DISCLAIMER
                merged["Terms URL"] = NORID_TERMS_URL
            }
            return merged
        }

    fun combinedRaw(): String {
        if (hops.isEmpty()) return ""
        val baseRaw = if (hops.size == 1) {
            hops[0].raw
        } else {
            hops.joinToString("\n\n") { hop ->
                "# Source: ${hop.server}\n${hop.raw}"
            }
        }
        if (!query.endsWith(".no", ignoreCase = true)) {
            return baseRaw
        }
        return "$baseRaw\n\n# Norid Notice\n$NORID_DISCLAIMER\n$NORID_TERMS_URL"
    }
}

object WhoisClient {
    private val ipV4Regex = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")
    private val structuredLineRegex = Regex("^([^:]{1,120}?):\\s*(.+)$")
    private val noridLineRegex = Regex("^([A-Za-z][A-Za-z0-9 .()/_-]{1,80}?)\\.+:\\s*(.+)$")

    fun determineWhoisServer(query: String): String {
        if (ipV4Regex.matches(query) || query.contains(':')) {
            return "whois.arin.net"
        }

        val tld = query.substringAfterLast('.', "").lowercase()
        return when (tld) {
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
            "info" -> "whois.afilias.net"
            "biz" -> "whois.biz"
            "ai" -> "whois.nic.ai"
            "us" -> "whois.nic.us"
            "ca" -> "whois.cira.ca"
            "ch", "li" -> "whois.nic.ch"
            "nl" -> "whois.domain-registry.nl"
            "be" -> "whois.dns.be"
            "it" -> "whois.nic.it"
            "es" -> "whois.nic.es"
            "pl" -> "whois.dns.pl"
            "cz" -> "whois.nic.cz"
            "at" -> "whois.nic.at"
            "jp" -> "whois.jprs.jp"
            "au" -> "whois.auda.org.au"
            "nz" -> "whois.srs.net.nz"
            "br" -> "whois.registro.br"
            "in" -> "whois.registry.in"
            "ru" -> "whois.tcinet.ru"
            "cn" -> "whois.cnnic.com.cn"
            else -> "whois.iana.org"
        }
    }

    fun lookup(
        query: String,
        serverOverride: String? = null,
        timeoutMillis: Int = 10_000,
        maxHops: Int = 4,
    ): WhoisResult {
        val initialServer = serverOverride ?: determineWhoisServer(query)
        val visitedServers = linkedSetOf<String>()
        val hops = mutableListOf<WhoisHop>()

        var currentServer = initialServer
        var hopCount = 0
        while (hopCount < maxHops && visitedServers.add(currentServer)) {
            val response = queryWhois(currentServer, query, timeoutMillis)
            hops += WhoisHop(
                server = currentServer,
                query = query,
                fields = parseWhoisFields(response),
                raw = response,
            )

            val nextServer = findReferralServer(response, currentServer)
            if (nextServer == null || nextServer in visitedServers) {
                break
            }

            currentServer = nextServer
            hopCount++
        }

        if (query.endsWith(".no", ignoreCase = true) && hops.none { it.server == "rdap.norid.no" }) {
            val rdapBody = queryNoridRdap(query, timeoutMillis)
            if (!rdapBody.isNullOrBlank()) {
                hops += WhoisHop(
                    server = "rdap.norid.no",
                    query = query,
                    fields = parseNoridRdapFields(rdapBody),
                    raw = rdapBody,
                )
            }
        }

        return WhoisResult(
            query = query,
            initialServer = initialServer,
            hops = hops,
        )
    }

    fun parseWhoisFields(response: String): Map<String, String> {
        val fields = linkedMapOf<String, String>()

        response.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("%") || line.startsWith("#")) return@forEach

            val kv = structuredLineRegex.find(line) ?: noridLineRegex.find(line)
            if (kv != null) {
                val key = normalizeFieldKey(kv.groupValues[1].trim().trimEnd('.'))
                val value = kv.groupValues[2].trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    val previous = fields[key]
                    fields[key] = if (previous == null || previous.equals(value, ignoreCase = true)) {
                        value
                    } else {
                        "$previous, $value"
                    }
                }
            }
        }

        return fields
    }

    fun findReferralServer(response: String, currentServer: String): String? {
        val regexes = listOf(
            Regex("(?im)^ReferralServer:\\s*(.+)$"),
            Regex("(?im)^Whois Server:\\s*(.+)$"),
            Regex("(?im)^Registrar WHOIS Server:\\s*(.+)$"),
            Regex("(?im)^refer:\\s*(.+)$"),
            Regex("(?im)^whois:\\s*(.+)$"),
            Regex("(?im)^ResourceLink:\\s*(whois[^\\s]+)$"),
        )

        for (regex in regexes) {
            val match = regex.find(response) ?: continue
            val candidate = normalizeWhoisHost(match.groupValues[1]) ?: continue
            if (!candidate.equals(currentServer, ignoreCase = true)) {
                return candidate
            }
        }

        return inferRirReferral(response, currentServer)
    }

    fun normalizeWhoisHost(rawHost: String): String? {
        var value = rawHost.trim()
        if (value.isEmpty()) return null

        value = value.substringBefore(' ').trim()
        value = value.removePrefix("whois://")
        value = value.removePrefix("WHOIS://")
        value = value.removePrefix("rwhois://")
        value = value.substringBefore('/').trim().trimEnd('.')

        return value.takeIf { it.contains('.') }
    }

    private fun normalizeFieldKey(rawKey: String): String {
        val compact = rawKey.trim().replace(Regex("\\s+"), " ")
        return when {
            Regex("(?i)^terms? of (use|service|services)$").matches(compact) -> "Terms of Use"
            Regex("(?i)^terms? and conditions$").matches(compact) -> "Terms of Use"
            Regex("(?i)^whois terms?( of use)?$").matches(compact) -> "Terms of Use"
            else -> compact
        }
    }

    private fun inferRirReferral(response: String, currentServer: String): String? {
        if (!currentServer.equals("whois.arin.net", ignoreCase = true)) return null

        return when {
            Regex("(?i)\\bOrgId:\\s*RIPE\\b").containsMatchIn(response) ||
                Regex("(?i)Allocated to RIPE NCC").containsMatchIn(response) -> "whois.ripe.net"

            Regex("(?i)\\bOrgId:\\s*APNIC\\b").containsMatchIn(response) ||
                Regex("(?i)Allocated to APNIC").containsMatchIn(response) -> "whois.apnic.net"

            Regex("(?i)\\bOrgId:\\s*AFRINIC\\b").containsMatchIn(response) ||
                Regex("(?i)Allocated to AFRINIC").containsMatchIn(response) -> "whois.afrinic.net"

            Regex("(?i)\\bOrgId:\\s*LACNIC\\b").containsMatchIn(response) ||
                Regex("(?i)Allocated to LACNIC").containsMatchIn(response) -> "whois.lacnic.net"

            else -> null
        }
    }

    private fun queryWhois(server: String, query: String, timeoutMillis: Int): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(server, 43), timeoutMillis)
            socket.soTimeout = timeoutMillis

            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            writer.print(query)
            writer.print("\r\n")
            writer.flush()
            val out = StringBuilder()
            while (true) {
                try {
                    val line = reader.readLine() ?: break
                    out.append(line).append('\n')
                } catch (e: SocketTimeoutException) {
                    if (out.isNotEmpty()) break
                    throw e
                }
            }
            return out.toString().trimEnd()
        }
    }

    private fun queryNoridRdap(domain: String, timeoutMillis: Int): String? {
        return runCatching {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMillis.toLong()))
                .build()

            val encoded = URLEncoder.encode(domain, StandardCharsets.UTF_8)
            val request = HttpRequest.newBuilder(URI("https://rdap.norid.no/domain/$encoded"))
                .timeout(Duration.ofMillis(timeoutMillis.toLong()))
                .header("Accept", "application/rdap+json")
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) response.body() else null
        }.getOrNull()
    }

    private fun parseNoridRdapFields(rdapJson: String): Map<String, String> {
        val root = runCatching {
            JsonParser.parseString(rdapJson).asJsonObject
        }.getOrNull() ?: return emptyMap()

        val fields = linkedMapOf<String, String>()

        root.getAsString("ldhName")?.let { fields["Domain Name"] = it }
        root.getAsString("handle")?.let { fields["NORID Handle"] = it }

        root.getAsJsonArray("nameservers")?.forEach { ns ->
            val nsName = ns.asJsonObject.getAsString("ldhName") ?: return@forEach
            val previous = fields["Name Server"]
            fields["Name Server"] = if (previous == null) nsName else "$previous, $nsName"
        }

        root.getAsJsonObject("secureDNS")?.get("delegationSigned")?.let {
            fields["DNSSEC"] = if (it.asBoolean) "Signed" else "Unsigned"
        }

        root.getAsJsonArray("events")?.forEach { ev ->
            val obj = ev.asJsonObject
            val action = obj.getAsString("eventAction")?.lowercase() ?: return@forEach
            val date = obj.getAsString("eventDate") ?: return@forEach
            when (action) {
                "registration" -> fields["Creation Date"] = date
                "last changed" -> fields["Updated Date"] = date
            }
        }

        root.getAsJsonArray("entities")?.forEach { entity ->
            val obj = entity.asJsonObject
            val roles = obj.getAsJsonArray("roles")?.mapNotNull { it.asString } ?: emptyList()
            if ("registrar" in roles) {
                extractFnFromVcard(obj)?.let { fields["Registrar"] = it }
                obj.getAsJsonArray("publicIds")?.forEach { id ->
                    val idObj = id.asJsonObject
                    val type = idObj.getAsString("type") ?: return@forEach
                    val identifier = idObj.getAsString("identifier") ?: return@forEach
                    if (type.contains("organization number", ignoreCase = true)) {
                        fields["Registrar Organization Number"] = identifier
                    }
                }
            }
        }

        fields["RDAP Source"] = "rdap.norid.no"
        return fields
    }

    private fun extractFnFromVcard(entity: JsonObject): String? {
        val vcardArray = entity.getAsJsonArray("vcardArray") ?: return null
        if (vcardArray.size() < 2) return null
        val values = vcardArray[1].takeIf { it.isJsonArray }?.asJsonArray ?: return null

        for (entry in values) {
            if (!entry.isJsonArray) continue
            val parts = entry.asJsonArray
            if (parts.size() < 4) continue
            if (parts[0].asString.equals("fn", ignoreCase = true)) {
                return parts[3].asString
            }
        }
        return null
    }

    private fun JsonObject.getAsString(key: String): String? {
        val value = get(key) ?: return null
        if (!value.isJsonPrimitive) return null
        return value.asString
    }

}
