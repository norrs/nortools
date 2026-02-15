package no.norrs.nortools.lib.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import no.norrs.nortools.lib.dns.DnsResolver
import no.norrs.nortools.lib.output.OutputFormatter

/**
 * Base command providing common flags shared across all nortools CLI tools.
 *
 * Provides:
 * - --json: Output in JSON format
 * - --server: Custom DNS server to use
 * - --timeout: Query timeout in seconds
 */
abstract class BaseCommand(
    name: String,
    private val helpText: String,
) : CliktCommand(name = name) {

    override fun help(context: Context): String = helpText

    val jsonOutput by option("--json", help = "Output results in JSON format")
        .flag(default = false)

    val dnsServer by option("--server", "-s", help = "DNS server to query (default: system resolver)")

    val timeoutSeconds by option("--timeout", "-t", help = "Query timeout in seconds")
        .int()
        .default(10)

    /**
     * Create a DnsResolver with the common options applied.
     */
    protected fun createResolver(): DnsResolver {
        return DnsResolver(
            server = dnsServer,
            timeout = java.time.Duration.ofSeconds(timeoutSeconds.toLong()),
        )
    }

    /**
     * Create an OutputFormatter with the common options applied.
     */
    protected fun createFormatter(): OutputFormatter {
        return OutputFormatter(json = jsonOutput)
    }
}
