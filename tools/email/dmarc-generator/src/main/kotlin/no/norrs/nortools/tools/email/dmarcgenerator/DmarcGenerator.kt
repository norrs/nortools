package no.norrs.nortools.tools.email.dmarcgenerator

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import no.norrs.nortools.lib.cli.BaseCommand

/**
 * DMARC Record Generator tool — builds DMARC records from provided parameters.
 *
 * Generates a valid DMARC TXT record for the _dmarc subdomain.
 * Uses RFC 7489 (DMARC).
 */
class DmarcGeneratorCommand : BaseCommand(
    name = "dmarc-generator",
    helpText = "Generate a DMARC record from specified parameters",
) {
    private val domain by argument(help = "Domain name the DMARC record is for")
    private val policy by option("--policy", "-p", help = "DMARC policy: none, quarantine, reject")
        .default("none")
    private val subdomainPolicy by option("--sp", help = "Subdomain policy: none, quarantine, reject")
    private val pct by option("--pct", help = "Percentage of messages to apply policy to (0-100)")
        .int()
        .default(100)
    private val rua by option("--rua", help = "Aggregate report URI (e.g., mailto:dmarc@example.com)")
    private val ruf by option("--ruf", help = "Forensic report URI (e.g., mailto:forensic@example.com)")
    private val adkim by option("--adkim", help = "DKIM alignment: relaxed (r) or strict (s)")
        .default("r")
    private val aspf by option("--aspf", help = "SPF alignment: relaxed (r) or strict (s)")
        .default("r")
    private val ri by option("--ri", help = "Report interval in seconds")
        .int()
        .default(86400)
    private val fo by option("--fo", help = "Failure reporting options: 0, 1, d, s")
        .default("0")

    override fun run() {
        val formatter = createFormatter()
        val parts = mutableListOf("v=DMARC1")

        parts.add("p=$policy")

        if (subdomainPolicy != null) {
            parts.add("sp=$subdomainPolicy")
        }

        if (pct != 100) {
            parts.add("pct=$pct")
        }

        if (adkim != "r") {
            parts.add("adkim=$adkim")
        }

        if (aspf != "r") {
            parts.add("aspf=$aspf")
        }

        if (rua != null) {
            parts.add("rua=$rua")
        }

        if (ruf != null) {
            parts.add("ruf=$ruf")
        }

        if (ri != 86400) {
            parts.add("ri=$ri")
        }

        if (fo != "0") {
            parts.add("fo=$fo")
        }

        val dmarcRecord = parts.joinToString("; ")

        val details = linkedMapOf<String, Any?>(
            "Domain" to domain,
            "DMARC Record" to dmarcRecord,
            "DNS Name" to "_dmarc.$domain",
            "Policy" to when (policy) {
                "none" -> "none (monitor only)"
                "quarantine" -> "quarantine (mark as spam)"
                "reject" -> "reject (block delivery)"
                else -> policy
            },
            "DKIM Alignment" to if (adkim == "s") "strict" else "relaxed",
            "SPF Alignment" to if (aspf == "s") "strict" else "relaxed",
            "Percentage" to "$pct%",
            "Aggregate Reports" to (rua ?: "not configured"),
            "Forensic Reports" to (ruf ?: "not configured"),
        )

        echo(formatter.formatDetail(details))
        echo()
        echo("DNS TXT Record:")
        echo("_dmarc.$domain  IN  TXT  \"$dmarcRecord\"")
    }
}

fun main(args: Array<String>) = DmarcGeneratorCommand().main(args)
