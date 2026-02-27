package no.norrs.nortools.tools.whois.whois

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import no.norrs.nortools.lib.cli.BaseCommand
import no.norrs.nortools.tools.whois.common.WhoisClient

/**
 * WHOIS Lookup tool — queries WHOIS servers for domain registration information.
 */
class WhoisLookupCommand : BaseCommand(
    name = "whois",
    helpText = "Look up WHOIS registration information for a domain or IP",
) {
    private val query by argument(help = "Domain name or IP address to look up")
    private val whoisServer by option("--whois-server", "-w", help = "WHOIS server to query")
        .default("")
    private val raw by option("--raw", "-r", help = "Show raw WHOIS response").flag()

    override fun run() {
        val formatter = createFormatter()
        val result = WhoisClient.lookup(
            query = query,
            serverOverride = whoisServer.takeIf { it.isNotBlank() },
            timeoutMillis = timeoutSeconds * 1000,
        )

        if (raw) {
            echo(result.combinedRaw())
            return
        }

        val details = linkedMapOf<String, Any?>(
            "Query" to query,
            "WHOIS Server" to result.finalServer,
        )

        if (result.hops.size > 1) {
            details["Lookup Chain"] = result.hops.joinToString(" -> ") { it.server }
        }

        for ((key, value) in result.mergedFields) {
            details[key] = value
        }

        echo(formatter.formatDetail(details))
    }
}

fun main(args: Array<String>) = WhoisLookupCommand().main(args)
