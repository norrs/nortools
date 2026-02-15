package no.norrs.nortools.tools.email.headeranalyzer

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import no.norrs.nortools.lib.cli.BaseCommand
import java.io.File

/**
 * Email Header Analyzer tool — parses and analyzes email headers.
 *
 * Parses RFC 5322 email headers, showing the routing path (Received headers),
 * authentication results (SPF, DKIM, DMARC), and key metadata.
 */
class HeaderAnalyzerCommand : BaseCommand(
    name = "header-analyzer",
    helpText = "Analyze email headers from a file or stdin",
) {
    private val file by argument(help = "File containing email headers (or - for stdin)").optional()
    private val showAll by option("--all", "-a", help = "Show all headers, not just key ones").flag()

    override fun run() {
        val formatter = createFormatter()

        val headerText = when {
            file == null || file == "-" -> System.`in`.bufferedReader().readText()
            else -> File(file!!).readText()
        }

        val headers = parseHeaders(headerText)

        if (headers.isEmpty()) {
            echo("No headers found in input")
            return
        }

        // Show routing path (Received headers in reverse order)
        val receivedHeaders = headers.filter { it.first.equals("Received", ignoreCase = true) }
        if (receivedHeaders.isNotEmpty()) {
            echo("=== Routing Path (${receivedHeaders.size} hops) ===")
            val hops = receivedHeaders.reversed().mapIndexed { index, (_, value) ->
                val from = extractField(value, "from")
                val by = extractField(value, "by")
                val dateStr = value.substringAfterLast(";", "").trim()
                mapOf(
                    "Hop" to "${index + 1}",
                    "From" to (from ?: "unknown"),
                    "By" to (by ?: "unknown"),
                    "Date" to dateStr,
                )
            }
            echo(formatter.format(hops))
            echo()
        }

        // Show authentication results
        val authResults = headers.filter {
            it.first.equals("Authentication-Results", ignoreCase = true)
        }
        if (authResults.isNotEmpty()) {
            echo("=== Authentication Results ===")
            for ((_, value) in authResults) {
                val spf = extractAuthResult(value, "spf")
                val dkim = extractAuthResult(value, "dkim")
                val dmarc = extractAuthResult(value, "dmarc")
                val details = linkedMapOf<String, Any?>()
                if (spf != null) details["SPF"] = spf
                if (dkim != null) details["DKIM"] = dkim
                if (dmarc != null) details["DMARC"] = dmarc
                if (details.isNotEmpty()) echo(formatter.formatDetail(details))
            }
            echo()
        }

        // Show key headers
        echo("=== Key Headers ===")
        val keyHeaderNames = listOf(
            "From", "To", "Subject", "Date", "Message-ID",
            "Return-Path", "Reply-To", "DKIM-Signature",
            "X-Mailer", "X-Originating-IP", "List-Unsubscribe",
        )
        val keyHeaders = if (showAll) {
            headers.filter { !it.first.equals("Received", ignoreCase = true) }
        } else {
            headers.filter { header ->
                keyHeaderNames.any { it.equals(header.first, ignoreCase = true) }
            }
        }

        val details = linkedMapOf<String, Any?>()
        for ((name, value) in keyHeaders) {
            val displayValue = if (value.length > 120) value.take(120) + "..." else value
            details[name] = displayValue
        }
        echo(formatter.formatDetail(details))
    }

    private fun parseHeaders(text: String): List<Pair<String, String>> {
        val headers = mutableListOf<Pair<String, String>>()
        var currentName = ""
        var currentValue = StringBuilder()

        for (line in text.lines()) {
            if (line.isBlank()) break // End of headers
            if (line.startsWith(" ") || line.startsWith("\t")) {
                // Continuation line (folded header)
                currentValue.append(" ").append(line.trim())
            } else {
                if (currentName.isNotEmpty()) {
                    headers.add(currentName to currentValue.toString())
                }
                val colonIndex = line.indexOf(':')
                if (colonIndex > 0) {
                    currentName = line.substring(0, colonIndex).trim()
                    currentValue = StringBuilder(line.substring(colonIndex + 1).trim())
                }
            }
        }
        if (currentName.isNotEmpty()) {
            headers.add(currentName to currentValue.toString())
        }
        return headers
    }

    private fun extractField(received: String, field: String): String? {
        val pattern = "$field\\s+([^;]+)".toRegex(RegexOption.IGNORE_CASE)
        return pattern.find(received)?.groupValues?.get(1)?.trim()?.split("\\s+".toRegex())?.firstOrNull()
    }

    private fun extractAuthResult(value: String, method: String): String? {
        val pattern = "$method=([a-zA-Z]+)".toRegex(RegexOption.IGNORE_CASE)
        return pattern.find(value)?.groupValues?.get(1)
    }
}

fun main(args: Array<String>) = HeaderAnalyzerCommand().main(args)
