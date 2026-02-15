package no.norrs.nortools.tools.network.http

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import no.norrs.nortools.lib.cli.BaseCommand
import no.norrs.nortools.lib.network.HttpClient
import java.time.Duration

/**
 * HTTP Check tool — performs an HTTP request and shows response details.
 *
 * Shows status code, response headers, timing, and optionally the body.
 * Uses RFC 9110 (HTTP Semantics) and RFC 9112 (HTTP/1.1).
 */
class HttpCheckCommand : BaseCommand(
    name = "http",
    helpText = "Check HTTP connectivity and response details for a URL",
) {
    private val url by argument(help = "URL to check (e.g., http://example.com)")
    private val showBody by option("--body", "-b", help = "Include response body").flag()
    private val showHeaders by option("--headers", "-H", help = "Show all response headers").flag()
    private val noFollow by option("--no-follow", help = "Don't follow redirects").flag()

    override fun run() {
        val formatter = createFormatter()
        val fullUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "http://$url"
        } else {
            url
        }

        val client = HttpClient(
            timeout = Duration.ofSeconds(timeoutSeconds.toLong()),
            followRedirects = !noFollow,
        )
        val result = client.get(fullUrl, includeBody = showBody)

        val details = linkedMapOf<String, Any?>(
            "URL" to result.url,
            "Status Code" to if (result.statusCode > 0) "${result.statusCode}" else "Error",
            "Response Time" to "${result.responseTimeMs}ms",
        )

        if (result.error != null) {
            details["Error"] = result.error
        }

        // Key headers
        val serverHeader = result.headers["server"]?.firstOrNull()
            ?: result.headers["Server"]?.firstOrNull()
        if (serverHeader != null) details["Server"] = serverHeader

        val contentType = result.headers["content-type"]?.firstOrNull()
            ?: result.headers["Content-Type"]?.firstOrNull()
        if (contentType != null) details["Content-Type"] = contentType

        val contentLength = result.headers["content-length"]?.firstOrNull()
            ?: result.headers["Content-Length"]?.firstOrNull()
        if (contentLength != null) details["Content-Length"] = contentLength

        echo(formatter.formatDetail(details))

        if (showHeaders && result.headers.isNotEmpty()) {
            echo()
            echo("=== Response Headers ===")
            val headerRows = result.headers.flatMap { (name, values) ->
                values.map { value ->
                    mapOf("Header" to name, "Value" to value)
                }
            }
            echo(formatter.format(headerRows))
        }

        if (showBody && result.body != null) {
            echo()
            echo("=== Response Body ===")
            echo(result.body)
        }
    }
}

fun main(args: Array<String>) = HttpCheckCommand().main(args)
