package no.norrs.nortools.tools.blocklist.cert

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import org.xbill.DNS.CERTRecord
import org.xbill.DNS.Section
import org.xbill.DNS.Type

/**
 * CERT Record Lookup tool — queries DNS CERT records for a domain.
 *
 * CERT records store certificates and related revocation lists in DNS.
 * Uses RFC 4398 (Storing Certificates in the DNS).
 */
class CertLookupCommand : BaseCommand(
    name = "cert",
    helpText = "Look up DNS CERT (certificate) records for a domain",
) {
    private val domain by argument(help = "Domain name to look up CERT records for")

    override fun run() {
        val resolver = createResolver()
        val formatter = createFormatter()

        val response = resolver.rawQuery(domain, Type.CERT)
        val certRecords = response.getSection(Section.ANSWER)
            .filterIsInstance<CERTRecord>()

        if (certRecords.isEmpty()) {
            echo("No CERT records found for $domain")
            return
        }

        val rows = certRecords.map { record ->
            val certType = when (record.certType) {
                1 -> "PKIX (X.509)"
                2 -> "SPKI"
                3 -> "PGP"
                4 -> "IPKIX (URL to X.509)"
                5 -> "ISPKI (URL to SPKI)"
                6 -> "IPGP (URL to PGP)"
                7 -> "ACPKIX"
                8 -> "IACPKIX"
                253 -> "URI"
                254 -> "OID"
                else -> "Unknown (${record.certType})"
            }

            val algorithm = when (record.algorithm) {
                1 -> "RSA/MD5"
                3 -> "DSA/SHA1"
                5 -> "RSA/SHA1"
                8 -> "RSA/SHA256"
                10 -> "RSA/SHA512"
                13 -> "ECDSA/P-256/SHA-256"
                14 -> "ECDSA/P-384/SHA-384"
                15 -> "Ed25519"
                16 -> "Ed448"
                else -> "Algorithm ${record.algorithm}"
            }

            mapOf(
                "Type" to certType,
                "Key Tag" to "${record.keyTag}",
                "Algorithm" to algorithm,
                "Certificate" to record.cert.take(40).joinToString("") { "%02x".format(it) } + "...",
                "TTL" to "${record.ttl}s",
            )
        }

        echo(formatter.format(rows))
    }
}

fun main(args: Array<String>) = CertLookupCommand().main(args)
