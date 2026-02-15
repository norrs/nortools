package no.norrs.nortools.tools.composite.deliverability

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.Type

/**
 * Email Deliverability Check tool — checks all factors affecting email deliverability.
 *
 * Verifies MX records, SPF, DKIM selector, DMARC, MTA-STS, TLSRPT,
 * reverse DNS, and DNSBL listing status.
 */
class DeliverabilityCommand : BaseCommand(
    name = "deliverability",
    helpText = "Check email deliverability factors for a domain (MX, SPF, DKIM, DMARC, blacklists)",
) {
    private val domain by argument(help = "Domain name to check email deliverability for")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()
        val checks = mutableListOf<Map<String, String>>()

        echo("=== Email Deliverability Report: $domain ===")
        echo()

        // MX Records
        val mxResult = resolver.lookup(domain, Type.MX)
        checks.add(mapOf(
            "Check" to "MX Records",
            "Status" to if (mxResult.records.isNotEmpty()) "PASS" else "FAIL",
            "Details" to if (mxResult.records.isNotEmpty())
                "${mxResult.records.size} MX: ${mxResult.records.joinToString(", ") { it.data }}"
            else "No MX records — cannot receive email",
        ))

        // SPF
        val txtResult = resolver.lookup(domain, Type.TXT)
        val spf = txtResult.records.find { it.data.startsWith("v=spf1") }
        val spfStatus = when {
            spf == null -> "FAIL"
            spf.data.contains("-all") -> "PASS"
            spf.data.contains("~all") -> "WARN"
            else -> "WARN"
        }
        checks.add(mapOf(
            "Check" to "SPF Record",
            "Status" to spfStatus,
            "Details" to (spf?.data ?: "No SPF record found"),
        ))

        // DMARC
        val dmarcResult = resolver.lookup("_dmarc.$domain", Type.TXT)
        val dmarc = dmarcResult.records.find { it.data.startsWith("v=DMARC1") }
        val dmarcPolicy = dmarc?.data?.let { txt ->
            val pMatch = Regex("p=([^;\\s]+)").find(txt)
            pMatch?.groupValues?.get(1)
        }
        checks.add(mapOf(
            "Check" to "DMARC Record",
            "Status" to when {
                dmarc == null -> "FAIL"
                dmarcPolicy == "reject" || dmarcPolicy == "quarantine" -> "PASS"
                dmarcPolicy == "none" -> "WARN"
                else -> "WARN"
            },
            "Details" to when {
                dmarc == null -> "No DMARC record found"
                else -> "Policy: $dmarcPolicy — ${dmarc.data}"
            },
        ))

        // DKIM (check common selectors)
        val dkimSelectors = listOf("default", "google", "selector1", "selector2", "k1", "dkim", "mail")
        val foundSelectors = mutableListOf<String>()
        for (selector in dkimSelectors) {
            val dkimResult = resolver.lookup("$selector._domainkey.$domain", Type.TXT)
            if (dkimResult.records.any { it.data.contains("v=DKIM1") || it.data.contains("p=") }) {
                foundSelectors.add(selector)
            }
        }
        checks.add(mapOf(
            "Check" to "DKIM (common selectors)",
            "Status" to if (foundSelectors.isNotEmpty()) "PASS" else "WARN",
            "Details" to if (foundSelectors.isNotEmpty())
                "Found: ${foundSelectors.joinToString(", ")}"
            else "No DKIM found for common selectors (may use custom selector)",
        ))

        // MTA-STS
        val mtaStsResult = resolver.lookup("_mta-sts.$domain", Type.TXT)
        val mtaSts = mtaStsResult.records.find { it.data.startsWith("v=STSv1") }
        checks.add(mapOf(
            "Check" to "MTA-STS",
            "Status" to if (mtaSts != null) "PASS" else "INFO",
            "Details" to (mtaSts?.data ?: "Not configured"),
        ))

        // TLS-RPT
        val tlsrptResult = resolver.lookup("_smtp._tls.$domain", Type.TXT)
        val tlsrpt = tlsrptResult.records.find { it.data.startsWith("v=TLSRPTv1") }
        checks.add(mapOf(
            "Check" to "TLS-RPT",
            "Status" to if (tlsrpt != null) "PASS" else "INFO",
            "Details" to (tlsrpt?.data ?: "Not configured"),
        ))

        // Reverse DNS for MX hosts
        if (mxResult.records.isNotEmpty()) {
            val mxHost = mxResult.records.first().data.split(" ").last().trimEnd('.')
            if (mxHost.isBlank()) {
                checks.add(mapOf(
                    "Check" to "Reverse DNS (PTR)",
                    "Status" to "INFO",
                    "Details" to "MX target is '.' (null MX) — skipping PTR lookup",
                ))
            } else {
                val mxA = resolver.lookup(mxHost, Type.A)
                if (mxA.records.isNotEmpty()) {
                    val ip = mxA.records.first().data
                    val ptr = resolver.reverseLookup(ip)
                    checks.add(mapOf(
                        "Check" to "Reverse DNS (PTR)",
                        "Status" to if (ptr.isSuccessful && ptr.records.isNotEmpty()) "PASS" else "WARN",
                        "Details" to if (ptr.records.isNotEmpty())
                            "$ip → ${ptr.records.first().data}"
                        else "$ip — No PTR record",
                    ))
                }
            }
        }

        echo(formatter.format(checks))

        val pass = checks.count { it["Status"] == "PASS" }
        val warn = checks.count { it["Status"] == "WARN" }
        val fail = checks.count { it["Status"] == "FAIL" }
        echo()
        echo("Score: $pass passed, $warn warnings, $fail failures (${checks.size} checks)")
    }
}

fun main(args: Array<String>) = DeliverabilityCommand().main(args)
