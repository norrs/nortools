package no.norrs.nortools.tools.email.mtasts

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import no.norrs.nortools.lib.network.HttpClient
import org.xbill.DNS.Type

/**
 * MTA-STS Record Lookup tool — queries MTA-STS DNS record and fetches the policy.
 *
 * MTA-STS (Mail Transfer Agent Strict Transport Security) allows mail
 * service providers to declare their ability to receive TLS-secured
 * connections and to specify whether sending SMTP servers should refuse
 * to deliver to MX hosts that do not offer TLS.
 * Uses RFC 8461 (MTA Strict Transport Security).
 */
class MtaStsLookupCommand : BaseCommand(
    name = "mta-sts",
    helpText = "Look up MTA-STS record and policy for a domain",
) {
    private val domain by argument(help = "Domain name to look up MTA-STS for")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()

        // Step 1: Look up the _mta-sts TXT record
        val stsDomain = "_mta-sts.$domain"
        val result = resolver.lookup(stsDomain, Type.TXT)

        val stsRecords = if (result.isSuccessful) {
            result.records.filter { it.data.startsWith("v=STSv1") }
        } else {
            emptyList()
        }

        val details = linkedMapOf<String, Any?>()
        details["Domain"] = domain

        if (stsRecords.isNotEmpty()) {
            val stsRecord = stsRecords.first().data
            val tags = stsRecord.split(";").associate { tag ->
                val parts = tag.trim().split("=", limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else "" to ""
            }
            details["MTA-STS Record"] = stsRecord
            details["Version"] = tags["v"] ?: "STSv1"
            details["ID"] = tags["id"] ?: "not specified"
            details["TTL"] = "${stsRecords.first().ttl}s"
        } else {
            details["MTA-STS Record"] = "NOT FOUND"
        }

        // Step 2: Fetch the MTA-STS policy file
        val policyUrl = "https://mta-sts.$domain/.well-known/mta-sts.txt"
        details["Policy URL"] = policyUrl

        try {
            val httpClient = HttpClient(timeout = java.time.Duration.ofSeconds(timeoutSeconds.toLong()))
            val httpResult = httpClient.get(policyUrl, includeBody = true)
            if (httpResult.statusCode in 200..299 && httpResult.body != null) {
                details["Policy Status"] = "Found (HTTP ${httpResult.statusCode})"
                val policyLines = httpResult.body!!.lines().filter { it.isNotBlank() }
                for (line in policyLines) {
                    val parts = line.split(":", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val value = parts[1].trim()
                        when (key) {
                            "version" -> details["Policy Version"] = value
                            "mode" -> details["Mode"] = when (value) {
                                "enforce" -> "enforce (require TLS)"
                                "testing" -> "testing (report only)"
                                "none" -> "none (disabled)"
                                else -> value
                            }
                            "mx" -> {
                                val existing = details["MX Patterns"] as? String
                                details["MX Patterns"] = if (existing != null) "$existing, $value" else value
                            }
                            "max_age" -> details["Max Age"] = "${value}s (${value.toLongOrNull()?.div(86400) ?: "?"}d)"
                        }
                    }
                }
            } else {
                details["Policy Status"] = "Not found (HTTP ${httpResult.statusCode})"
            }
        } catch (e: Exception) {
            details["Policy Status"] = "Error: ${e.message}"
        }

        echo(formatter.formatDetail(details))
    }
}

fun main(args: Array<String>) = MtaStsLookupCommand().main(args)
