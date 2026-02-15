package no.norrs.nortools.tools.whois.arin

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.google.gson.JsonParser
import no.norrs.nortools.lib.cli.BaseCommand
import no.norrs.nortools.lib.network.HttpClient
import java.time.Duration

/**
 * ARIN IP Lookup tool — queries ARIN's RDAP/REST API for IP network information.
 *
 * Looks up IP address ownership, network blocks, and organization details
 * using ARIN's public RDAP API.
 */
class ArinLookupCommand : BaseCommand(
    name = "arin",
    helpText = "Look up IP address information from ARIN (American Registry for Internet Numbers)",
) {
    private val ip by argument(help = "IP address to look up")

    override fun run() {
        val formatter = createFormatter()
        val httpClient = HttpClient(timeout = Duration.ofSeconds(timeoutSeconds.toLong()))

        // Query RDAP for IP info
        val rdapUrl = "https://rdap.arin.net/registry/ip/$ip"
        val result = httpClient.get(rdapUrl, includeBody = true)

        if (result.statusCode != 200 || result.body == null) {
            echo("ARIN lookup failed for $ip: HTTP ${result.statusCode}")
            if (result.error != null) echo("Error: ${result.error}")
            return
        }

        try {
            val json = JsonParser.parseString(result.body).asJsonObject
            val details = linkedMapOf<String, Any?>(
                "IP" to ip,
            )

            // Network name
            val name = json.get("name")?.asString
            if (name != null) details["Network Name"] = name

            // Handle
            val handle = json.get("handle")?.asString
            if (handle != null) details["Handle"] = handle

            // CIDR blocks
            val cidrs = json.getAsJsonArray("cidr0_cidrs")
            if (cidrs != null && cidrs.size() > 0) {
                val cidrList = cidrs.map { cidr ->
                    val obj = cidr.asJsonObject
                    val prefix = obj.get("v4prefix")?.asString ?: obj.get("v6prefix")?.asString ?: ""
                    val length = obj.get("length")?.asInt ?: 0
                    "$prefix/$length"
                }
                details["CIDR"] = cidrList.joinToString(", ")
            }

            // Start/end addresses
            val startAddr = json.get("startAddress")?.asString
            val endAddr = json.get("endAddress")?.asString
            if (startAddr != null) details["Start Address"] = startAddr
            if (endAddr != null) details["End Address"] = endAddr

            // IP version
            val ipVersion = json.get("ipVersion")?.asString
            if (ipVersion != null) details["IP Version"] = ipVersion

            // Type
            val type = json.get("type")?.asString
            if (type != null) details["Type"] = type

            // Entities (organizations)
            val entities = json.getAsJsonArray("entities")
            if (entities != null) {
                for (entity in entities) {
                    val entityObj = entity.asJsonObject
                    val roles = entityObj.getAsJsonArray("roles")?.map { it.asString }
                    val vcardArray = entityObj.getAsJsonArray("vcardArray")
                    if (vcardArray != null && vcardArray.size() > 1) {
                        val cards = vcardArray[1].asJsonArray
                        for (card in cards) {
                            val cardArr = card.asJsonArray
                            if (cardArr[0].asString == "fn") {
                                val orgName = cardArr[3].asString
                                val roleStr = roles?.joinToString(", ") ?: ""
                                details["Organization ($roleStr)"] = orgName
                            }
                        }
                    }
                }
            }

            // Events (dates)
            val events = json.getAsJsonArray("events")
            if (events != null) {
                for (event in events) {
                    val eventObj = event.asJsonObject
                    val action = eventObj.get("eventAction")?.asString ?: continue
                    val date = eventObj.get("eventDate")?.asString ?: continue
                    details[action.replaceFirstChar { it.uppercase() }] = date
                }
            }

            echo(formatter.formatDetail(details))
        } catch (e: Exception) {
            echo("Failed to parse ARIN response: ${e.message}")
        }
    }
}

fun main(args: Array<String>) = ArinLookupCommand().main(args)
