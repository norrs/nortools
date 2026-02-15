package no.norrs.nortools.tools.util.dnshealth

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.Type

/**
 * DNS Health Check tool — performs comprehensive DNS health checks for a domain.
 *
 * Checks SOA record, NS consistency, MX records, A/AAAA records,
 * DNSSEC status, and common misconfigurations.
 */
class DnsHealthCommand : BaseCommand(
    name = "dns-health",
    helpText = "Perform a comprehensive DNS health check for a domain",
) {
    private val domain by argument(help = "Domain name to check")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()
        val checks = mutableListOf<Map<String, String>>()

        // Check SOA record
        val soaResult = resolver.lookup(domain, Type.SOA)
        checks.add(
            mapOf(
                "Check" to "SOA Record",
                "Status" to if (soaResult.isSuccessful && soaResult.records.isNotEmpty()) "PASS" else "FAIL",
                "Details" to if (soaResult.records.isNotEmpty()) soaResult.records.first().data else "No SOA record found",
            )
        )

        // Check NS records
        val nsResult = resolver.lookup(domain, Type.NS)
        val nsCount = nsResult.records.size
        checks.add(
            mapOf(
                "Check" to "NS Records",
                "Status" to when {
                    nsCount >= 2 -> "PASS"
                    nsCount == 1 -> "WARN"
                    else -> "FAIL"
                },
                "Details" to if (nsCount > 0) "$nsCount nameservers: ${nsResult.records.joinToString(", ") { it.data }}" else "No NS records found",
            )
        )

        // Check A record
        val aResult = resolver.lookup(domain, Type.A)
        checks.add(
            mapOf(
                "Check" to "A Record",
                "Status" to if (aResult.isSuccessful && aResult.records.isNotEmpty()) "PASS" else "INFO",
                "Details" to if (aResult.records.isNotEmpty()) aResult.records.joinToString(", ") { it.data } else "No A records",
            )
        )

        // Check AAAA record (IPv6)
        val aaaaResult = resolver.lookup(domain, Type.AAAA)
        checks.add(
            mapOf(
                "Check" to "AAAA Record (IPv6)",
                "Status" to if (aaaaResult.isSuccessful && aaaaResult.records.isNotEmpty()) "PASS" else "WARN",
                "Details" to if (aaaaResult.records.isNotEmpty()) aaaaResult.records.joinToString(", ") { it.data } else "No AAAA records — no IPv6 support",
            )
        )

        // Check MX records
        val mxResult = resolver.lookup(domain, Type.MX)
        checks.add(
            mapOf(
                "Check" to "MX Records",
                "Status" to if (mxResult.isSuccessful && mxResult.records.isNotEmpty()) "PASS" else "INFO",
                "Details" to if (mxResult.records.isNotEmpty()) "${mxResult.records.size} MX records found" else "No MX records",
            )
        )

        // Check SPF
        val txtResult = resolver.lookup(domain, Type.TXT)
        val spfRecord = txtResult.records.find { it.data.startsWith("v=spf1") }
        checks.add(
            mapOf(
                "Check" to "SPF Record",
                "Status" to if (spfRecord != null) "PASS" else "WARN",
                "Details" to (spfRecord?.data ?: "No SPF record found"),
            )
        )

        // Check DMARC
        val dmarcResult = resolver.lookup("_dmarc.$domain", Type.TXT)
        val dmarcRecord = dmarcResult.records.find { it.data.startsWith("v=DMARC1") }
        checks.add(
            mapOf(
                "Check" to "DMARC Record",
                "Status" to if (dmarcRecord != null) "PASS" else "WARN",
                "Details" to (dmarcRecord?.data ?: "No DMARC record found"),
            )
        )

        // Check DNSSEC
        val dnskeyResult = resolver.dnssecLookup(domain, Type.DNSKEY)
        checks.add(
            mapOf(
                "Check" to "DNSSEC",
                "Status" to if (dnskeyResult.isSuccessful && dnskeyResult.records.isNotEmpty()) "PASS" else "INFO",
                "Details" to if (dnskeyResult.records.isNotEmpty()) "${dnskeyResult.records.size} DNSKEY records found" else "DNSSEC not enabled",
            )
        )

        // Check CAA record
        val caaResult = resolver.lookup(domain, Type.CAA)
        checks.add(
            mapOf(
                "Check" to "CAA Record",
                "Status" to if (caaResult.isSuccessful && caaResult.records.isNotEmpty()) "PASS" else "INFO",
                "Details" to if (caaResult.records.isNotEmpty()) caaResult.records.joinToString(", ") { it.data } else "No CAA records — any CA can issue certificates",
            )
        )

        echo("DNS Health Check: $domain")
        echo()
        echo(formatter.format(checks))

        // Summary
        val passCount = checks.count { it["Status"] == "PASS" }
        val warnCount = checks.count { it["Status"] == "WARN" }
        val failCount = checks.count { it["Status"] == "FAIL" }
        echo()
        echo("Summary: $passCount passed, $warnCount warnings, $failCount failures out of ${checks.size} checks")
    }
}

fun main(args: Array<String>) = DnsHealthCommand().main(args)
