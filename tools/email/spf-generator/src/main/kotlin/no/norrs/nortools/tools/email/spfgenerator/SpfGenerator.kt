package no.norrs.nortools.tools.email.spfgenerator

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import no.norrs.nortools.lib.cli.BaseCommand

/**
 * SPF Record Generator tool — builds SPF records from provided parameters.
 *
 * Generates a valid SPF TXT record based on the specified mechanisms.
 * Uses RFC 7208 (Sender Policy Framework).
 */
class SpfGeneratorCommand : BaseCommand(
    name = "spf-generator",
    helpText = "Generate an SPF record from specified mechanisms",
) {
    private val includes by option("--include", "-i", help = "Include domain (can be repeated)")
        .multiple()
    private val ip4s by option("--ip4", help = "Allowed IPv4 address or CIDR (can be repeated)")
        .multiple()
    private val ip6s by option("--ip6", help = "Allowed IPv6 address or CIDR (can be repeated)")
        .multiple()
    private val useMx by option("--mx", help = "Allow domain's MX servers")
        .default("")
    private val useA by option("--a", help = "Allow domain's A record")
        .default("")
    private val redirect by option("--redirect", help = "Redirect to another domain's SPF")
    private val allPolicy by option("--all", help = "Policy for 'all' mechanism: fail(-all), softfail(~all), neutral(?all), pass(+all)")
        .default("softfail")

    override fun run() {
        val formatter = createFormatter()
        val parts = mutableListOf("v=spf1")

        // Add 'a' mechanism
        if (useA.isNotEmpty()) {
            parts.add("a:$useA")
        }

        // Add 'mx' mechanism
        if (useMx.isNotEmpty()) {
            parts.add("mx:$useMx")
        }

        // Add includes
        for (include in includes) {
            parts.add("include:$include")
        }

        // Add IP4 addresses
        for (ip4 in ip4s) {
            parts.add("ip4:$ip4")
        }

        // Add IP6 addresses
        for (ip6 in ip6s) {
            parts.add("ip6:$ip6")
        }

        // Add redirect or all
        if (redirect != null) {
            parts.add("redirect=$redirect")
        } else {
            val allMechanism = when (allPolicy.lowercase()) {
                "fail", "hardfail" -> "-all"
                "softfail" -> "~all"
                "neutral" -> "?all"
                "pass" -> "+all"
                else -> "~all"
            }
            parts.add(allMechanism)
        }

        val spfRecord = parts.joinToString(" ")

        // Validate
        val warnings = mutableListOf<String>()
        if (spfRecord.length > 255) {
            warnings.add("Record exceeds 255 characters (${spfRecord.length}). May need to split across multiple strings.")
        }
        val lookupCount = includes.size +
            (if (useA.isNotEmpty()) 1 else 0) +
            (if (useMx.isNotEmpty()) 1 else 0) +
            (if (redirect != null) 1 else 0)
        if (lookupCount > 10) {
            warnings.add("Exceeds 10 DNS lookup limit ($lookupCount lookups). RFC 7208 violation.")
        }

        val details = linkedMapOf<String, Any?>(
            "SPF Record" to spfRecord,
            "DNS Lookups" to "$lookupCount / 10",
            "Record Length" to "${spfRecord.length} chars",
        )
        if (warnings.isNotEmpty()) {
            details["Warnings"] = warnings.joinToString("; ")
        }

        echo(formatter.formatDetail(details))
        echo()
        echo("TXT Record Value:")
        echo("\"$spfRecord\"")
    }
}

fun main(args: Array<String>) = SpfGeneratorCommand().main(args)
