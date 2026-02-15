package no.norrs.nortools.tools.network.https

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import no.norrs.nortools.lib.network.HttpClient
import java.net.URI
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.net.ssl.HttpsURLConnection
import javax.naming.ldap.LdapName


/**
 * HTTPS Check tool — performs an HTTPS request and shows TLS/certificate details.
 *
 * Shows TLS version, cipher suite, certificate chain, validity, and issuer.
 * Uses RFC 8446 (TLS 1.3), RFC 5280 (X.509 Certificates), RFC 6125 (TLS hostname verification).
 */
class HttpsCheckCommand : BaseCommand(
    name = "https",
    helpText = "Check HTTPS connectivity and TLS/certificate details for a host",
) {
    private val host by argument(help = "Hostname to check (e.g., example.com)")

    override fun run() {
        val formatter = createFormatter()
        val url = if (host.startsWith("https://")) host else "https://$host"

        // First, do a basic HTTPS request for timing/status
        val client = HttpClient(
            timeout = Duration.ofSeconds(timeoutSeconds.toLong()),
            followRedirects = true,
        )
        val httpResult = client.get(url)

        val details = linkedMapOf<String, Any?>(
            "Host" to host,
            "URL" to httpResult.url,
            "Status Code" to if (httpResult.statusCode > 0) "${httpResult.statusCode}" else "Error",
            "Response Time" to "${httpResult.responseTimeMs}ms",
        )

        if (httpResult.error != null) {
            details["Error"] = httpResult.error
        }

        val sslInfo = httpResult.sslSession
        if (sslInfo != null) {
            details["TLS Protocol"] = sslInfo.protocol
            details["Cipher Suite"] = sslInfo.cipherSuite
        }

        // Get detailed certificate info via direct connection
        try {
            val certInfo = getCertificateInfo(host)
            details.putAll(certInfo)
        } catch (e: Exception) {
            details["Certificate Error"] = e.message
        }

        echo(formatter.formatDetail(details))
    }

    private fun getCertificateInfo(hostname: String): Map<String, String> {
        val info = linkedMapOf<String, String>()
        val dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.of("UTC"))

        val uri = URI.create("https://$hostname")
        val conn = uri.toURL().openConnection() as HttpsURLConnection
        conn.connectTimeout = timeoutSeconds * 1000
        conn.readTimeout = timeoutSeconds * 1000

        try {
            conn.connect()
            val certs = conn.serverCertificates.filterIsInstance<X509Certificate>()
            if (certs.isNotEmpty()) {
                val leaf = certs[0]
                info["Subject"] = leaf.subjectX500Principal.name
                info["Issuer"] = leaf.issuerX500Principal.name
                info["Common Name"] = dnAttribute(leaf.subjectX500Principal.name, "CN") ?: ""
                info["Issuer CN"] = dnAttribute(leaf.issuerX500Principal.name, "CN") ?: ""

                val notBefore = leaf.notBefore.toInstant()
                val notAfter = leaf.notAfter.toInstant()
                val now = Instant.now()
                val daysRemaining = ChronoUnit.DAYS.between(now, notAfter)

                info["Valid From"] = dtf.format(notBefore)
                info["Valid Until"] = dtf.format(notAfter)
                info["Days Remaining"] = "$daysRemaining"
                info["Expired"] = if (now.isAfter(notAfter)) "YES" else "No"

                val sans = subjectAltNames(leaf)
                if (sans.isNotEmpty()) {
                    info["SANs"] = sans.joinToString(", ")
                }

                info["Serial Number"] = leaf.serialNumber.toString(16)
                info["Signature Algorithm"] = leaf.sigAlgName
                info["Certificate Chain"] = formatCertChain(certs, dtf)
            }
        } finally {
            conn.disconnect()
        }

        return info
    }
}

fun main(args: Array<String>) = HttpsCheckCommand().main(args)

private fun dnAttribute(dn: String, attribute: String): String? {
    return try {
        LdapName(dn).rdns.firstOrNull { it.type.equals(attribute, ignoreCase = true) }?.value?.toString()
    } catch (_: Exception) {
        null
    }
}

private fun subjectAltNames(cert: X509Certificate): List<String> {
    val sans = cert.subjectAlternativeNames ?: return emptyList()
    return sans.mapNotNull { entry ->
        if (entry.size < 2) return@mapNotNull null
        val type = entry[0] as? Int ?: return@mapNotNull null
        val value = entry[1]?.toString() ?: return@mapNotNull null
        val label = when (type) {
            2 -> "DNS"
            7 -> "IP"
            1 -> "RFC822"
            6 -> "URI"
            8 -> "RID"
            4 -> "DirName"
            else -> "Type$type"
        }
        "$label:$value"
    }
}

private fun formatCertChain(certs: List<X509Certificate>, dtf: DateTimeFormatter): String {
    val now = Instant.now()
    return certs.mapIndexed { idx, cert ->
        val subject = cert.subjectX500Principal.name
        val issuer = cert.issuerX500Principal.name
        val cn = dnAttribute(subject, "CN")
        val issuerCn = dnAttribute(issuer, "CN")
        val notAfter = cert.notAfter.toInstant()
        val daysRemaining = ChronoUnit.DAYS.between(now, notAfter)
        val keySize = when (val publicKey = cert.publicKey) {
            is RSAPublicKey -> publicKey.modulus.bitLength()
            is ECPublicKey -> publicKey.params.curve.field.fieldSize
            else -> null
        }
        val sanText = subjectAltNames(cert).joinToString(", ")
        val fingerprint = sha256Fingerprint(cert)
        buildString {
            appendLine("[$idx] ${cn ?: "(no CN)"}")
            appendLine("    Subject: $subject")
            appendLine("    Issuer: ${issuerCn ?: issuer}")
            appendLine("    SANs: ${if (sanText.isNotBlank()) sanText else "-"}")
            appendLine("    Valid: ${dtf.format(cert.notBefore.toInstant())} → ${dtf.format(cert.notAfter.toInstant())}")
            appendLine("    Days Remaining: $daysRemaining")
            appendLine("    Key: ${cert.publicKey.algorithm}${keySize?.let { " ($it bit)" } ?: ""}")
            appendLine("    Signature: ${cert.sigAlgName}")
            appendLine("    CA: ${if (cert.basicConstraints >= 0) "Yes" else "No"}")
            appendLine("    SHA-256: $fingerprint")
        }
    }.joinToString("\n")
}

private fun sha256Fingerprint(cert: X509Certificate): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
    return digest.joinToString(":") { "%02X".format(it) }
}
