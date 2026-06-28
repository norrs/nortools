package no.norrs.nortools.tools.zeroconf.sambabrowse

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import no.norrs.nortools.lib.cli.BaseCommand
import no.norrs.nortools.lib.zeroconf.SmbBrowseCredential
import no.norrs.nortools.lib.zeroconf.SmbBrowseResult
import no.norrs.nortools.lib.zeroconf.SmbShareBrowser
import java.time.Duration

class SambaBrowseCommand : BaseCommand(
    name = "samba-browse",
    helpText = "Browse SMB/Samba server metadata and shared resources",
) {
    private val host by argument(help = "SMB/Samba host or IPv4/IPv6 address")
    private val username by option("--username", "-u", help = "Username for authenticated SMB browsing")
    private val password by option("--password", "-p", help = "Password for authenticated SMB browsing")
        .default("")
    private val domain by option("--domain", "-d", help = "SMB domain or workgroup for authenticated browsing")
        .default("")

    override fun run() {
        val formatter = createFormatter()
        val credentials = explicitCredentials()
        val result = SmbShareBrowser(timeout = Duration.ofSeconds(timeoutSeconds.toLong()))
            .browse(host, credentials = credentials)

        if (result.shares.isNotEmpty()) {
            echo(formatter.format(flattenShares(result)))
        } else {
            echo(formatter.formatDetail(detail(result)))
        }
    }

    private fun explicitCredentials(): List<SmbBrowseCredential> =
        username?.takeIf { it.isNotBlank() }
            ?.let { user ->
                listOf(SmbShareBrowser.usernamePasswordCredential(user, password, domain))
            }
            ?: SmbShareBrowser.defaultCredentials()

    private fun flattenShares(result: SmbBrowseResult): List<Map<String, Any?>> =
        result.shares.map { share ->
            linkedMapOf(
                "Host" to result.host,
                "Dialect" to (result.dialect ?: ""),
                "Auth" to (result.authenticationStatus ?: result.authenticationMode ?: ""),
                "Share" to share.name,
                "Type" to share.type,
                "Comment" to share.comment,
            )
        }

    private fun detail(result: SmbBrowseResult): Map<String, Any?> {
        val detail = linkedMapOf<String, Any?>(
            "Protocol" to "SMB/Samba",
            "Host" to result.host,
            "Status" to when {
                result.error != null -> "Error"
                result.authenticationStatus == "denied" -> "Authentication denied"
                result.attempted -> "No shares"
                else -> "Not attempted"
            },
            "Dialect" to (result.dialect ?: ""),
            "Signing Required" to result.signingRequired,
            "Signing Enabled" to result.signingEnabled,
            "Encryption Supported" to result.encryptionSupported,
            "Server GUID" to (result.serverGuid ?: ""),
            "Authentication Mode" to (result.authenticationMode ?: ""),
            "Authentication Status" to (result.authenticationStatus ?: ""),
            "Shares" to result.shares.size,
        )
        if (result.note != null) detail["Note"] = result.note
        if (result.error != null) detail["Error"] = result.error
        return detail
    }
}

fun main(args: Array<String>) = SambaBrowseCommand().main(args)
