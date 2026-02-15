package no.norrs.nortools.tools.composite.domainhealth

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import no.norrs.nortools.lib.network.HttpClient
import org.xbill.DNS.Type
import java.time.Duration

/**
 * Domain Health Report tool — comprehensive domain health check.
 *
 * Combines DNS, email authentication, DNSSEC, and web checks into
 * a single comprehensive report with pass/warn/fail status for each check.
 */
class DomainHealthCommand : BaseCommand(
    name = "domain-health",
    helpText = "Comprehensive domain health report (DNS, email, DNSSEC, web)",
) {
    private val domain by argument(help = "Domain name to check")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()
        val checks = mutableListOf<Map<String, String>>()

        echo("=== Domain Health Report: $domain ===")
        echo()

        // --- DNS Checks ---
        echo("--- DNS ---")
        val soaResult = resolver.lookup(domain, Type.SOA)
        checks.add(check("SOA Record", soaResult.isSuccessful && soaResult.records.isNotEmpty(),
            if (soaResult.records.isNotEmpty()) soaResult.records.first().data else "Missing"))

        val nsResult = resolver.lookup(domain, Type.NS)
        checks.add(check("NS Records (≥2)", nsResult.records.size >= 2,
            "${nsResult.records.size} nameservers", warn = nsResult.records.size == 1))

        val aResult = resolver.lookup(domain, Type.A)
        checks.add(check("A Record", aResult.records.isNotEmpty(),
            if (aResult.records.isNotEmpty()) aResult.records.joinToString(", ") { it.data } else "None", info = true))

        val aaaaResult = resolver.lookup(domain, Type.AAAA)
        checks.add(check("AAAA Record (IPv6)", aaaaResult.records.isNotEmpty(),
            if (aaaaResult.records.isNotEmpty()) aaaaResult.records.joinToString(", ") { it.data } else "No IPv6", warn = true))

        // --- Email Authentication ---
        echo("--- Email Authentication ---")
        val mxResult = resolver.lookup(domain, Type.MX)
        checks.add(check("MX Records", mxResult.records.isNotEmpty(),
            if (mxResult.records.isNotEmpty()) "${mxResult.records.size} MX records" else "None", info = true))

        val txtResult = resolver.lookup(domain, Type.TXT)
        val spf = txtResult.records.find { it.data.startsWith("v=spf1") }
        checks.add(check("SPF Record", spf != null, spf?.data ?: "Missing"))

        val dmarcResult = resolver.lookup("_dmarc.$domain", Type.TXT)
        val dmarc = dmarcResult.records.find { it.data.startsWith("v=DMARC1") }
        checks.add(check("DMARC Record", dmarc != null, dmarc?.data ?: "Missing"))

        // --- DNSSEC ---
        echo("--- DNSSEC ---")
        val dnskeyResult = resolver.dnssecLookup(domain, Type.DNSKEY)
        checks.add(check("DNSSEC (DNSKEY)", dnskeyResult.records.isNotEmpty(),
            if (dnskeyResult.records.isNotEmpty()) "${dnskeyResult.records.size} keys" else "Not enabled", info = true))

        val dsResult = resolver.dnssecLookup(domain, Type.DS)
        checks.add(check("DNSSEC (DS)", dsResult.records.isNotEmpty(),
            if (dsResult.records.isNotEmpty()) "${dsResult.records.size} DS records" else "Not enabled", info = true))

        // --- Web ---
        echo("--- Web ---")
        try {
            val httpClient = HttpClient(timeout = Duration.ofSeconds(timeoutSeconds.toLong()))
            val httpsResult = httpClient.get("https://$domain", includeBody = false)
            checks.add(check("HTTPS Accessible", httpsResult.statusCode in 200..399,
                "HTTP ${httpsResult.statusCode} (${httpsResult.responseTimeMs}ms)"))

            val httpResult = httpClient.get("http://$domain", includeBody = false)
            val redirectsToHttps = httpResult.statusCode in 300..399
            checks.add(check("HTTP→HTTPS Redirect", redirectsToHttps,
                if (redirectsToHttps) "Redirects to HTTPS" else "HTTP ${httpResult.statusCode}", warn = true))
        } catch (e: Exception) {
            checks.add(check("HTTPS Accessible", false, "Error: ${e.message}"))
        }

        // CAA
        val caaResult = resolver.lookup(domain, Type.CAA)
        checks.add(check("CAA Record", caaResult.records.isNotEmpty(),
            if (caaResult.records.isNotEmpty()) caaResult.records.joinToString(", ") { it.data } else "Not set", info = true))

        echo()
        echo(formatter.format(checks))

        // Summary
        val pass = checks.count { it["Status"] == "PASS" }
        val warn = checks.count { it["Status"] == "WARN" }
        val fail = checks.count { it["Status"] == "FAIL" }
        val info = checks.count { it["Status"] == "INFO" }
        echo()
        echo("Score: $pass passed, $warn warnings, $fail failures, $info informational (${checks.size} total)")
    }

    private fun check(name: String, passed: Boolean, details: String, warn: Boolean = false, info: Boolean = false): Map<String, String> {
        val status = when {
            passed -> "PASS"
            info -> "INFO"
            warn -> "WARN"
            else -> "FAIL"
        }
        return mapOf("Check" to name, "Status" to status, "Details" to details)
    }
}

fun main(args: Array<String>) = DomainHealthCommand().main(args)
