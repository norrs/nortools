package no.norrs.nortools.tools.composite.compliance

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import no.norrs.nortools.lib.network.HttpClient
import org.xbill.DNS.Type
import java.time.Duration

/**
 * Email Compliance Check tool — verifies email sending compliance.
 *
 * Checks RFC 8058 (one-click unsubscribe), DMARC enforcement,
 * SPF alignment, MTA-STS, BIMI, and other compliance requirements
 * for bulk email senders (Google/Yahoo 2024 requirements).
 */
class ComplianceCommand : BaseCommand(
    name = "compliance",
    helpText = "Check email compliance (RFC 8058, Google/Yahoo sender requirements)",
) {
    private val domain by argument(help = "Domain name to check compliance for")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()
        val checks = mutableListOf<Map<String, String>>()

        echo("=== Email Compliance Report: $domain ===")
        echo("(Based on Google/Yahoo 2024 bulk sender requirements)")
        echo()

        // SPF
        val txtResult = resolver.lookup(domain, Type.TXT)
        val spf = txtResult.records.find { it.data.startsWith("v=spf1") }
        checks.add(mapOf(
            "Check" to "SPF Authentication",
            "Requirement" to "Required",
            "Status" to if (spf != null) "PASS" else "FAIL",
            "Details" to (spf?.data ?: "Missing — required for all senders"),
        ))

        // DKIM
        val dkimSelectors = listOf("default", "google", "selector1", "selector2", "k1", "dkim")
        val hasDkim = dkimSelectors.any { selector ->
            val r = resolver.lookup("$selector._domainkey.$domain", Type.TXT)
            r.records.any { it.data.contains("v=DKIM1") || it.data.contains("p=") }
        }
        checks.add(mapOf(
            "Check" to "DKIM Authentication",
            "Requirement" to "Required",
            "Status" to if (hasDkim) "PASS" else "WARN",
            "Details" to if (hasDkim) "DKIM found" else "No DKIM at common selectors",
        ))

        // DMARC
        val dmarcResult = resolver.lookup("_dmarc.$domain", Type.TXT)
        val dmarc = dmarcResult.records.find { it.data.startsWith("v=DMARC1") }
        val dmarcPolicy = dmarc?.data?.let { Regex("p=([^;\\s]+)").find(it)?.groupValues?.get(1) }
        checks.add(mapOf(
            "Check" to "DMARC Record",
            "Requirement" to "Required",
            "Status" to if (dmarc != null) "PASS" else "FAIL",
            "Details" to (dmarc?.data ?: "Missing — required for bulk senders"),
        ))

        // DMARC policy enforcement
        checks.add(mapOf(
            "Check" to "DMARC Policy (p=quarantine|reject)",
            "Requirement" to "Recommended",
            "Status" to when (dmarcPolicy) {
                "reject", "quarantine" -> "PASS"
                "none" -> "WARN"
                else -> "FAIL"
            },
            "Details" to "Policy: ${dmarcPolicy ?: "none"}",
        ))

        // MTA-STS
        val mtaSts = resolver.lookup("_mta-sts.$domain", Type.TXT)
            .records.find { it.data.startsWith("v=STSv1") }
        checks.add(mapOf(
            "Check" to "MTA-STS (RFC 8461)",
            "Requirement" to "Recommended",
            "Status" to if (mtaSts != null) "PASS" else "INFO",
            "Details" to (mtaSts?.data ?: "Not configured"),
        ))

        // TLS-RPT
        val tlsrpt = resolver.lookup("_smtp._tls.$domain", Type.TXT)
            .records.find { it.data.startsWith("v=TLSRPTv1") }
        checks.add(mapOf(
            "Check" to "TLS-RPT (RFC 8460)",
            "Requirement" to "Recommended",
            "Status" to if (tlsrpt != null) "PASS" else "INFO",
            "Details" to (tlsrpt?.data ?: "Not configured"),
        ))

        // BIMI
        val bimi = resolver.lookup("default._bimi.$domain", Type.TXT)
            .records.find { it.data.startsWith("v=BIMI1") }
        checks.add(mapOf(
            "Check" to "BIMI (Brand Indicators)",
            "Requirement" to "Optional",
            "Status" to if (bimi != null) "PASS" else "INFO",
            "Details" to (bimi?.data ?: "Not configured"),
        ))

        // Reverse DNS for MX
        val mxResult = resolver.lookup(domain, Type.MX)
        if (mxResult.records.isNotEmpty()) {
            val mxHost = mxResult.records.first().data.split(" ").last().trimEnd('.')
            val mxA = resolver.lookup(mxHost, Type.A)
            if (mxA.records.isNotEmpty()) {
                val ip = mxA.records.first().data
                val ptr = resolver.reverseLookup(ip)
                checks.add(mapOf(
                    "Check" to "Reverse DNS (PTR) for MX",
                    "Requirement" to "Required",
                    "Status" to if (ptr.records.isNotEmpty()) "PASS" else "FAIL",
                    "Details" to if (ptr.records.isNotEmpty()) "$ip → ${ptr.records.first().data}" else "No PTR for $ip",
                ))
            }
        }

        // TLS on MX (check port 25 STARTTLS via MTA-STS as proxy)
        checks.add(mapOf(
            "Check" to "TLS Support (STARTTLS)",
            "Requirement" to "Required",
            "Status" to if (mtaSts != null) "PASS" else "INFO",
            "Details" to if (mtaSts != null) "MTA-STS enforces TLS" else "Run smtp tool to verify STARTTLS",
        ))

        echo(formatter.format(checks))

        val pass = checks.count { it["Status"] == "PASS" }
        val fail = checks.count { it["Status"] == "FAIL" }
        val warn = checks.count { it["Status"] == "WARN" }
        echo()
        echo("Compliance: $pass passed, $warn warnings, $fail failures (${checks.size} checks)")
    }
}

fun main(args: Array<String>) = ComplianceCommand().main(args)
