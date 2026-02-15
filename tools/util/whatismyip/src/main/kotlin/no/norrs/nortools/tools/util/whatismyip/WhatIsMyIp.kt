package no.norrs.nortools.tools.util.whatismyip

import com.github.ajalt.clikt.core.main
import no.norrs.nortools.lib.cli.BaseCommand
import no.norrs.nortools.lib.network.HttpClient
import org.xbill.DNS.Type
import java.time.Duration

/**
 * What Is My IP tool — detects the public IP address of the machine.
 *
 * Uses multiple external services for reliability:
 * - HTTP-based: ifconfig.me, icanhazip.com, api.ipify.org
 * - DNS-based: myip.opendns.com via resolver1.opendns.com
 */
class WhatIsMyIpCommand : BaseCommand(
    name = "whatismyip",
    helpText = "Detect your public IP address using external services",
) {
    override fun run() {
        val formatter = createFormatter()
        val details = linkedMapOf<String, Any?>()

        // DNS-based detection via OpenDNS
        try {
            val resolver = createResolver()
            val result = resolver.lookup("myip.opendns.com", Type.A)
            if (result.isSuccessful && result.records.isNotEmpty()) {
                details["IP (OpenDNS)"] = result.records.first().data
            }
        } catch (_: Exception) {
            details["IP (OpenDNS)"] = "Failed"
        }

        // HTTP-based detection
        val httpClient = HttpClient(timeout = Duration.ofSeconds(timeoutSeconds.toLong()))
        val services = listOf(
            "https://ifconfig.me/ip" to "ifconfig.me",
            "https://icanhazip.com" to "icanhazip.com",
            "https://api.ipify.org" to "ipify.org",
            "https://api64.ipify.org" to "ipify.org (v6)",
        )

        for ((url, name) in services) {
            try {
                val result = httpClient.get(url, includeBody = true)
                val body = result.body
                if (result.statusCode == 200 && body != null) {
                    details["IP ($name)"] = body.trim()
                } else {
                    details["IP ($name)"] = "HTTP ${result.statusCode}"
                }
            } catch (e: Exception) {
                details["IP ($name)"] = "Failed: ${e.message}"
            }
        }

        echo(formatter.formatDetail(details))
    }
}

fun main(args: Array<String>) = WhatIsMyIpCommand().main(args)
