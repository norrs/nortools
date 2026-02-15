package no.norrs.nortools.web

import com.google.gson.Gson
import com.fasterxml.jackson.databind.ObjectMapper
import io.javalin.http.Context
import no.norrs.nortools.lib.dns.DnsResolver
import no.norrs.nortools.lib.network.HttpClient
import no.norrs.nortools.lib.network.TcpClient
import org.xbill.DNS.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import javax.naming.ldap.LdapName
import kotlin.math.log2

private class JsonFacade {
    private val gson = Gson()
    private val tree = ObjectMapper()
    fun writeValueAsString(value: Any?): String = gson.toJson(value)
    fun readTree(content: String) = tree.readTree(content)
}

private val json by lazy { JsonFacade() }

// ─── DNS Tools ───────────────────────────────────────────────────────────────

fun dnsLookup(ctx: Context) {
    val type = ctx.pathParam("type").uppercase()
    val domain = ctx.pathParam("domain")
    val server = ctx.queryParam("server")
    val resolver = DnsResolver(server = if (server.isNullOrBlank()) null else server)
    val typeInt = Type.value(type)
    if (typeInt == -1) {
        ctx.result(json.writeValueAsString(mapOf("error" to "Unknown DNS type: $type")))
        ctx.contentType("application/json")
        return
    }
    val result = resolver.lookup(domain, typeInt)
    ctx.result(json.writeValueAsString(result))
    ctx.contentType("application/json")
}

fun dnssecLookup(ctx: Context) {
    val type = ctx.pathParam("type").uppercase()
    val domain = ctx.pathParam("domain")
    val resolver = DnsResolver()
    val typeInt = Type.value(type)
    if (typeInt == -1) {
        ctx.result(json.writeValueAsString(mapOf("error" to "Unknown DNS type: $type")))
        ctx.contentType("application/json")
        return
    }
    val result = resolver.dnssecLookup(domain, typeInt)
    ctx.result(json.writeValueAsString(result))
    ctx.contentType("application/json")
}

// ─── DNSSEC Authentication Chain ────────────────────────────────────────────

private fun algorithmName(alg: Int): String = when (alg) {
    1 -> "RSA/MD5"; 3 -> "DSA/SHA1"; 5 -> "RSA/SHA-1"; 6 -> "DSA-NSEC3-SHA1"
    7 -> "RSASHA1-NSEC3-SHA1"; 8 -> "RSA/SHA-256"; 10 -> "RSA/SHA-512"
    13 -> "ECDSA P-256/SHA-256"; 14 -> "ECDSA P-384/SHA-384"; 15 -> "Ed25519"; 16 -> "Ed448"
    else -> "Algorithm $alg"
}

private fun digestTypeName(dt: Int): String = when (dt) {
    1 -> "SHA-1"; 2 -> "SHA-256"; 4 -> "SHA-384"
    else -> "Digest $dt"
}

private fun keyRole(flags: Int): String = when (flags) {
    257 -> "KSK"; 256 -> "ZSK"; else -> "flags=$flags"
}

private fun keyLengthBits(alg: Int, keyBytes: ByteArray): Int = when (alg) {
    13 -> 256; 14 -> 384; 15 -> 256; 16 -> 456
    else -> keyBytes.size * 8  // RSA: key bytes ≈ modulus
}

/**
 * Walk the DNSSEC authentication chain from root (.) → TLD → domain.
 * Returns a structured JSON response with zones, DNSKEY/DS/RRSIG records,
 * and chain links showing how DS in parent matches DNSKEY in child.
 */
fun dnssecChain(ctx: Context) {
    val domain = ctx.pathParam("domain").trimEnd('.')
    val resolver = DnsResolver()

    // Split domain into zone levels: e.g. "norrs.no" → [".", "no", "norrs.no"]
    val labels = domain.split(".")
    val zones = mutableListOf(".")
    for (i in labels.indices.reversed()) {
        zones.add(labels.subList(i, labels.size).joinToString("."))
    }

    val zoneResults = mutableListOf<Map<String, Any?>>()

    for (zone in zones) {
        val zoneName = if (zone == ".") "." else "$zone."
        val zoneInfo = mutableMapOf<String, Any?>("zone" to zone)

        // Query DNSKEY records
        val dnskeys = mutableListOf<Map<String, Any?>>()
        val rrsigs = mutableListOf<Map<String, Any?>>()
        try {
            val dnskeyMsg = resolver.dnssecQuery(if (zone == ".") "." else zone, Type.DNSKEY)
            val ad = dnskeyMsg.header.getFlag(Flags.AD.toInt())
            zoneInfo["adFlag"] = ad

            for (rec in dnskeyMsg.getSection(Section.ANSWER)) {
                when (rec) {
                    is DNSKEYRecord -> dnskeys.add(mapOf(
                        "keyTag" to rec.footprint,
                        "algorithm" to rec.algorithm,
                        "algorithmName" to algorithmName(rec.algorithm),
                        "flags" to rec.flags,
                        "role" to keyRole(rec.flags),
                        "protocol" to rec.protocol,
                        "keyLength" to keyLengthBits(rec.algorithm, rec.key),
                        "ttl" to rec.ttl,
                        "keyBase64" to java.util.Base64.getEncoder().encodeToString(rec.key).take(40) + "...",
                    ))
                    is RRSIGRecord -> rrsigs.add(mapOf(
                        "typeCovered" to Type.string(rec.typeCovered),
                        "algorithm" to rec.algorithm,
                        "algorithmName" to algorithmName(rec.algorithm),
                        "labels" to rec.labels,
                        "origTTL" to rec.origTTL,
                        "expiration" to rec.expire.toString(),
                        "inception" to rec.timeSigned.toString(),
                        "keyTag" to rec.footprint,
                        "signerName" to rec.signer.toString(),
                    ))
                }
            }
        } catch (_: Exception) {
            zoneInfo["dnskeyError"] = "Failed to query DNSKEY for $zone"
        }
        zoneInfo["dnskeys"] = dnskeys
        zoneInfo["rrsigs"] = rrsigs

        // Query DS records from parent (not for root)
        val dsRecords = mutableListOf<Map<String, Any?>>()
        if (zone != ".") {
            try {
                val dsMsg = resolver.dnssecQuery(zone, Type.DS)
                for (rec in dsMsg.getSection(Section.ANSWER)) {
                    when (rec) {
                        is DSRecord -> dsRecords.add(mapOf(
                            "keyTag" to rec.footprint,
                            "algorithm" to rec.algorithm,
                            "algorithmName" to algorithmName(rec.algorithm),
                            "digestType" to rec.digestID,
                            "digestTypeName" to digestTypeName(rec.digestID),
                            "digest" to rec.digest.joinToString("") { "%02X".format(it) },
                            "ttl" to rec.ttl,
                        ))
                        is RRSIGRecord -> if (rec.typeCovered == Type.DS) {
                            rrsigs.add(mapOf(
                                "typeCovered" to "DS",
                                "algorithm" to rec.algorithm,
                                "algorithmName" to algorithmName(rec.algorithm),
                                "labels" to rec.labels,
                                "origTTL" to rec.origTTL,
                                "expiration" to rec.expire.toString(),
                                "inception" to rec.timeSigned.toString(),
                                "keyTag" to rec.footprint,
                                "signerName" to rec.signer.toString(),
                            ))
                        }
                    }
                }
            } catch (_: Exception) {
                zoneInfo["dsError"] = "Failed to query DS for $zone"
            }
        }
        zoneInfo["dsRecords"] = dsRecords

        // Query NS records
        val nsRecords = mutableListOf<String>()
        try {
            val nsResult = resolver.lookup(if (zone == ".") "." else zone, Type.NS)
            nsRecords.addAll(nsResult.records.map { it.data })
        } catch (_: Exception) { }
        zoneInfo["nsRecords"] = nsRecords

        // Determine delegation status
        val hasDnskey = dnskeys.isNotEmpty()
        val hasDs = dsRecords.isNotEmpty()
        val hasKsk = dnskeys.any { (it["flags"] as? Int) == 257 }
        val dsKeyTags = dsRecords.map { it["keyTag"] as? Int }.filterNotNull().toSet()
        val dnskeyKeyTags = dnskeys.map { it["keyTag"] as? Int }.filterNotNull().toSet()
        val dsMatchesDnskey = dsKeyTags.intersect(dnskeyKeyTags).isNotEmpty()

        val delegationStatus = when {
            zone == "." -> if (hasDnskey) "Secure (Trust Anchor)" else "Insecure"
            hasDnskey && hasDs && dsMatchesDnskey -> "Secure"
            hasDnskey && hasDs && !dsMatchesDnskey -> "Bogus (DS/DNSKEY mismatch)"
            hasDnskey && !hasDs -> "Insecure (no DS in parent)"
            !hasDnskey && hasDs -> "Bogus (DS exists but no DNSKEY)"
            else -> "Insecure"
        }
        zoneInfo["delegationStatus"] = delegationStatus
        zoneInfo["hasDnskey"] = hasDnskey
        zoneInfo["hasDs"] = hasDs
        zoneInfo["dsMatchesDnskey"] = dsMatchesDnskey

        // Build chain links
        val chainLinks = mutableListOf<Map<String, Any?>>()
        for (ds in dsRecords) {
            val dsTag = ds["keyTag"] as? Int ?: continue
            val matchingKey = dnskeys.find { (it["keyTag"] as? Int) == dsTag }
            chainLinks.add(mapOf(
                "dsKeyTag" to dsTag,
                "dsDigestType" to ds["digestTypeName"],
                "matchesDnskey" to (matchingKey != null),
                "dnskeyRole" to matchingKey?.get("role"),
                "dnskeyAlgorithm" to matchingKey?.get("algorithmName"),
            ))
        }
        zoneInfo["chainLinks"] = chainLinks

        // Query NSEC3PARAM if zone has DNSKEY
        if (hasDnskey) {
            try {
                val nsec3Result = resolver.lookup(if (zone == ".") "." else zone, Type.NSEC3PARAM)
                if (nsec3Result.isSuccessful && nsec3Result.records.isNotEmpty()) {
                    zoneInfo["nsec3"] = true
                    zoneInfo["nsec3Params"] = nsec3Result.records.first().data
                } else {
                    zoneInfo["nsec3"] = false
                }
            } catch (_: Exception) {
                zoneInfo["nsec3"] = null
            }
        }

        zoneResults.add(zoneInfo)
    }

    val result = mapOf(
        "domain" to domain,
        "zones" to zoneResults,
        "chainSecure" to zoneResults.all {
            val status = it["delegationStatus"] as? String ?: ""
            status.startsWith("Secure")
        },
    )
    ctx.result(json.writeValueAsString(result))
    ctx.contentType("application/json")
}

fun reverseLookup(ctx: Context) {
    val ip = ctx.pathParam("ip")
    val resolver = DnsResolver()
    val result = resolver.reverseLookup(ip)
    ctx.result(json.writeValueAsString(result))
    ctx.contentType("application/json")
}

// ─── Email Auth Tools ────────────────────────────────────────────────────────

fun spfLookup(ctx: Context) {
    val domain = ctx.pathParam("domain")
    val resolver = DnsResolver()
    val result = resolver.lookup(domain, Type.TXT)
    val spfRecords = if (result.isSuccessful) {
        result.records.filter { it.data.startsWith("v=spf1") }
    } else emptyList()

    val spfRecord = spfRecords.firstOrNull()?.data
    val mechanisms = if (spfRecord != null) parseSpfMechanisms(spfRecord, domain) else emptyList()

    val response = mapOf(
        "domain" to domain,
        "found" to (spfRecord != null),
        "record" to spfRecord,
        "ttl" to spfRecords.firstOrNull()?.ttl,
        "mechanisms" to mechanisms,
        "multipleRecords" to (spfRecords.size > 1),
    )
    ctx.result(json.writeValueAsString(response))
    ctx.contentType("application/json")
}

private fun parseSpfMechanisms(spf: String, domain: String): List<Map<String, String>> {
    val parts = spf.split(" ").drop(1)
    return parts.map { part ->
        val qualifier = when {
            part.startsWith("+") -> "Pass"
            part.startsWith("-") -> "Fail"
            part.startsWith("~") -> "SoftFail"
            part.startsWith("?") -> "Neutral"
            else -> "Pass"
        }
        val mechanism = part.trimStart('+', '-', '~', '?')
        val (type, value) = when {
            mechanism.startsWith("include:") -> "include" to mechanism.removePrefix("include:")
            mechanism.startsWith("a:") -> "a" to mechanism.removePrefix("a:")
            mechanism.startsWith("mx:") -> "mx" to mechanism.removePrefix("mx:")
            mechanism.startsWith("ip4:") -> "ip4" to mechanism.removePrefix("ip4:")
            mechanism.startsWith("ip6:") -> "ip6" to mechanism.removePrefix("ip6:")
            mechanism.startsWith("redirect=") -> "redirect" to mechanism.removePrefix("redirect=")
            mechanism.startsWith("exists:") -> "exists" to mechanism.removePrefix("exists:")
            mechanism == "a" -> "a" to domain
            mechanism == "mx" -> "mx" to domain
            mechanism == "all" -> "all" to ""
            mechanism == "ptr" -> "ptr" to "(deprecated)"
            else -> mechanism to ""
        }
        mapOf("qualifier" to qualifier, "mechanism" to type, "value" to value)
    }
}

fun dkimLookup(ctx: Context) {
    val selector = ctx.pathParam("selector")
    val domain = ctx.pathParam("domain")
    val resolver = DnsResolver()
    val dkimDomain = "$selector._domainkey.$domain"
    val result = resolver.lookup(dkimDomain, Type.TXT)
    val dkimRecords = if (result.isSuccessful) {
        result.records.filter { it.data.contains("v=DKIM1") || it.data.contains("k=") }
    } else emptyList()

    val dkimRecord = dkimRecords.firstOrNull()?.data
    val tags = if (dkimRecord != null) parseTags(dkimRecord) else emptyMap()

    val response = mapOf(
        "domain" to domain, "selector" to selector, "dkimDomain" to dkimDomain,
        "found" to (dkimRecord != null), "record" to dkimRecord,
        "version" to (tags["v"] ?: "DKIM1"), "keyType" to (tags["k"] ?: "rsa"),
        "publicKey" to (tags["p"]?.take(60)?.plus("...") ?: "N/A"),
        "hashAlgorithms" to (tags["h"] ?: "all"), "serviceType" to (tags["s"] ?: "*"),
        "flags" to (tags["t"] ?: "none"), "ttl" to dkimRecords.firstOrNull()?.ttl,
    )
    ctx.result(json.writeValueAsString(response))
    ctx.contentType("application/json")
}

/** Well-known DKIM selectors used by major email providers and platforms. */
private val COMMON_DKIM_SELECTORS = listOf(
    // Google Workspace / Gmail
    "google", "google2048", "20161025", "20230601", "20210112",
    // Microsoft 365 / Outlook
    "selector1", "selector2", "selector1-azurecomm-prod-net",
    // Amazon SES
    "ses", "amazonses", "ug7nbtf4gccmlpwj322ax3p6ow6yfsug",
    "3gvwdm5gzn4xnm6dyqmyqisoha5r57tv", "k1", "k2", "k3",
    // Mailchimp / Mandrill
    "k1", "mandrill", "mcdkim", "mcdkim2",
    // SendGrid
    "s1", "s2", "smtpapi", "sgrid",
    // Salesforce / Pardot
    "sf", "sf1", "sf2", "pardot",
    // Mailgun
    "mg", "mailo", "smtp", "pic",
    // Postmark
    "pm", "20240913",
    // Brevo (Sendinblue)
    "mail", "mail2",
    // Zoho
    "zoho", "zoho1",
    // ProtonMail
    "protonmail", "protonmail2", "protonmail3",
    // Fastmail
    "fm1", "fm2", "fm3", "mesmtp",
    // Yahoo / AOL
    "yahoo", "s1024", "s2048",
    // SparkPost
    "sparkpost", "scph0316",
    // Constant Contact
    "ctct1", "ctct2",
    // HubSpot
    "hs1", "hs2", "hubspot",
    // Klaviyo
    "kl", "kl2",
    // Intercom
    "ic",
    // Zendesk
    "zendesk1", "zendesk2",
    // Freshdesk
    "freshdesk",
    // Mimecast
    "mimecast20190104",
    // Generic / common defaults
    "default", "dkim", "dkim2", "email", "mail", "mailer",
    "selector", "sig1", "key1", "key2", "smtp", "x",
    // Numeric patterns
    "1", "2", "3", "201505", "20150623", "20170101",
    // Cloudflare
    "cf", "cf2",
    // Twilio
    "twilio",
    // Mailjet
    "mailjet",
    // Campaign Monitor
    "cm",
    // ActiveCampaign
    "dk",
    // Elastic Email
    "api",
    // Postfix / cPanel / Plesk
    "mx", "main", "a1", "a2",
).distinct()

fun dkimDiscover(ctx: Context) {
    val domain = ctx.pathParam("domain")
    val resolver = DnsResolver(timeout = Duration.ofSeconds(3))
    val found = mutableListOf<Map<String, Any?>>()

    for (selector in COMMON_DKIM_SELECTORS) {
        val dkimDomain = "$selector._domainkey.$domain"
        try {
            val result = resolver.lookup(dkimDomain, Type.TXT)
            if (!result.isSuccessful) continue
            val dkimRecords = result.records.filter {
                it.data.contains("v=DKIM1") || it.data.contains("k=") || it.data.contains("p=")
            }
            if (dkimRecords.isEmpty()) continue
            val record = dkimRecords.first().data
            val tags = parseTags(record)
            found.add(mapOf(
                "selector" to selector, "dkimDomain" to dkimDomain,
                "record" to record, "keyType" to (tags["k"] ?: "rsa"),
                "publicKey" to (tags["p"]?.take(60)?.plus("...") ?: "N/A"),
                "flags" to (tags["t"] ?: "none"), "ttl" to dkimRecords.first().ttl,
            ))
        } catch (_: Exception) {
            // Timeout or DNS error — skip this selector
        }
    }

    val response = mapOf(
        "domain" to domain,
        "selectorsProbed" to COMMON_DKIM_SELECTORS.size,
        "selectorsFound" to found.size,
        "selectors" to found,
    )
    ctx.result(json.writeValueAsString(response))
    ctx.contentType("application/json")
}

fun dmarcLookup(ctx: Context) {
    val domain = ctx.pathParam("domain")
    val resolver = DnsResolver()
    val dmarcDomain = "_dmarc.$domain"
    val result = resolver.lookup(dmarcDomain, Type.TXT)
    val dmarcRecords = if (result.isSuccessful) {
        result.records.filter { it.data.startsWith("v=DMARC1") }
    } else emptyList()

    val dmarcRecord = dmarcRecords.firstOrNull()?.data
    val tags = if (dmarcRecord != null) parseTags(dmarcRecord) else emptyMap()

    val policyDesc = when (tags["p"]) {
        "none" -> "none (monitor only)"
        "quarantine" -> "quarantine (mark as spam)"
        "reject" -> "reject (block delivery)"
        else -> tags["p"] ?: "not specified"
    }

    val response = mapOf(
        "domain" to domain, "found" to (dmarcRecord != null), "record" to dmarcRecord,
        "policy" to policyDesc, "subdomainPolicy" to (tags["sp"] ?: "same as domain"),
        "pct" to (tags["pct"] ?: "100"),
        "dkimAlignment" to (tags["adkim"]?.let { if (it == "s") "strict" else "relaxed" } ?: "relaxed"),
        "spfAlignment" to (tags["aspf"]?.let { if (it == "s") "strict" else "relaxed" } ?: "relaxed"),
        "rua" to (tags["rua"] ?: "not configured"),
        "ruf" to (tags["ruf"] ?: "not configured"),
        "ttl" to dmarcRecords.firstOrNull()?.ttl,
    )
    ctx.result(json.writeValueAsString(response))
    ctx.contentType("application/json")
}

private fun parseTags(record: String): Map<String, String> {
    return record.split(";")
        .map { it.trim() }
        .filter { it.contains("=") }
        .associate { tag ->
            val (key, value) = tag.split("=", limit = 2)
            key.trim() to value.trim()
        }
}

// ─── Network Tools ───────────────────────────────────────────────────────────

fun tcpCheck(ctx: Context) {
    val host = ctx.pathParam("host")
    val port = ctx.pathParam("port").toIntOrNull() ?: 80
    val banner = ctx.queryParam("banner") == "true"
    val client = TcpClient(timeout = Duration.ofSeconds(10))
    val result = client.connect(host, port, grabBanner = banner)
    ctx.result(json.writeValueAsString(result))
    ctx.contentType("application/json")
}

fun httpCheck(ctx: Context) {
    val url = ctx.pathParam("url")
    val fullUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) "http://$url" else url
    val client = HttpClient(timeout = Duration.ofSeconds(10))
    val result = client.get(fullUrl, includeBody = false)
    val response = mapOf(
        "url" to result.url, "statusCode" to result.statusCode,
        "responseTimeMs" to result.responseTimeMs, "error" to result.error,
        "headers" to result.headers.entries.take(20).associate { it.key to it.value.joinToString(", ") },
    )
    ctx.result(json.writeValueAsString(response))
    ctx.contentType("application/json")
}

fun httpsCheck(ctx: Context) {
    val host = ctx.pathParam("host")
    val client = HttpClient(timeout = Duration.ofSeconds(10))
    val result = client.get("https://$host", includeBody = false)
    var certificateError: String? = null
    val certificateChain = try {
        fetchCertificateChain(host, timeoutSeconds = 10)
    } catch (e: Exception) {
        certificateError = e.message ?: "Failed to fetch certificate chain"
        null
    }
    val response = mapOf(
        "url" to result.url, "statusCode" to result.statusCode,
        "responseTimeMs" to result.responseTimeMs, "error" to result.error,
        "ssl" to result.sslSession?.let {
            mapOf("protocol" to it.protocol, "cipherSuite" to it.cipherSuite)
        },
        "certificateChain" to certificateChain,
        "certificateError" to certificateError,
        "headers" to result.headers.entries.take(20).associate { it.key to it.value.joinToString(", ") },
    )
    ctx.result(json.writeValueAsString(response))
    ctx.contentType("application/json")
}

private fun fetchCertificateChain(hostname: String, timeoutSeconds: Int): List<Map<String, Any?>> {
    val dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.of("UTC"))
    val uri = URI.create("https://$hostname")
    val conn = uri.toURL().openConnection() as HttpsURLConnection
    conn.connectTimeout = timeoutSeconds * 1000
    conn.readTimeout = timeoutSeconds * 1000
    conn.instanceFollowRedirects = true

    try {
        conn.connect()
        val certs = conn.serverCertificates.filterIsInstance<X509Certificate>()
        val now = Instant.now()
        return certs.mapIndexed { index, cert ->
            val notBefore = cert.notBefore.toInstant()
            val notAfter = cert.notAfter.toInstant()
            val daysRemaining = ChronoUnit.DAYS.between(now, notAfter)
            val subject = cert.subjectX500Principal.name
            val issuer = cert.issuerX500Principal.name
            val publicKey = cert.publicKey
            val keySize = when (publicKey) {
                is RSAPublicKey -> publicKey.modulus.bitLength()
                is ECPublicKey -> publicKey.params.curve.field.fieldSize
                else -> null
            }
            mapOf(
                "index" to index,
                "subject" to subject,
                "issuer" to issuer,
                "commonName" to dnAttribute(subject, "CN"),
                "issuerCommonName" to dnAttribute(issuer, "CN"),
                "subjectAltNames" to subjectAltNames(cert),
                "validFrom" to dtf.format(notBefore),
                "validUntil" to dtf.format(notAfter),
                "validFromEpochMs" to notBefore.toEpochMilli(),
                "validUntilEpochMs" to notAfter.toEpochMilli(),
                "daysRemaining" to daysRemaining,
                "expired" to now.isAfter(notAfter),
                "serialNumber" to cert.serialNumber.toString(16),
                "signatureAlgorithm" to cert.sigAlgName,
                "publicKeyType" to publicKey.algorithm,
                "publicKeySize" to keySize,
                "isCA" to (cert.basicConstraints >= 0),
                "keyUsage" to keyUsage(cert),
                "extendedKeyUsage" to extendedKeyUsage(cert),
                "sha256Fingerprint" to sha256Fingerprint(cert),
                "selfSigned" to (subject == issuer),
            )
        }
    } finally {
        conn.disconnect()
    }
}

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

private fun keyUsage(cert: X509Certificate): List<String> {
    val usage = cert.keyUsage ?: return emptyList()
    val names = listOf(
        "Digital Signature",
        "Content Commitment",
        "Key Encipherment",
        "Data Encipherment",
        "Key Agreement",
        "Key Cert Sign",
        "CRL Sign",
        "Encipher Only",
        "Decipher Only",
    )
    return usage.indices.mapNotNull { idx ->
        if (usage[idx]) names.getOrNull(idx) else null
    }
}

private fun extendedKeyUsage(cert: X509Certificate): List<String> {
    val eku = cert.extendedKeyUsage ?: return emptyList()
    val names = mapOf(
        "1.3.6.1.5.5.7.3.1" to "TLS Web Server Authentication",
        "1.3.6.1.5.5.7.3.2" to "TLS Web Client Authentication",
        "1.3.6.1.5.5.7.3.3" to "Code Signing",
        "1.3.6.1.5.5.7.3.4" to "Email Protection",
        "1.3.6.1.5.5.7.3.8" to "Time Stamping",
        "1.3.6.1.5.5.7.3.9" to "OCSP Signing",
    )
    return eku.map { names[it] ?: it }
}

private fun sha256Fingerprint(cert: X509Certificate): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
    return digest.joinToString(":") { "%02X".format(it) }
}

fun pingCheck(ctx: Context) {
    val host = ctx.pathParam("host")
    val count = ctx.queryParam("count")?.toIntOrNull() ?: 4
    try {
        val command = listOf("ping", "-c", "$count", "-W", "5", host)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().readLines()
        val exitCode = process.waitFor()

        val replies = mutableListOf<Map<String, String>>()
        val replyPattern = ".*from ([^:]+):.*time=([0-9.]+).*".toRegex()
        for (l in lines) {
            val match = replyPattern.find(l)
            if (match != null) {
                replies.add(mapOf("from" to match.groupValues[1], "time" to "${match.groupValues[2]}ms"))
            }
        }

        val statsLine = lines.find { it.contains("packets transmitted") }
        val rttLine = lines.find { it.contains("min/avg/max") }
        val received = "([0-9]+) received".toRegex().find(statsLine ?: "")?.groupValues?.get(1)
        val loss = "([0-9.]+)% packet loss".toRegex().find(statsLine ?: "")?.groupValues?.get(1)
        val rttMatch = "= ([0-9.]+)/([0-9.]+)/([0-9.]+)/([0-9.]+)".toRegex().find(rttLine ?: "")

        val response = mapOf(
            "host" to host, "packetsSent" to count, "packetsReceived" to received,
            "packetLoss" to "${loss ?: "?"}%",
            "minRtt" to rttMatch?.groupValues?.get(1)?.plus("ms"),
            "avgRtt" to rttMatch?.groupValues?.get(2)?.plus("ms"),
            "maxRtt" to rttMatch?.groupValues?.get(3)?.plus("ms"),
            "status" to if (exitCode == 0) "Reachable" else "Unreachable",
            "replies" to replies,
        )
        ctx.result(json.writeValueAsString(response))
    } catch (e: Exception) {
        ctx.result(json.writeValueAsString(mapOf("error" to "Ping failed: ${e.message}")))
    }
    ctx.contentType("application/json")
}

fun traceCheck(ctx: Context) {
    val host = ctx.pathParam("host")
    val maxHops = ctx.queryParam("maxHops")?.toIntOrNull() ?: 30
    try {
        val command = listOf("traceroute", "-m", "$maxHops", "-w", "3", host)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor()

        val hops = mutableListOf<Map<String, String>>()
        val hopPattern = "^\\s*([0-9]+)\\s+(.+)$".toRegex()
        for (line in lines.drop(1)) {
            val match = hopPattern.find(line)
            if (match != null) {
                val hopNum = match.groupValues[1]
                val rest = match.groupValues[2].trim()
                if (rest.contains("* * *")) {
                    hops.add(mapOf("hop" to hopNum, "host" to "*", "ip" to "*", "rtt" to "* * *"))
                } else {
                    val hostMatch = "^([^(]+)\\(([^)]+)\\)(.*)$".toRegex().find(rest)
                    if (hostMatch != null) {
                        hops.add(mapOf(
                            "hop" to hopNum, "host" to hostMatch.groupValues[1].trim(),
                            "ip" to hostMatch.groupValues[2].trim(), "rtt" to hostMatch.groupValues[3].trim(),
                        ))
                    } else {
                        hops.add(mapOf("hop" to hopNum, "host" to rest, "ip" to "", "rtt" to ""))
                    }
                }
            }
        }
        ctx.result(json.writeValueAsString(mapOf("host" to host, "maxHops" to maxHops, "hops" to hops)))
    } catch (e: Exception) {
        ctx.result(json.writeValueAsString(mapOf("error" to "Traceroute failed: ${e.message}")))
    }
    ctx.contentType("application/json")
}

/**
 * Enhanced traceroute with ASN and geolocation enrichment.
 * For each hop, looks up:
 * - ASN via Team Cymru DNS (reversed-ip.origin.asn.cymru.com TXT)
 * - Geolocation via ip-api.com batch API (free, no key needed)
 * Returns enriched hop data suitable for map and diagram visualization.
 */
fun traceVisual(ctx: Context) {
    val host = ctx.pathParam("host")
    val maxHops = ctx.queryParam("maxHops")?.toIntOrNull() ?: 30
    val includeGeo = ctx.queryParam("geo")?.lowercase() == "true"
    try {
        // Step 1: Run traceroute
        val command = listOf("traceroute", "-m", "$maxHops", "-w", "3", host)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor()

        data class RawHop(val hop: Int, val host: String, val ip: String, val rttRaw: String)

        val rawHops = mutableListOf<RawHop>()
        val hopPattern = "^\\s*([0-9]+)\\s+(.+)$".toRegex()
        for (line in lines.drop(1)) {
            val match = hopPattern.find(line) ?: continue
            val hopNum = match.groupValues[1].toInt()
            val rest = match.groupValues[2].trim()
            if (rest.contains("* * *")) {
                rawHops.add(RawHop(hopNum, "*", "*", "* * *"))
            } else {
                val hostMatch = "^([^(]+)\\(([^)]+)\\)(.*)$".toRegex().find(rest)
                if (hostMatch != null) {
                    rawHops.add(RawHop(hopNum, hostMatch.groupValues[1].trim(),
                        hostMatch.groupValues[2].trim(), hostMatch.groupValues[3].trim()))
                } else {
                    rawHops.add(RawHop(hopNum, rest, "", ""))
                }
            }
        }

        // Step 2: Parse RTT values (extract numeric ms values)
        fun parseRtt(raw: String): Double? {
            val times = "([0-9]+\\.?[0-9]*)\\s*ms".toRegex().findAll(raw).map { it.groupValues[1].toDouble() }.toList()
            return if (times.isNotEmpty()) times.average() else null
        }

        // Step 3: Collect unique IPs for enrichment
        val validIps = rawHops.map { it.ip }.filter { it != "*" && it.isNotEmpty() && it != "127.0.0.1" }.distinct()

        // Step 4: ASN lookup via Team Cymru DNS (batch via individual TXT queries)
        val resolver = DnsResolver(timeout = Duration.ofSeconds(2))
        val asnMap = mutableMapOf<String, Map<String, String>>()
        for (ip in validIps) {
            try {
                val reversed = ip.split(".").reversed().joinToString(".")
                val dnsQuery = "$reversed.origin.asn.cymru.com"
                val result = resolver.lookup(dnsQuery, Type.TXT)
                if (result.isSuccessful && result.records.isNotEmpty()) {
                    val txt = result.records.first().data
                    val parts = txt.split("|").map { it.trim() }
                    if (parts.size >= 5) {
                        val asn = parts[0]
                        // Also get AS name
                        var asName = ""
                        try {
                            val nameResult = resolver.lookup("AS$asn.asn.cymru.com", Type.TXT)
                            if (nameResult.isSuccessful && nameResult.records.isNotEmpty()) {
                                val nameParts = nameResult.records.first().data.split("|").map { it.trim() }
                                if (nameParts.size >= 5) asName = nameParts[4]
                            }
                        } catch (_: Exception) {}
                        asnMap[ip] = mapOf("asn" to "AS$asn", "prefix" to parts[1],
                            "country" to parts[2], "registry" to parts[3], "asName" to asName)
                    }
                }
            } catch (_: Exception) {}
        }

        // Step 5: Geolocation via ip-api.com batch API (opt-in, rate-limited)
        val geoMap = mutableMapOf<String, Map<String, Any?>>()
        if (includeGeo && validIps.isNotEmpty()) {
            try {
                val batchBody = json.writeValueAsString(validIps.map { mapOf("query" to it) })
                val uri = java.net.URI.create("http://ip-api.com/batch?fields=query,status,country,countryCode,regionName,city,lat,lon,isp,org")
                val request = java.net.http.HttpRequest.newBuilder()
                    .uri(uri).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(batchBody)).build()
                val client = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() == 200) {
                    val arr = json.readTree(response.body())
                    if (arr.isArray) {
                        arr.forEach { node ->
                            if (node.get("status")?.asText() == "success") {
                                val q = node.get("query")?.asText() ?: return@forEach
                                geoMap[q] = mapOf(
                                    "country" to node.get("country")?.asText(),
                                    "countryCode" to node.get("countryCode")?.asText(),
                                    "region" to node.get("regionName")?.asText(),
                                    "city" to node.get("city")?.asText(),
                                    "lat" to node.get("lat")?.asDouble(),
                                    "lon" to node.get("lon")?.asDouble(),
                                    "isp" to node.get("isp")?.asText(),
                                    "org" to node.get("org")?.asText(),
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Geo lookup is best-effort
            }
        }

        // Step 6: Build enriched response
        val enrichedHops = rawHops.map { hop ->
            val rttAvg = parseRtt(hop.rttRaw)
            val asn = asnMap[hop.ip]
            val geo = geoMap[hop.ip]
            val m = mutableMapOf<String, Any?>(
                "hop" to hop.hop, "host" to hop.host, "ip" to hop.ip,
                "rttRaw" to hop.rttRaw, "rttAvg" to rttAvg,
            )
            if (asn != null) {
                m["asn"] = asn["asn"]; m["asName"] = asn["asName"]
                m["prefix"] = asn["prefix"]; m["asnCountry"] = asn["country"]
            }
            if (geo != null) {
                m["lat"] = geo["lat"]; m["lon"] = geo["lon"]
                m["city"] = geo["city"]; m["region"] = geo["region"]
                m["country"] = geo["country"]; m["countryCode"] = geo["countryCode"]
                m["isp"] = geo["isp"]; m["org"] = geo["org"]
            }
            m
        }

        ctx.result(json.writeValueAsString(mapOf(
            "host" to host, "maxHops" to maxHops,
            "hopCount" to rawHops.size,
            "hops" to enrichedHops,
        )))
    } catch (e: Exception) {
        ctx.result(json.writeValueAsString(mapOf("error" to "Traceroute failed: ${e.message}")))
    }
    ctx.contentType("application/json")
}

// ─── WHOIS Tools ─────────────────────────────────────────────────────────────

fun whoisLookup(ctx: Context) {
    val query = ctx.pathParam("query")
    val server = determineWhoisServer(query)
    try {
        val response = queryWhois(server, query)
        val fields = parseWhoisFields(response)
        ctx.result(json.writeValueAsString(mapOf("query" to query, "server" to server, "fields" to fields, "raw" to response)))
    } catch (e: Exception) {
        ctx.result(json.writeValueAsString(mapOf("error" to "WHOIS failed: ${e.message}")))
    }
    ctx.contentType("application/json")
}

private fun determineWhoisServer(query: String): String {
    if (query.matches("[0-9.]+".toRegex()) || query.contains(":")) return "whois.arin.net"
    val tld = query.substringAfterLast(".")
    return when (tld.lowercase()) {
        "com", "net" -> "whois.verisign-grs.com"
        "org" -> "whois.pir.org"
        "io" -> "whois.nic.io"
        "dev", "app" -> "whois.nic.google"
        "no" -> "whois.norid.no"
        "se" -> "whois.iis.se"
        "dk" -> "whois.dk-hostmaster.dk"
        "uk" -> "whois.nic.uk"
        "de" -> "whois.denic.de"
        "fr" -> "whois.nic.fr"
        "eu" -> "whois.eu"
        else -> "whois.iana.org"
    }
}

private fun queryWhois(server: String, query: String): String {
    Socket().use { socket ->
        socket.connect(InetSocketAddress(server, 43), 10000)
        socket.soTimeout = 10000
        val writer = PrintWriter(socket.getOutputStream(), true)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        writer.println(query)
        return reader.readText()
    }
}

private fun parseWhoisFields(response: String): Map<String, String> {
    val fields = linkedMapOf<String, String>()
    val keyFields = setOf(
        "Domain Name", "Registry Domain ID", "Registrar",
        "Registrar WHOIS Server", "Registrar URL",
        "Updated Date", "Creation Date", "Registry Expiry Date",
        "Registrant Organization", "Registrant Country",
        "Name Server", "DNSSEC", "Status",
        "NetRange", "CIDR", "NetName", "OrgName", "OrgId",
    )
    for (line in response.lines()) {
        val colonIndex = line.indexOf(':')
        if (colonIndex > 0) {
            val key = line.substring(0, colonIndex).trim()
            val value = line.substring(colonIndex + 1).trim()
            if (key in keyFields && value.isNotEmpty()) {
                fields[key] = if (fields.containsKey(key)) "${fields[key]}, $value" else value
            }
        }
    }
    return fields
}

// ─── Utility Tools ───────────────────────────────────────────────────────────

fun whatIsMyIp(ctx: Context) {
    val details = linkedMapOf<String, String>()
    try {
        val resolver = DnsResolver()
        val result = resolver.lookup("myip.opendns.com", Type.A)
        if (result.isSuccessful && result.records.isNotEmpty()) {
            details["opendns"] = result.records.first().data
        }
    } catch (_: Exception) {
        details["opendns"] = "Failed"
    }

    val httpClient = HttpClient(timeout = Duration.ofSeconds(5))
    val services = listOf("https://ifconfig.me/ip" to "ifconfig.me", "https://icanhazip.com" to "icanhazip.com", "https://api.ipify.org" to "ipify.org")
    for ((url, name) in services) {
        try {
            val result = httpClient.get(url, includeBody = true)
            val body = result.body
            details[name] = if (result.statusCode == 200 && body != null) body.trim() else "HTTP ${result.statusCode}"
        } catch (e: Exception) {
            details[name] = "Failed: ${e.message}"
        }
    }
    ctx.result(json.writeValueAsString(details))
    ctx.contentType("application/json")
}

fun subnetCalc(ctx: Context) {
    val cidr = ctx.pathParam("cidr")
    val parts = cidr.split("/")
    if (parts.size != 2) {
        ctx.result(json.writeValueAsString(mapOf("error" to "Invalid CIDR notation")))
        ctx.contentType("application/json")
        return
    }
    val ipStr = parts[0]
    val prefix = parts[1].toIntOrNull()
    if (prefix == null || prefix < 0 || prefix > 32) {
        ctx.result(json.writeValueAsString(mapOf("error" to "Invalid prefix length")))
        ctx.contentType("application/json")
        return
    }
    val addr = InetAddress.getByName(ipStr)
    val addrBytes = addr.address
    val ipInt = addrBytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
    val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
    val network = ipInt and mask
    val broadcast = network or mask.inv() and 0xFFFFFFFFL
    val firstHost = if (prefix < 31) network + 1 else network
    val lastHost = if (prefix < 31) broadcast - 1 else broadcast
    val totalHosts = if (prefix <= 30) (1L shl (32 - prefix)) - 2 else if (prefix == 31) 2L else 1L

    fun longToIpv4(v: Long) = "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"

    val response = mapOf(
        "cidr" to "$ipStr/$prefix", "ip" to ipStr,
        "networkAddress" to longToIpv4(network), "broadcastAddress" to longToIpv4(broadcast),
        "subnetMask" to longToIpv4(mask), "wildcardMask" to longToIpv4(mask.inv() and 0xFFFFFFFFL),
        "firstHost" to longToIpv4(firstHost), "lastHost" to longToIpv4(lastHost),
        "totalHosts" to totalHosts, "prefixLength" to "/$prefix",
    )
    ctx.result(json.writeValueAsString(response))
    ctx.contentType("application/json")
}

fun passwordGen(ctx: Context) {
    val length = ctx.queryParam("length")?.toIntOrNull() ?: 16
    val count = ctx.queryParam("count")?.toIntOrNull() ?: 5
    val upper = ctx.queryParam("upper") != "false"
    val lower = ctx.queryParam("lower") != "false"
    val digits = ctx.queryParam("digits") != "false"
    val special = ctx.queryParam("special") != "false"

    var charset = ""
    if (upper) charset += "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    if (lower) charset += "abcdefghijklmnopqrstuvwxyz"
    if (digits) charset += "0123456789"
    if (special) charset += "!@#$%^&*()-_=+[]{}|;:,.<>?"
    if (charset.isEmpty()) charset = "abcdefghijklmnopqrstuvwxyz0123456789"

    val random = SecureRandom()
    val passwords = (1..count).map {
        (1..length).map { charset[random.nextInt(charset.length)] }.joinToString("")
    }
    val entropy = length * log2(charset.length.toDouble())

    val response = mapOf(
        "passwords" to passwords, "length" to length, "count" to count,
        "charsetSize" to charset.length, "entropy" to "%.1f bits".format(entropy),
        "options" to mapOf("upper" to upper, "lower" to lower, "digits" to digits, "special" to special),
    )
    ctx.result(json.writeValueAsString(response))
    ctx.contentType("application/json")
}

fun emailExtract(ctx: Context) {
    val text = ctx.body()
    val emailPattern = "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}".toRegex()
    val emails = emailPattern.findAll(text).map { it.value }.toList().distinct()
    val domainCounts = emails.groupBy { it.substringAfter("@").lowercase() }
        .mapValues { it.value.size }
        .toList().sortedByDescending { it.second }
        .associate { it.first to it.second }

    val response = mapOf(
        "totalFound" to emails.size, "emails" to emails,
        "uniqueDomains" to domainCounts.size, "domainBreakdown" to domainCounts,
    )
    ctx.result(json.writeValueAsString(response))
    ctx.contentType("application/json")
}

// ─── Blocklist Tools ────────────────────────────────────────────────────────

private val DNSBL_SERVERS = listOf(
    "zen.spamhaus.org", "bl.spamcop.net", "b.barracudacentral.org",
    "dnsbl.sorbs.net", "spam.dnsbl.sorbs.net", "dul.dnsbl.sorbs.net",
    "dnsbl-1.uceprotect.net", "dnsbl-2.uceprotect.net", "dnsbl-3.uceprotect.net",
    "psbl.surriel.com", "db.wpbl.info", "all.s5h.net",
    "dyna.spamrats.com", "noptr.spamrats.com", "spam.spamrats.com",
    "cbl.abuseat.org", "dnsbl.dronebl.org", "rbl.interserver.net",
    "truncate.gbudb.net",
)

fun blacklistCheck(ctx: Context) {
    val ip = ctx.pathParam("ip")
    val reversed = ip.split(".").reversed().joinToString(".")
    val resolver = DnsResolver()
    val results = mutableListOf<Map<String, Any?>>()

    for (dnsbl in DNSBL_SERVERS) {
        val query = "$reversed.$dnsbl"
        val aResult = resolver.lookup(query, Type.A)
        val listed = aResult.isSuccessful && aResult.records.isNotEmpty()
        var reason: String? = null
        if (listed) {
            val txtResult = resolver.lookup(query, Type.TXT)
            if (txtResult.isSuccessful && txtResult.records.isNotEmpty()) {
                reason = txtResult.records.first().data
            }
        }
        results.add(mapOf("server" to dnsbl, "listed" to listed, "reason" to reason))
    }

    val listedCount = results.count { it["listed"] == true }
    val response = mapOf(
        "ip" to ip, "totalChecked" to DNSBL_SERVERS.size,
        "listedOn" to listedCount, "clean" to (listedCount == 0), "results" to results,
    )
    ctx.result(json.writeValueAsString(response))
    ctx.contentType("application/json")
}

// ─── DNS Health Check ────────────────────────────────────────────────────────

private fun addCheck(checks: MutableList<Map<String, Any>>, category: String, check: String, status: String, detail: String) {
    checks.add(mapOf("category" to category, "check" to check, "status" to status, "detail" to detail))
}

fun dnsHealthCheck(ctx: Context) {
    val domain = ctx.pathParam("domain")
    val resolver = DnsResolver()
    val checks = mutableListOf<Map<String, Any>>()
    val nameservers = mutableListOf<Map<String, Any?>>()

    // 1. Get NS records for the domain
    val nsResult = resolver.lookup(domain, Type.NS)
    val nsNames = nsResult.records.map { it.data.trimEnd('.') }

    // 2. Get SOA record and parse fields
    val soaResult = resolver.lookup(domain, Type.SOA)
    var soaSerial = 0L
    var soaRefresh = 0L
    var soaRetry = 0L
    var soaExpire = 0L
    var soaMinimum = 0L
    var soaPrimary = ""
    var soaAdmin = ""
    if (soaResult.isSuccessful && soaResult.records.isNotEmpty()) {
        try {
            val soaMsg = resolver.rawQuery(domain, Type.SOA)
            val soaRec = soaMsg.getSection(Section.ANSWER)
                .filterIsInstance<SOARecord>().firstOrNull()
            if (soaRec != null) {
                soaSerial = soaRec.serial
                soaRefresh = soaRec.refresh
                soaRetry = soaRec.retry
                soaExpire = soaRec.expire
                soaMinimum = soaRec.minimum
                soaPrimary = soaRec.host.toString().trimEnd('.')
                soaAdmin = soaRec.admin.toString().trimEnd('.')
            }
        } catch (_: Exception) {}
    }

    // 3. For each NS: resolve IP, query it directly, measure response time, check auth
    val serialNumbers = mutableListOf<Long>()
    val nsIpMap = mutableMapOf<String, String>() // nsName -> IP
    for (nsName in nsNames) {
        val nsInfo = mutableMapOf<String, Any?>(
            "type" to "NS",
            "name" to nsName,
            "ip" to null,
            "ttl" to nsResult.records.firstOrNull { it.data.trimEnd('.') == nsName }?.ttl,
            "status" to "FAIL",
            "timeMs" to null,
            "authoritative" to false,
            "responding" to false,
        )
        try {
            val aResult = resolver.lookup(nsName, Type.A)
            val ip = aResult.records.firstOrNull()?.data
            if (ip != null) {
                nsInfo["ip"] = ip
                nsIpMap[nsName] = ip
                try {
                    val nsResolver = SimpleResolver(ip)
                    nsResolver.setTimeout(Duration.ofSeconds(5))
                    val dnsName = Name.fromString("$domain.")
                    val record = Record.newRecord(dnsName, Type.SOA, DClass.IN)
                    val query = Message.newQuery(record)
                    val startTime = System.nanoTime()
                    val response = nsResolver.send(query)
                    val elapsed = (System.nanoTime() - startTime) / 1_000_000
                    nsInfo["timeMs"] = elapsed
                    nsInfo["responding"] = true
                    nsInfo["authoritative"] = response.header.getFlag(Flags.AA.toInt())
                    nsInfo["status"] = if (response.header.getFlag(Flags.AA.toInt())) "OK" else "WARN"
                    val nsSoa = response.getSection(Section.ANSWER)
                        .filterIsInstance<SOARecord>().firstOrNull()
                    if (nsSoa != null) {
                        serialNumbers.add(nsSoa.serial)
                        nsInfo["serial"] = nsSoa.serial
                    }
                } catch (_: Exception) {
                    nsInfo["responding"] = false
                    nsInfo["status"] = "FAIL"
                }
            }
        } catch (_: Exception) {}
        nameservers.add(nsInfo)
    }

    // 4. Get parent NS list (query parent zone for NS records of this domain)
    val parentNsNames = mutableListOf<String>()
    try {
        val parts = domain.split(".")
        if (parts.size >= 2) {
            val parentZone = parts.drop(1).joinToString(".")
            val parentNsResult = resolver.lookup(parentZone, Type.NS)
            if (parentNsResult.isSuccessful && parentNsResult.records.isNotEmpty()) {
                val parentNsIp = resolver.lookup(
                    parentNsResult.records.first().data.trimEnd('.'), Type.A
                ).records.firstOrNull()?.data
                if (parentNsIp != null) {
                    val parentResolver = SimpleResolver(parentNsIp)
                    parentResolver.setTimeout(Duration.ofSeconds(5))
                    val dnsName = Name.fromString("$domain.")
                    val rec = Record.newRecord(dnsName, Type.NS, DClass.IN)
                    val q = Message.newQuery(rec)
                    val resp = parentResolver.send(q)
                    val parentNs = resp.getSection(Section.ANSWER)
                        .filterIsInstance<NSRecord>()
                        .ifEmpty { resp.getSection(Section.AUTHORITY).filterIsInstance<NSRecord>() }
                    parentNsNames.addAll(parentNs.map { it.target.toString().trimEnd('.') })
                }
            }
        }
    } catch (_: Exception) {}

    // Collect per-NS SOA data for consistency checks
    data class NsSoaData(val rname: String, val mname: String, val refresh: Long, val retry: Long, val expire: Long, val minimum: Long)
    val nsSoaMap = mutableMapOf<String, NsSoaData>()
    val nsNsSetMap = mutableMapOf<String, Set<String>>()
    for (ns in nameservers) {
        val ip = ns["ip"] as? String ?: continue
        val nsName = ns["name"] as? String ?: continue
        try {
            val nsResolver = SimpleResolver(ip)
            nsResolver.setTimeout(Duration.ofSeconds(3))
            val soaQ = Message.newQuery(Record.newRecord(Name.fromString("$domain."), Type.SOA, DClass.IN))
            val soaR = nsResolver.send(soaQ)
            val soa = soaR.getSection(Section.ANSWER).filterIsInstance<SOARecord>().firstOrNull()
            if (soa != null) {
                nsSoaMap[nsName] = NsSoaData(
                    soa.admin.toString().trimEnd('.'), soa.host.toString().trimEnd('.'),
                    soa.refresh, soa.retry, soa.expire, soa.minimum
                )
            }
            // Get NS set from this NS
            val nsQ = Message.newQuery(Record.newRecord(Name.fromString("$domain."), Type.NS, DClass.IN))
            val nsR = nsResolver.send(nsQ)
            val nsSet = nsR.getSection(Section.ANSWER).filterIsInstance<NSRecord>()
                .map { it.target.toString().trimEnd('.').lowercase() }.toSet()
            if (nsSet.isNotEmpty()) nsNsSetMap[nsName] = nsSet
        } catch (_: Exception) {}
    }

    val nsIps = nameservers.mapNotNull { it["ip"] as? String }

    // ═══════════════════════════════════════════════════════════════════════════
    // BASIC checks (Zonemaster Basic-TP)
    // ═══════════════════════════════════════════════════════════════════════════

    // BASIC01: Parent zone exists
    val parentExists = try {
        val parts = domain.split(".")
        if (parts.size >= 2) {
            val parentZone = parts.drop(1).joinToString(".")
            val pSoa = resolver.lookup(parentZone, Type.SOA)
            pSoa.isSuccessful && pSoa.records.isNotEmpty()
        } else false
    } catch (_: Exception) { false }
    addCheck(checks, "Basic", "Parent Zone Exists", if (parentExists) "PASS" else "FAIL",
        if (parentExists) "Parent zone found" else "Could not find parent zone")

    // BASIC02: Zone has at least one working NS
    val anyResponding = nameservers.any { it["responding"] == true }
    addCheck(checks, "Basic", "Zone Has Working Name Server", if (anyResponding) "PASS" else "FAIL",
        if (anyResponding) "At least one NS is responding" else "No NS responded to queries")

    // BASIC03: Zone exists (SOA record present)
    addCheck(checks, "Basic", "Zone Exists (SOA Present)", if (soaResult.isSuccessful && soaResult.records.isNotEmpty()) "PASS" else "FAIL",
        if (soaResult.isSuccessful) "SOA record found for $domain" else "No SOA record — zone may not exist")

    // ═══════════════════════════════════════════════════════════════════════════
    // DELEGATION checks (Zonemaster Delegation-TP)
    // ═══════════════════════════════════════════════════════════════════════════

    // DELEGATION01: Minimum number of NS (at least 2)
    addCheck(checks, "Delegation", "At Least Two Name Servers", if (nsNames.size >= 2) "PASS" else "FAIL",
        "${nsNames.size} nameserver(s) found")

    // DELEGATION02: NS must have distinct IPs
    val distinctIps = nsIps.distinct()
    addCheck(checks, "Delegation", "NS Have Distinct IP Addresses", if (distinctIps.size == nsIps.size && nsIps.isNotEmpty()) "PASS" else if (nsIps.isEmpty()) "WARN" else "FAIL",
        if (distinctIps.size == nsIps.size) "${nsIps.size} distinct IPs" else "Duplicate IPs found: ${nsIps.groupBy { it }.filter { it.value.size > 1 }.keys.joinToString(", ")}")

    // DELEGATION04: NS is authoritative
    val allAuth = nameservers.all { it["authoritative"] == true }
    addCheck(checks, "Delegation", "All NS Are Authoritative", if (allAuth && nameservers.isNotEmpty()) "PASS" else "FAIL",
        if (allAuth) "All nameservers returned AA flag" else "Some nameservers are not authoritative")

    // DELEGATION05: NS must not point at CNAME
    val nsCnameIssues = mutableListOf<String>()
    for (nsName in nsNames) {
        try {
            val cnameResult = resolver.lookup(nsName, Type.CNAME)
            if (cnameResult.isSuccessful && cnameResult.records.isNotEmpty()) {
                nsCnameIssues.add(nsName)
            }
        } catch (_: Exception) {}
    }
    addCheck(checks, "Delegation", "NS Not Pointing To CNAME", if (nsCnameIssues.isEmpty()) "PASS" else "FAIL",
        if (nsCnameIssues.isEmpty()) "No NS hostnames are CNAMEs" else "NS pointing to CNAME: ${nsCnameIssues.joinToString(", ")}")

    // DELEGATION06: SOA exists
    addCheck(checks, "Delegation", "SOA Record Exists", if (soaResult.isSuccessful && soaResult.records.isNotEmpty()) "PASS" else "FAIL",
        if (soaResult.isSuccessful) "SOA record present" else "No SOA record found")

    // DELEGATION07: Parent glue records present in child
    val primaryAtParent = parentNsNames.isEmpty() || parentNsNames.any { it.equals(soaPrimary, ignoreCase = true) }
    addCheck(checks, "Delegation", "Primary NS Listed At Parent", if (primaryAtParent) "PASS" else "WARN",
        if (parentNsNames.isEmpty()) "Could not determine parent NS list" else "Primary NS: $soaPrimary")

    val localSorted = nsNames.map { it.lowercase() }.sorted()
    val parentSorted = parentNsNames.map { it.lowercase() }.sorted()
    val nsListsMatch = parentNsNames.isEmpty() || localSorted == parentSorted
    addCheck(checks, "Delegation", "NS List Matches Parent Delegation", if (nsListsMatch) "PASS" else "WARN",
        if (parentNsNames.isEmpty()) "Could not determine parent NS list" else if (nsListsMatch) "NS lists match" else "Local: ${nsNames.joinToString(", ")} vs Parent: ${parentNsNames.joinToString(", ")}")

    // ═══════════════════════════════════════════════════════════════════════════
    // CONSISTENCY checks (Zonemaster Consistency-TP)
    // ═══════════════════════════════════════════════════════════════════════════

    // CONSISTENCY01: SOA serial consistency
    val serialsMatch = serialNumbers.isNotEmpty() && serialNumbers.distinct().size == 1
    addCheck(checks, "Consistency", "SOA Serial Consistency", if (serialsMatch) "PASS" else if (serialNumbers.isEmpty()) "WARN" else "FAIL",
        if (serialsMatch) "All serials: ${serialNumbers.first()}" else if (serialNumbers.isEmpty()) "Could not retrieve serials" else "Serials differ: ${serialNumbers.distinct().joinToString(", ")}")

    // CONSISTENCY02: SOA RNAME consistency across NS
    val rnames = nsSoaMap.values.map { it.rname }.distinct()
    addCheck(checks, "Consistency", "SOA RNAME Consistent Across NS", if (rnames.size <= 1) "PASS" else "FAIL",
        if (rnames.size <= 1) "RNAME consistent: ${rnames.firstOrNull() ?: soaAdmin}" else "RNAME differs: ${rnames.joinToString(", ")}")

    // CONSISTENCY03: SOA timers consistency across NS
    val timerSets = nsSoaMap.values.map { "${it.refresh}/${it.retry}/${it.expire}/${it.minimum}" }.distinct()
    addCheck(checks, "Consistency", "SOA Timers Consistent Across NS", if (timerSets.size <= 1) "PASS" else "FAIL",
        if (timerSets.size <= 1) "SOA timers consistent across all NS" else "SOA timers differ across nameservers")

    // CONSISTENCY04: NS record set consistency across NS
    val nsSetValues = nsNsSetMap.values.distinct()
    addCheck(checks, "Consistency", "NS Record Set Consistent Across NS", if (nsSetValues.size <= 1) "PASS" else "WARN",
        if (nsSetValues.size <= 1) "All NS return same NS record set" else "NS record sets differ across nameservers")

    // CONSISTENCY06: SOA MNAME consistency across NS
    val mnames = nsSoaMap.values.map { it.mname }.distinct()
    addCheck(checks, "Consistency", "SOA MNAME Consistent Across NS", if (mnames.size <= 1) "PASS" else "FAIL",
        if (mnames.size <= 1) "MNAME consistent: ${mnames.firstOrNull() ?: soaPrimary}" else "MNAME differs: ${mnames.joinToString(", ")}")

    // ═══════════════════════════════════════════════════════════════════════════
    // ADDRESS checks (Zonemaster Address-TP)
    // ═══════════════════════════════════════════════════════════════════════════

    // ADDRESS01: NS IP addresses must be globally reachable (public IPs)
    val allPublic = nsIps.all { ip ->
        val p = ip.split(".").map { it.toIntOrNull() ?: 0 }
        !(p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) || p[0] == 127)
    }
    addCheck(checks, "Address", "NS Have Public IP Addresses", if (allPublic && nsIps.isNotEmpty()) "PASS" else if (nsIps.isEmpty()) "WARN" else "FAIL",
        if (allPublic) "All NS IPs are globally reachable" else "Some NS IPs are private/reserved")

    // ADDRESS02: Reverse DNS (PTR) entry exists for each NS IP
    val ptrMissing = mutableListOf<String>()
    val ptrMismatch = mutableListOf<String>()
    for ((nsName, ip) in nsIpMap) {
        try {
            val ptrResult = resolver.reverseLookup(ip)
            if (!ptrResult.isSuccessful || ptrResult.records.isEmpty()) {
                ptrMissing.add(ip)
            } else {
                // ADDRESS03: Reverse DNS entry matches NS hostname
                val ptrName = ptrResult.records.first().data.trimEnd('.')
                if (!ptrName.equals(nsName, ignoreCase = true)) {
                    ptrMismatch.add("$ip → $ptrName (expected $nsName)")
                }
            }
        } catch (_: Exception) { ptrMissing.add(ip) }
    }
    addCheck(checks, "Address", "Reverse DNS (PTR) Exists For NS IPs", if (ptrMissing.isEmpty()) "PASS" else "WARN",
        if (ptrMissing.isEmpty()) "All NS IPs have PTR records" else "Missing PTR for: ${ptrMissing.joinToString(", ")}")
    addCheck(checks, "Address", "Reverse DNS Matches NS Hostname", if (ptrMismatch.isEmpty() && ptrMissing.isEmpty()) "PASS" else if (ptrMismatch.isNotEmpty()) "WARN" else "INFO",
        if (ptrMismatch.isEmpty()) "All PTR records match NS hostnames" else "Mismatches: ${ptrMismatch.joinToString("; ")}")

    // ═══════════════════════════════════════════════════════════════════════════
    // CONNECTIVITY checks (Zonemaster Connectivity-TP)
    // ═══════════════════════════════════════════════════════════════════════════

    // CONNECTIVITY01: UDP connectivity (already tested via SOA query above)
    val allResponding = nameservers.all { it["responding"] == true }
    addCheck(checks, "Connectivity", "UDP Connectivity To All NS", if (allResponding && nameservers.isNotEmpty()) "PASS" else "FAIL",
        if (allResponding) "All ${nameservers.size} NS responded via UDP" else "${nameservers.count { it["responding"] == true }}/${nameservers.size} responding via UDP")

    // CONNECTIVITY02: TCP connectivity to NS (port 53)
    val tcpFailed = mutableListOf<String>()
    for (ns in nameservers) {
        val ip = ns["ip"] as? String ?: continue
        try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(ip, 53), 5000)
            }
        } catch (_: Exception) { tcpFailed.add("${ns["name"]} ($ip)") }
    }
    addCheck(checks, "Connectivity", "TCP Connectivity To All NS (Port 53)", if (tcpFailed.isEmpty() && nsIps.isNotEmpty()) "PASS" else if (nsIps.isEmpty()) "WARN" else "FAIL",
        if (tcpFailed.isEmpty()) "All NS accept TCP connections on port 53" else "TCP failed: ${tcpFailed.joinToString(", ")}")

    // CONNECTIVITY03: AS diversity (NS in different ASNs)
    val nsAsns = mutableMapOf<String, String>()
    for (ip in nsIps) {
        try {
            val reversed = ip.split(".").reversed().joinToString(".")
            val asnResult = resolver.lookup("$reversed.origin.asn.cymru.com", Type.TXT)
            if (asnResult.isSuccessful && asnResult.records.isNotEmpty()) {
                val asn = asnResult.records.first().data.split("|").firstOrNull()?.trim() ?: "unknown"
                nsAsns[ip] = asn
            }
        } catch (_: Exception) {}
    }
    val distinctAsns = nsAsns.values.distinct()
    addCheck(checks, "Connectivity", "NS In Multiple AS Networks", if (distinctAsns.size >= 2) "PASS" else if (distinctAsns.size == 1) "WARN" else "INFO",
        if (distinctAsns.size >= 2) "${distinctAsns.size} distinct ASNs: ${distinctAsns.joinToString(", ").take(80)}" else if (distinctAsns.size == 1) "All NS in same ASN: ${distinctAsns.first()}" else "Could not determine ASNs")

    // CONNECTIVITY04: IP prefix diversity (different /24 subnets)
    val subnets = nsIps.map { ip -> ip.split(".").take(3).joinToString(".") }.distinct()
    addCheck(checks, "Connectivity", "NS IP Prefix Diversity", if (subnets.size >= 2) "PASS" else if (nsIps.size < 2) "WARN" else "WARN",
        if (subnets.size >= 2) "${subnets.size} distinct /24 subnets" else "All nameservers in same /24 subnet")

    // ═══════════════════════════════════════════════════════════════════════════
    // NAMESERVER checks (Zonemaster Nameserver-TP)
    // ═══════════════════════════════════════════════════════════════════════════

    // NAMESERVER01: NS should not be a recursor (open recursive)
    var openRecursive = false
    val openRecursiveNs = mutableListOf<String>()
    for (ns in nameservers) {
        val ip = ns["ip"] as? String ?: continue
        try {
            val nsResolver = SimpleResolver(ip)
            nsResolver.setTimeout(Duration.ofSeconds(3))
            val testName = Name.fromString("www.google.com.")
            val testRec = Record.newRecord(testName, Type.A, DClass.IN)
            val testQuery = Message.newQuery(testRec)
            testQuery.header.setFlag(Flags.RD.toInt())
            val testResp = nsResolver.send(testQuery)
            if (testResp.header.getFlag(Flags.RA.toInt()) && testResp.getSection(Section.ANSWER).isNotEmpty()) {
                openRecursive = true
                openRecursiveNs.add("${ns["name"]} ($ip)")
            }
        } catch (_: Exception) {}
    }
    addCheck(checks, "Nameserver", "No Open Recursive NS Detected", if (!openRecursive) "PASS" else "FAIL",
        if (!openRecursive) "No open recursive resolvers found" else "Open recursive: ${openRecursiveNs.joinToString(", ")}")

    // NAMESERVER02: EDNS0 support
    val ednsUnsupported = mutableListOf<String>()
    for (ns in nameservers) {
        val ip = ns["ip"] as? String ?: continue
        try {
            val nsResolver = SimpleResolver(ip)
            nsResolver.setTimeout(Duration.ofSeconds(3))
            nsResolver.setEDNS(0, 4096, 0, emptyList())
            val ednsQ = Message.newQuery(Record.newRecord(Name.fromString("$domain."), Type.SOA, DClass.IN))
            val ednsR = nsResolver.send(ednsQ)
            val opt = ednsR.getOPT()
            if (opt == null) ednsUnsupported.add("${ns["name"]}")
        } catch (_: Exception) { ednsUnsupported.add("${ns["name"]}") }
    }
    addCheck(checks, "Nameserver", "EDNS0 Support", if (ednsUnsupported.isEmpty() && nsIps.isNotEmpty()) "PASS" else if (nsIps.isEmpty()) "WARN" else "WARN",
        if (ednsUnsupported.isEmpty()) "All NS support EDNS0" else "EDNS0 not confirmed: ${ednsUnsupported.joinToString(", ")}")

    // NAMESERVER03: AXFR disabled (zone transfer should be refused)
    val axfrAllowed = mutableListOf<String>()
    for (ns in nameservers) {
        val ip = ns["ip"] as? String ?: continue
        try {
            val nsResolver = SimpleResolver(ip)
            nsResolver.setTimeout(Duration.ofSeconds(3))
            val axfrQ = Message.newQuery(Record.newRecord(Name.fromString("$domain."), Type.AXFR, DClass.IN))
            val axfrR = nsResolver.send(axfrQ)
            if (axfrR.header.rcode == Rcode.NOERROR && axfrR.getSection(Section.ANSWER).size > 1) {
                axfrAllowed.add("${ns["name"]} ($ip)")
            }
        } catch (_: Exception) { /* refused or timeout = good */ }
    }
    addCheck(checks, "Nameserver", "AXFR (Zone Transfer) Disabled", if (axfrAllowed.isEmpty()) "PASS" else "FAIL",
        if (axfrAllowed.isEmpty()) "Zone transfer refused by all NS" else "AXFR allowed: ${axfrAllowed.joinToString(", ")}")

    // NAMESERVER08: QNAME case insensitivity
    val caseInsensitiveFail = mutableListOf<String>()
    for (ns in nameservers) {
        val ip = ns["ip"] as? String ?: continue
        try {
            val nsResolver = SimpleResolver(ip)
            nsResolver.setTimeout(Duration.ofSeconds(3))
            val mixedCase = domain.mapIndexed { i, c -> if (i % 2 == 0) c.uppercaseChar() else c.lowercaseChar() }.joinToString("")
            val caseQ = Message.newQuery(Record.newRecord(Name.fromString("$mixedCase."), Type.SOA, DClass.IN))
            val caseR = nsResolver.send(caseQ)
            if (caseR.header.rcode != Rcode.NOERROR) {
                caseInsensitiveFail.add("${ns["name"]}")
            }
        } catch (_: Exception) {}
    }
    addCheck(checks, "Nameserver", "QNAME Case Insensitivity", if (caseInsensitiveFail.isEmpty() && nsIps.isNotEmpty()) "PASS" else if (caseInsensitiveFail.isNotEmpty()) "WARN" else "INFO",
        if (caseInsensitiveFail.isEmpty()) "All NS handle mixed-case queries correctly" else "Case sensitivity issues: ${caseInsensitiveFail.joinToString(", ")}")

    // NAMESERVER15: Software version hidden (version.bind)
    val versionExposed = mutableListOf<String>()
    for (ns in nameservers) {
        val ip = ns["ip"] as? String ?: continue
        try {
            val nsResolver = SimpleResolver(ip)
            nsResolver.setTimeout(Duration.ofSeconds(3))
            val versionQ = Message.newQuery(Record.newRecord(Name.fromString("version.bind."), Type.TXT, DClass.CH))
            val versionR = nsResolver.send(versionQ)
            val versionTxt = versionR.getSection(Section.ANSWER).filterIsInstance<TXTRecord>()
            if (versionTxt.isNotEmpty()) {
                val ver = versionTxt.first().strings.joinToString("")
                versionExposed.add("${ns["name"]}: $ver")
            }
        } catch (_: Exception) { /* refused = good */ }
    }
    addCheck(checks, "Nameserver", "Software Version Hidden", if (versionExposed.isEmpty()) "PASS" else "WARN",
        if (versionExposed.isEmpty()) "No NS exposes software version via version.bind" else "Version exposed: ${versionExposed.joinToString("; ").take(120)}")

    // ═══════════════════════════════════════════════════════════════════════════
    // DNSSEC checks (Zonemaster DNSSEC-TP)
    // ═══════════════════════════════════════════════════════════════════════════

    val dnskeyResult = resolver.lookup(domain, Type.DNSKEY)
    val hasDnskey = dnskeyResult.isSuccessful && dnskeyResult.records.isNotEmpty()
    val dsResult = resolver.lookup(domain, Type.DS)
    val hasDs = dsResult.isSuccessful && dsResult.records.isNotEmpty()

    // DNSSEC07: Signed zone has DS in parent
    addCheck(checks, "DNSSEC", "DNSSEC Enabled", if (hasDnskey && hasDs) "PASS" else if (hasDnskey && !hasDs) "WARN" else "INFO",
        if (hasDnskey && hasDs) "DNSKEY and DS records present — DNSSEC enabled" else if (hasDnskey) "DNSKEY present but no DS in parent" else "DNSSEC not configured")

    if (hasDnskey) {
        // DNSSEC01: DS hash digest algorithm valid
        if (hasDs) {
            try {
                val dsMsg = resolver.rawQuery(domain, Type.DS)
                val dsRecs = dsMsg.getSection(Section.ANSWER).filterIsInstance<DSRecord>()
                val weakAlgos = dsRecs.filter { it.digestID == 1 } // SHA-1 is weak
                addCheck(checks, "DNSSEC", "DS Digest Algorithm Secure", if (weakAlgos.isEmpty()) "PASS" else "WARN",
                    if (weakAlgos.isEmpty()) "All DS records use secure digest algorithms" else "${weakAlgos.size} DS record(s) use SHA-1 (weak)")
            } catch (_: Exception) {
                addCheck(checks, "DNSSEC", "DS Digest Algorithm Secure", "WARN", "Could not verify DS digest algorithms")
            }
        }

        // DNSSEC05: Valid DNSKEY algorithms
        try {
            val dnskeyMsg = resolver.dnssecQuery(domain, Type.DNSKEY)
            val dnskeyRecs = dnskeyMsg.getSection(Section.ANSWER).filterIsInstance<DNSKEYRecord>()
            val algos = dnskeyRecs.map { it.algorithm }.distinct()
            val weakDnssecAlgos = algos.filter { it in listOf(1, 3, 5, 6) } // RSAMD5, DSA, RSASHA1, DSA-NSEC3-SHA1
            addCheck(checks, "DNSSEC", "DNSKEY Algorithm Secure", if (weakDnssecAlgos.isEmpty()) "PASS" else "WARN",
                if (weakDnssecAlgos.isEmpty()) "All DNSKEY algorithms are secure" else "Weak algorithms found: ${weakDnssecAlgos.joinToString(", ")}")
        } catch (_: Exception) {
            addCheck(checks, "DNSSEC", "DNSKEY Algorithm Secure", "WARN", "Could not verify DNSKEY algorithms")
        }

        // DNSSEC08/09: Valid RRSIG for DNSKEY and SOA
        try {
            val rrsigMsg = resolver.dnssecQuery(domain, Type.DNSKEY)
            val rrsigs = rrsigMsg.getSection(Section.ANSWER).filterIsInstance<RRSIGRecord>()
            val dnskeyRrsig = rrsigs.any { it.typeCovered == Type.DNSKEY }
            addCheck(checks, "DNSSEC", "RRSIG Exists For DNSKEY", if (dnskeyRrsig) "PASS" else "FAIL",
                if (dnskeyRrsig) "DNSKEY RRset is signed" else "No RRSIG covering DNSKEY")
        } catch (_: Exception) {
            addCheck(checks, "DNSSEC", "RRSIG Exists For DNSKEY", "WARN", "Could not verify DNSKEY RRSIG")
        }

        try {
            val soaRrsigMsg = resolver.dnssecQuery(domain, Type.SOA)
            val soaRrsigs = soaRrsigMsg.getSection(Section.ANSWER).filterIsInstance<RRSIGRecord>()
            val soaRrsig = soaRrsigs.any { it.typeCovered == Type.SOA }
            addCheck(checks, "DNSSEC", "RRSIG Exists For SOA", if (soaRrsig) "PASS" else "FAIL",
                if (soaRrsig) "SOA RRset is signed" else "No RRSIG covering SOA")
        } catch (_: Exception) {
            addCheck(checks, "DNSSEC", "RRSIG Exists For SOA", "WARN", "Could not verify SOA RRSIG")
        }

        // DNSSEC10: NSEC or NSEC3 records present
        try {
            val nsecResult = resolver.lookup(domain, Type.NSEC)
            val nsec3Result = resolver.lookup(domain, Type.NSEC3PARAM)
            val hasNsec = nsecResult.isSuccessful && nsecResult.records.isNotEmpty()
            val hasNsec3 = nsec3Result.isSuccessful && nsec3Result.records.isNotEmpty()
            addCheck(checks, "DNSSEC", "NSEC/NSEC3 Records Present", if (hasNsec || hasNsec3) "PASS" else "WARN",
                if (hasNsec3) "NSEC3 authenticated denial of existence configured" else if (hasNsec) "NSEC authenticated denial of existence configured" else "No NSEC/NSEC3 records found")
        } catch (_: Exception) {
            addCheck(checks, "DNSSEC", "NSEC/NSEC3 Records Present", "WARN", "Could not check NSEC/NSEC3")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SYNTAX checks (Zonemaster Syntax-TP)
    // ═══════════════════════════════════════════════════════════════════════════

    // SYNTAX01: No illegal characters in domain name
    val validDomainChars = domain.all { it.isLetterOrDigit() || it == '.' || it == '-' }
    addCheck(checks, "Syntax", "Domain Name Has Valid Characters", if (validDomainChars) "PASS" else "FAIL",
        if (validDomainChars) "Domain name contains only valid characters" else "Domain contains illegal characters")

    // SYNTAX02: No hyphen at start/end of domain labels
    val labels = domain.split(".")
    val hyphenIssues = labels.filter { it.startsWith("-") || it.endsWith("-") }
    addCheck(checks, "Syntax", "No Leading/Trailing Hyphens In Labels", if (hyphenIssues.isEmpty()) "PASS" else "FAIL",
        if (hyphenIssues.isEmpty()) "No labels start or end with hyphen" else "Labels with hyphen issues: ${hyphenIssues.joinToString(", ")}")

    // SYNTAX03: No double hyphen at position 3-4 (unless xn--)
    val doubleHyphenIssues = labels.filter { it.length >= 4 && it[2] == '-' && it[3] == '-' && !it.startsWith("xn--") }
    addCheck(checks, "Syntax", "No Invalid Double Hyphen (pos 3-4)", if (doubleHyphenIssues.isEmpty()) "PASS" else "FAIL",
        if (doubleHyphenIssues.isEmpty()) "No invalid double-hyphen labels" else "Invalid labels: ${doubleHyphenIssues.joinToString(", ")}")

    // SYNTAX04: NS names have valid hostnames
    val invalidNsNames = nsNames.filter { ns ->
        val nsLabels = ns.split(".")
        nsLabels.any { l -> !l.all { c -> c.isLetterOrDigit() || c == '-' } || l.startsWith("-") || l.endsWith("-") }
    }
    addCheck(checks, "Syntax", "NS Names Are Valid Hostnames", if (invalidNsNames.isEmpty()) "PASS" else "FAIL",
        if (invalidNsNames.isEmpty()) "All NS hostnames are syntactically valid" else "Invalid NS names: ${invalidNsNames.joinToString(", ")}")

    // SYNTAX05/06: SOA RNAME validation
    val rnameValid = soaAdmin.isNotEmpty() && soaAdmin.contains(".") && soaAdmin.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
    addCheck(checks, "Syntax", "SOA RNAME Is Valid", if (rnameValid) "PASS" else "WARN",
        if (rnameValid) "SOA RNAME ($soaAdmin) is syntactically valid" else "SOA RNAME ($soaAdmin) may have syntax issues")

    // SYNTAX07: SOA MNAME validation
    val mnameValid = soaPrimary.isNotEmpty() && soaPrimary.contains(".") && soaPrimary.all { it.isLetterOrDigit() || it == '.' || it == '-' }
    addCheck(checks, "Syntax", "SOA MNAME Is Valid Hostname", if (mnameValid) "PASS" else "WARN",
        if (mnameValid) "SOA MNAME ($soaPrimary) is a valid hostname" else "SOA MNAME ($soaPrimary) may have syntax issues")

    // SYNTAX08: MX names have valid hostnames
    val mxResult = resolver.lookup(domain, Type.MX)
    if (mxResult.isSuccessful && mxResult.records.isNotEmpty()) {
        val mxNames = mxResult.records.map { it.data.split(" ").last().trimEnd('.') }
        val invalidMx = mxNames.filter { mx ->
            val mxLabels = mx.split(".")
            mxLabels.any { l -> !l.all { c -> c.isLetterOrDigit() || c == '-' } || l.startsWith("-") || l.endsWith("-") }
        }
        addCheck(checks, "Syntax", "MX Names Are Valid Hostnames", if (invalidMx.isEmpty()) "PASS" else "FAIL",
            if (invalidMx.isEmpty()) "All MX hostnames are syntactically valid" else "Invalid MX names: ${invalidMx.joinToString(", ")}")
    } else {
        addCheck(checks, "Syntax", "MX Names Are Valid Hostnames", "INFO", "No MX records to validate")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ZONE checks (Zonemaster Zone-TP)
    // ═══════════════════════════════════════════════════════════════════════════

    // ZONE01: SOA MNAME is fully qualified
    addCheck(checks, "Zone", "SOA MNAME Is Fully Qualified", if (soaPrimary.contains(".") && soaPrimary.isNotEmpty()) "PASS" else "WARN",
        if (soaPrimary.contains(".")) "MNAME ($soaPrimary) is fully qualified" else "MNAME ($soaPrimary) may not be fully qualified")

    // ZONE02: SOA refresh minimum value
    val refreshOk = soaRefresh in 1200..43200
    addCheck(checks, "Zone", "SOA Refresh Value In Range", if (refreshOk) "PASS" else "WARN",
        "Refresh: ${soaRefresh}s (${soaRefresh / 60} min)" + if (!refreshOk) " — recommended: 20min-12hrs" else "")

    // ZONE03: SOA retry lower than refresh
    addCheck(checks, "Zone", "SOA Retry Lower Than Refresh", if (soaRetry < soaRefresh) "PASS" else "WARN",
        if (soaRetry < soaRefresh) "Retry (${soaRetry}s) < Refresh (${soaRefresh}s)" else "Retry (${soaRetry}s) >= Refresh (${soaRefresh}s) — retry should be lower")

    // ZONE04: SOA retry at least 1 hour (3600s)
    val retryOk = soaRetry in 120..7200
    addCheck(checks, "Zone", "SOA Retry Value In Range", if (retryOk) "PASS" else "WARN",
        "Retry: ${soaRetry}s (${soaRetry / 60} min)" + if (!retryOk) " — recommended: 2min-2hrs" else "")

    // ZONE05: SOA expire minimum value
    val expireOk = soaExpire in 1209600..2419200
    addCheck(checks, "Zone", "SOA Expire Value In Range", if (expireOk) "PASS" else "WARN",
        "Expire: ${soaExpire}s (${soaExpire / 86400} days)" + if (!expireOk) " — recommended: 14-28 days" else "")

    // ZONE06: SOA minimum (negative cache TTL) maximum value
    val minTtlOk = soaMinimum in 300..86400
    addCheck(checks, "Zone", "SOA Minimum TTL In Range", if (minTtlOk) "PASS" else "WARN",
        "Minimum TTL: ${soaMinimum}s" + if (!minTtlOk) " — recommended: 300-86400s" else "")

    // SOA Serial Number Format (YYYYMMDDnn)
    val serialStr = soaSerial.toString()
    val validSerialFormat = serialStr.length == 10 && try {
        val year = serialStr.substring(0, 4).toInt()
        val month = serialStr.substring(4, 6).toInt()
        val day = serialStr.substring(6, 8).toInt()
        year in 1970..2099 && month in 1..12 && day in 1..31
    } catch (_: Exception) { false }
    addCheck(checks, "Zone", "SOA Serial Number Format Valid", if (validSerialFormat) "PASS" else "WARN",
        "Serial: $soaSerial" + if (!validSerialFormat) " (not in YYYYMMDDnn format)" else "")

    // ZONE07: SOA master (MNAME) is not a CNAME
    try {
        val mnameCname = resolver.lookup(soaPrimary, Type.CNAME)
        val mnameIsCname = mnameCname.isSuccessful && mnameCname.records.isNotEmpty()
        addCheck(checks, "Zone", "SOA MNAME Is Not A CNAME", if (!mnameIsCname) "PASS" else "FAIL",
            if (!mnameIsCname) "MNAME ($soaPrimary) is not a CNAME" else "MNAME ($soaPrimary) points to a CNAME — not allowed")
    } catch (_: Exception) {
        addCheck(checks, "Zone", "SOA MNAME Is Not A CNAME", "WARN", "Could not verify MNAME CNAME status")
    }

    // ZONE08: MX is not a CNAME
    if (mxResult.isSuccessful && mxResult.records.isNotEmpty()) {
        val mxCnameIssues = mutableListOf<String>()
        for (mxRec in mxResult.records) {
            val mxHost = mxRec.data.split(" ").last().trimEnd('.')
            try {
                val cnameCheck = resolver.lookup(mxHost, Type.CNAME)
                if (cnameCheck.isSuccessful && cnameCheck.records.isNotEmpty()) {
                    mxCnameIssues.add(mxHost)
                }
            } catch (_: Exception) {}
        }
        addCheck(checks, "Zone", "MX Records Are Not CNAMEs", if (mxCnameIssues.isEmpty()) "PASS" else "FAIL",
            if (mxCnameIssues.isEmpty()) "No MX records point to CNAMEs" else "MX pointing to CNAME: ${mxCnameIssues.joinToString(", ")}")
    }

    // ZONE09: MX record present
    addCheck(checks, "Zone", "MX Record Present", if (mxResult.isSuccessful && mxResult.records.isNotEmpty()) "PASS" else "INFO",
        if (mxResult.records.isNotEmpty()) "${mxResult.records.size} MX record(s) found" else "No MX records — domain may not receive email")

    // ZONE10: No multiple SOA records
    try {
        val soaMsg = resolver.rawQuery(domain, Type.SOA)
        val soaCount = soaMsg.getSection(Section.ANSWER).filterIsInstance<SOARecord>().size
        addCheck(checks, "Zone", "Single SOA Record", if (soaCount == 1) "PASS" else if (soaCount == 0) "FAIL" else "FAIL",
            if (soaCount == 1) "Exactly one SOA record" else if (soaCount == 0) "No SOA record found" else "$soaCount SOA records found — should be exactly 1")
    } catch (_: Exception) {
        addCheck(checks, "Zone", "Single SOA Record", "WARN", "Could not verify SOA record count")
    }

    // ZONE11: SPF policy validation
    val spfResult = resolver.lookup(domain, Type.TXT)
    val spfRecords = spfResult.records.filter { it.data.startsWith("v=spf1") }
    addCheck(checks, "Zone", "SPF Record Present", if (spfRecords.isNotEmpty()) "PASS" else "WARN",
        if (spfRecords.isNotEmpty()) "SPF record found: ${spfRecords.first().data.take(80)}" else "No SPF record found")
    if (spfRecords.size > 1) {
        addCheck(checks, "Zone", "Single SPF Record", "FAIL", "${spfRecords.size} SPF records found — should be exactly 1")
    }

    // BIMI Record (bonus check)
    val bimiResult = resolver.lookup("default._bimi.$domain", Type.TXT)
    val hasBimi = bimiResult.records.any { it.data.startsWith("v=BIMI1") }
    addCheck(checks, "Zone", "BIMI Record", if (hasBimi) "PASS" else "INFO",
        if (hasBimi) "BIMI record found" else "No BIMI record at default._bimi.$domain")

    // DMARC Record (bonus check)
    val dmarcResult = resolver.lookup("_dmarc.$domain", Type.TXT)
    val hasDmarc = dmarcResult.records.any { it.data.startsWith("v=DMARC1") }
    addCheck(checks, "Zone", "DMARC Record Present", if (hasDmarc) "PASS" else "WARN",
        if (hasDmarc) "DMARC record found" else "No DMARC record at _dmarc.$domain")

    // ═══════════════════════════════════════════════════════════════════════════
    // Build response
    // ═══════════════════════════════════════════════════════════════════════════

    val passCount = checks.count { it["status"] == "PASS" }
    val warnCount = checks.count { it["status"] == "WARN" }
    val failCount = checks.count { it["status"] == "FAIL" }
    val infoCount = checks.count { it["status"] == "INFO" }
    val overall = when {
        failCount > 0 -> "FAIL"
        warnCount > 3 -> "WARN"
        else -> "PASS"
    }

    // Group checks by category for the response
    val categories = checks.map { it["category"] as String }.distinct()

    val response = mapOf(
        "domain" to domain,
        "overallStatus" to overall,
        "soa" to mapOf(
            "primary" to soaPrimary,
            "admin" to soaAdmin,
            "serial" to soaSerial,
            "refresh" to soaRefresh,
            "retry" to soaRetry,
            "expire" to soaExpire,
            "minimum" to soaMinimum,
        ),
        "nameservers" to nameservers,
        "summary" to mapOf("pass" to passCount, "warn" to warnCount, "fail" to failCount, "info" to infoCount, "total" to checks.size),
        "categories" to categories,
        "checks" to checks,
    )
    ctx.result(json.writeValueAsString(response))
    ctx.contentType("application/json")
}

// ─── Composite Tools ────────────────────────────────────────────────────────

fun domainHealth(ctx: Context) {
    val domain = ctx.pathParam("domain")
    val resolver = DnsResolver()
    val checks = mutableListOf<Map<String, String>>()

    // DNS checks
    val soaResult = resolver.lookup(domain, Type.SOA)
    checks.add(mapOf("check" to "SOA Record", "status" to if (soaResult.isSuccessful && soaResult.records.isNotEmpty()) "PASS" else "FAIL", "detail" to (soaResult.records.firstOrNull()?.data ?: "No SOA record")))

    val nsResult = resolver.lookup(domain, Type.NS)
    checks.add(mapOf("check" to "NS Records", "status" to if (nsResult.isSuccessful && nsResult.records.size >= 2) "PASS" else if (nsResult.records.size == 1) "WARN" else "FAIL", "detail" to "${nsResult.records.size} nameserver(s)"))

    val aResult = resolver.lookup(domain, Type.A)
    checks.add(mapOf("check" to "A Record", "status" to if (aResult.isSuccessful && aResult.records.isNotEmpty()) "PASS" else "INFO", "detail" to (aResult.records.firstOrNull()?.data ?: "No A record")))

    val aaaaResult = resolver.lookup(domain, Type.AAAA)
    checks.add(mapOf("check" to "AAAA Record", "status" to if (aaaaResult.isSuccessful && aaaaResult.records.isNotEmpty()) "PASS" else "INFO", "detail" to (aaaaResult.records.firstOrNull()?.data ?: "No AAAA record")))

    // Email checks
    val mxResult = resolver.lookup(domain, Type.MX)
    checks.add(mapOf("check" to "MX Records", "status" to if (mxResult.isSuccessful && mxResult.records.isNotEmpty()) "PASS" else "WARN", "detail" to "${mxResult.records.size} MX record(s)"))

    val spfResult = resolver.lookup(domain, Type.TXT)
    val hasSpf = spfResult.records.any { it.data.startsWith("v=spf1") }
    checks.add(mapOf("check" to "SPF Record", "status" to if (hasSpf) "PASS" else "WARN", "detail" to if (hasSpf) "SPF record found" else "No SPF record"))

    val dmarcResult = resolver.lookup("_dmarc.$domain", Type.TXT)
    val hasDmarc = dmarcResult.records.any { it.data.startsWith("v=DMARC1") }
    checks.add(mapOf("check" to "DMARC Record", "status" to if (hasDmarc) "PASS" else "WARN", "detail" to if (hasDmarc) "DMARC record found" else "No DMARC record"))

    // DNSSEC check
    val dnskeyResult = resolver.lookup(domain, Type.DNSKEY)
    val hasDnssec = dnskeyResult.isSuccessful && dnskeyResult.records.isNotEmpty()
    checks.add(mapOf("check" to "DNSSEC", "status" to if (hasDnssec) "PASS" else "INFO", "detail" to if (hasDnssec) "DNSSEC enabled" else "DNSSEC not configured"))

    // Web checks
    try {
        val httpClient = HttpClient(timeout = Duration.ofSeconds(10))
        val httpsResult = httpClient.get("https://$domain", includeBody = false)
        checks.add(mapOf("check" to "HTTPS", "status" to if (httpsResult.statusCode in 200..399) "PASS" else "WARN", "detail" to "HTTP ${httpsResult.statusCode} (${httpsResult.responseTimeMs}ms)"))
    } catch (e: Exception) {
        checks.add(mapOf("check" to "HTTPS", "status" to "FAIL", "detail" to "HTTPS not available: ${e.message}"))
    }

    // CAA check
    val caaResult = resolver.lookup(domain, Type.CAA)
    checks.add(mapOf("check" to "CAA Record", "status" to if (caaResult.isSuccessful && caaResult.records.isNotEmpty()) "PASS" else "INFO", "detail" to if (caaResult.records.isNotEmpty()) "${caaResult.records.size} CAA record(s)" else "No CAA records"))

    val passCount = checks.count { it["status"] == "PASS" }
    val warnCount = checks.count { it["status"] == "WARN" }
    val failCount = checks.count { it["status"] == "FAIL" }
    val overall = when {
        failCount > 0 -> "FAIL"
        warnCount > 2 -> "WARN"
        else -> "PASS"
    }

    val response = mapOf(
        "domain" to domain, "overallStatus" to overall,
        "summary" to mapOf("pass" to passCount, "warn" to warnCount, "fail" to failCount, "info" to checks.count { it["status"] == "INFO" }),
        "checks" to checks,
    )
    ctx.result(json.writeValueAsString(response))
    ctx.contentType("application/json")
}

// ─── Generator Tools ────────────────────────────────────────────────────────

fun spfGenerator(ctx: Context) {
    val includes = ctx.queryParam("includes")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    val ip4 = ctx.queryParam("ip4")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    val ip6 = ctx.queryParam("ip6")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    val mx = ctx.queryParam("mx") == "true"
    val a = ctx.queryParam("a") == "true"
    val redirect = ctx.queryParam("redirect")
    val allPolicy = ctx.queryParam("all") ?: "~all"

    val parts = mutableListOf("v=spf1")
    if (a) parts.add("a")
    if (mx) parts.add("mx")
    for (inc in includes) parts.add("include:$inc")
    for (ip in ip4) parts.add("ip4:$ip")
    for (ip in ip6) parts.add("ip6:$ip")
    if (!redirect.isNullOrBlank()) {
        parts.add("redirect=$redirect")
    } else {
        parts.add(allPolicy)
    }

    val record = parts.joinToString(" ")
    val response = mapOf(
        "record" to record, "dnsRecordType" to "TXT",
        "dnsHost" to "@", "length" to record.length,
        "valid" to (record.length <= 255),
        "components" to parts,
    )
    ctx.result(json.writeValueAsString(response))
    ctx.contentType("application/json")
}

fun dmarcGenerator(ctx: Context) {
    val policy = ctx.queryParam("policy") ?: "none"
    val sp = ctx.queryParam("sp")
    val pct = ctx.queryParam("pct")?.toIntOrNull()
    val rua = ctx.queryParam("rua")
    val ruf = ctx.queryParam("ruf")
    val adkim = ctx.queryParam("adkim")
    val aspf = ctx.queryParam("aspf")
    val ri = ctx.queryParam("ri")?.toIntOrNull()
    val fo = ctx.queryParam("fo")

    val parts = mutableListOf("v=DMARC1", "p=$policy")
    if (!sp.isNullOrBlank()) parts.add("sp=$sp")
    if (pct != null && pct != 100) parts.add("pct=$pct")
    if (!rua.isNullOrBlank()) parts.add("rua=$rua")
    if (!ruf.isNullOrBlank()) parts.add("ruf=$ruf")
    if (!adkim.isNullOrBlank()) parts.add("adkim=$adkim")
    if (!aspf.isNullOrBlank()) parts.add("aspf=$aspf")
    if (ri != null && ri != 86400) parts.add("ri=$ri")
    if (!fo.isNullOrBlank()) parts.add("fo=$fo")

    val record = parts.joinToString("; ")
    val response = mapOf(
        "record" to record, "dnsRecordType" to "TXT",
        "dnsHost" to "_dmarc", "components" to parts,
    )
    ctx.result(json.writeValueAsString(response))
    ctx.contentType("application/json")
}
