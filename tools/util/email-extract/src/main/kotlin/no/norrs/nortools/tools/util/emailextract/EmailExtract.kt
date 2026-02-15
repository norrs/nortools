package no.norrs.nortools.tools.util.emailextract

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import no.norrs.nortools.lib.cli.BaseCommand
import java.io.File

/**
 * Email Extractor tool — extracts email addresses from text input.
 *
 * Reads from a file or stdin and extracts all RFC 5322 compliant email addresses.
 * Can deduplicate and sort results.
 */
class EmailExtractCommand : BaseCommand(
    name = "email-extract",
    helpText = "Extract email addresses from text input (file or stdin)",
) {
    private val file by argument(help = "File to extract emails from (reads stdin if omitted)").optional()
    private val unique by option("--unique", "-u", help = "Only show unique email addresses").flag()
    private val sortOutput by option("--sort", help = "Sort email addresses alphabetically").flag()
    private val domainOnly by option("--domain", "-d", help = "Extract only domains from email addresses").flag()

    // RFC 5322 compatible email regex (simplified but practical)
    private val emailRegex = Regex(
        "[a-zA-Z0-9.!#\$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*"
    )

    override fun run() {
        val formatter = createFormatter()

        val input = if (file != null) {
            val f = File(file!!)
            if (!f.exists()) {
                echo("File not found: $file")
                return
            }
            f.readText()
        } else {
            System.`in`.bufferedReader().readText()
        }

        var emails = emailRegex.findAll(input).map { it.value }.toList()

        if (unique) {
            emails = emails.distinct()
        }

        if (sortOutput) {
            emails = emails.sorted()
        }

        if (domainOnly) {
            var domains = emails.map { it.substringAfter("@") }
            if (unique) domains = domains.distinct()
            if (sortOutput) domains = domains.sorted()

            val rows = domains.map { mapOf("Domain" to it) }
            echo("Found ${domains.size} domains")
            echo()
            echo(formatter.format(rows))
        } else {
            val rows = emails.map { email ->
                mapOf(
                    "Email" to email,
                    "Local Part" to email.substringBefore("@"),
                    "Domain" to email.substringAfter("@"),
                )
            }
            echo("Found ${emails.size} email addresses")
            echo()
            echo(formatter.format(rows))
        }
    }
}

fun main(args: Array<String>) = EmailExtractCommand().main(args)
