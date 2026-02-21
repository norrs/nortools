package no.norrs.nortools.web

import io.javalin.http.Context
import no.norrs.nortools.lib.dns.DnsResolver
import no.norrs.nortools.lib.network.HttpClient
import no.norrs.nortools.tools.whois.asn.RoutinatorRouteValidator
import org.xbill.DNS.Type
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import javax.naming.ldap.LdapName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// ─── Composite Tools ────────────────────────────────────────────────────────

fun domainHealth(ctx: Context) {
    val domain = ctx.pathParam("domain")
    val resolver = DnsResolver()
    val checks = mutableListOf<DomainHealthCheck>()

    // DNS checks
    val soaResult = resolver.lookup(domain, Type.SOA)
    checks.add(
        DomainHealthCheck(
            check = "SOA Record",
            status = if (soaResult.isSuccessful && soaResult.records.isNotEmpty()) "PASS" else "FAIL",
            detail = soaResult.records.firstOrNull()?.data ?: "No SOA record",
        )
    )

    val nsResult = resolver.lookup(domain, Type.NS)
    val domainNsHosts = nsResult.records
        .map { it.data.trim().trimEnd('.') }
        .filter { it.isNotBlank() }
        .distinct()
        .take(6)
    checks.add(
        DomainHealthCheck(
            check = "NS Records",
            status = if (nsResult.isSuccessful && nsResult.records.size >= 2) "PASS" else if (nsResult.records.size == 1) "WARN" else "FAIL",
            detail = "${nsResult.records.size} nameserver(s)",
        )
    )

    val aResult = resolver.lookup(domain, Type.A)
    checks.add(
        DomainHealthCheck(
            check = "A Record",
            status = if (aResult.isSuccessful && aResult.records.isNotEmpty()) "PASS" else "INFO",
            detail = aResult.records.firstOrNull()?.data ?: "No A record",
        )
    )

    val aaaaResult = resolver.lookup(domain, Type.AAAA)
    checks.add(
        DomainHealthCheck(
            check = "AAAA Record",
            status = if (aaaaResult.isSuccessful && aaaaResult.records.isNotEmpty()) "PASS" else "INFO",
            detail = aaaaResult.records.firstOrNull()?.data ?: "No AAAA record",
        )
    )

    // Mail checks (modeled after Internet.nl mail categories)
    val mxResult = resolver.lookup(domain, Type.MX)
    val mxTargets = mxResult.records.mapNotNull { mx ->
        val parts = mx.data.trim().split(Regex("\\s+"))
        if (parts.size < 2) return@mapNotNull null
        val priority = parts[0].toIntOrNull() ?: Int.MAX_VALUE
        val host = parts[1].trimEnd('.')
        priority to host
    }.sortedBy { it.first }
    val hasNullMx = mxTargets.size == 1 && mxTargets[0].second.isEmpty()

    checks.add(
        DomainHealthCheck(
            check = "Mail / MX Records",
            status = when {
                hasNullMx -> "INFO"
                mxTargets.isNotEmpty() -> "PASS"
                else -> "FAIL"
            },
            detail = when {
                hasNullMx -> "Null MX configured (domain does not accept email)"
                mxTargets.isNotEmpty() -> "${mxTargets.size} MX record(s): ${mxTargets.joinToString(", ") { "${it.first} ${it.second}" }}"
                else -> "No MX records found"
            },
        )
    )

    if (hasNullMx) {
        checks.add(
            DomainHealthCheck(
                check = "Mail / Null MX Configuration",
                status = "PASS",
                detail = "Domain explicitly signals non-receiving mail with MX 0 .",
            )
        )
    }

    val mxHosts = mxTargets.map { it.second }.filter { it.isNotBlank() }.take(3)
    var mxWithIp = 0
    var mxWithIpv6 = 0
    val mxIpMap = mutableMapOf<String, String>()
    val mxMissingAddress = mutableListOf<String>()

    for (mxHost in mxHosts) {
        val mxA = resolver.lookup(mxHost, Type.A)
        val mxAaaa = resolver.lookup(mxHost, Type.AAAA)
        val hasAnyIp = mxA.records.isNotEmpty() || mxAaaa.records.isNotEmpty()
        if (hasAnyIp) mxWithIp++ else mxMissingAddress.add(mxHost)
        if (mxAaaa.records.isNotEmpty()) mxWithIpv6++
        mxA.records.firstOrNull()?.data?.let { mxIpMap[mxHost] = it }
        mxAaaa.records.firstOrNull()?.data?.let { if (mxHost !in mxIpMap) mxIpMap[mxHost] = it }
    }

    checks.add(
        DomainHealthCheck(
            check = "Mail / MX Host Address Records",
            status = when {
                mxHosts.isEmpty() -> "INFO"
                mxWithIp == mxHosts.size -> "PASS"
                mxWithIp > 0 -> "WARN"
                else -> "FAIL"
            },
            detail = if (mxHosts.isEmpty()) {
                "No MX hosts to validate"
            } else if (mxMissingAddress.isEmpty()) {
                "All checked MX hosts have A/AAAA records"
            } else {
                "Missing A/AAAA for: ${mxMissingAddress.joinToString(", ")}"
            },
        )
    )

    checks.add(
        DomainHealthCheck(
            check = "Mail / IPv6 Support On MX",
            status = when {
                mxHosts.isEmpty() -> "INFO"
                mxWithIpv6 == mxHosts.size -> "PASS"
                mxWithIpv6 > 0 -> "WARN"
                else -> "WARN"
            },
            detail = if (mxHosts.isEmpty()) {
                "No MX hosts to evaluate"
            } else {
                "$mxWithIpv6/${mxHosts.size} checked MX host(s) have AAAA records"
            },
        )
    )

    val spfRecords = resolver.lookup(domain, Type.TXT).records.filter { it.data.startsWith("v=spf1") }
    val spf = spfRecords.firstOrNull()?.data
    val spfStatus = when {
        spf == null -> "FAIL"
        spf.contains("-all") -> "PASS"
        spf.contains("~all") -> "WARN"
        else -> "WARN"
    }
    checks.add(
        DomainHealthCheck(
            check = "Mail / SPF Record",
            status = spfStatus,
            detail = spf ?: "No SPF record found",
        )
    )
    checks.add(
        DomainHealthCheck(
            check = "Mail / Single SPF Record",
            status = when {
                spfRecords.isEmpty() -> "FAIL"
                spfRecords.size == 1 -> "PASS"
                else -> "FAIL"
            },
            detail = "${spfRecords.size} SPF record(s)",
        )
    )

    val dmarcRecords = resolver.lookup("_dmarc.$domain", Type.TXT).records.filter { it.data.startsWith("v=DMARC1") }
    val dmarc = dmarcRecords.firstOrNull()?.data
    val dmarcPolicy = dmarc?.let { Regex("""(?:^|;)p=([^;\s]+)""").find(it)?.groupValues?.get(1) }
    val hasRua = dmarc?.contains("rua=") == true
    val hasRuf = dmarc?.contains("ruf=") == true
    val dmarcReportTargets = listOfNotNull(if (hasRua) "rua" else null, if (hasRuf) "ruf" else null)

    checks.add(
        DomainHealthCheck(
            check = "Mail / DMARC Record",
            status = if (dmarc != null) "PASS" else "FAIL",
            detail = dmarc ?: "No DMARC record found",
        )
    )
    checks.add(
        DomainHealthCheck(
            check = "Mail / DMARC Policy",
            status = when (dmarcPolicy) {
                "reject", "quarantine" -> "PASS"
                "none" -> "WARN"
                null -> "FAIL"
                else -> "WARN"
            },
            detail = "Policy: ${dmarcPolicy ?: "none"}",
        )
    )
    checks.add(
        DomainHealthCheck(
            check = "Mail / DMARC Reporting",
            status = when {
                dmarc == null -> "FAIL"
                hasRua || hasRuf -> "PASS"
                else -> "WARN"
            },
            detail = when {
                dmarc == null -> "DMARC missing"
                hasRua || hasRuf -> "Reporting configured (${dmarcReportTargets.joinToString(", ")})"
                else -> "No rua/ruf reporting addresses configured"
            },
        )
    )

    val dkimSelectors = listOf("default", "google", "selector1", "selector2", "k1", "dkim", "mail")
    val foundSelectors = dkimSelectors.filter { selector ->
        val r = resolver.lookup("$selector._domainkey.$domain", Type.TXT)
        r.records.any { it.data.contains("v=DKIM1") || it.data.contains("p=") }
    }
    checks.add(
        DomainHealthCheck(
            check = "Mail / DKIM (Common Selectors)",
            status = if (foundSelectors.isNotEmpty()) "PASS" else "WARN",
            detail = if (foundSelectors.isNotEmpty()) {
                "Found selectors: ${foundSelectors.joinToString(", ")}"
            } else {
                "No DKIM records found for common selectors"
            },
        )
    )

    val mtaStsRecord = resolver.lookup("_mta-sts.$domain", Type.TXT).records.find { it.data.startsWith("v=STSv1") }?.data
    checks.add(
        DomainHealthCheck(
            check = "Mail / MTA-STS DNS",
            status = if (mtaStsRecord != null) "PASS" else "INFO",
            detail = mtaStsRecord ?: "No _mta-sts TXT record",
        )
    )

    if (mtaStsRecord != null) {
        val policyHost = "mta-sts.$domain"
        try {
            val httpClient = HttpClient(timeout = Duration.ofSeconds(10))
            val policyResp = httpClient.get("https://$policyHost/.well-known/mta-sts.txt", includeBody = true)
            val policyBody = policyResp.body.orEmpty()
            val hasVersion = policyBody.contains("version: STSv1")
            val hasMode = Regex("""(?m)^mode:\s*(enforce|testing|none)\s*$""").containsMatchIn(policyBody)
            val hasMaxAge = Regex("""(?m)^max_age:\s*\d+\s*$""").containsMatchIn(policyBody)
            checks.add(
                DomainHealthCheck(
                    check = "Mail / MTA-STS Policy HTTPS",
                    status = if (policyResp.statusCode in 200..299 && hasVersion && hasMode && hasMaxAge) "PASS" else "WARN",
                    detail = "HTTP ${policyResp.statusCode}, version=${hasVersion}, mode=${hasMode}, max_age=${hasMaxAge}",
                )
            )
        } catch (e: Exception) {
            checks.add(
                DomainHealthCheck(
                    check = "Mail / MTA-STS Policy HTTPS",
                    status = "WARN",
                    detail = "Could not fetch policy: ${e.message}",
                )
            )
        }
    } else {
        checks.add(
            DomainHealthCheck(
                check = "Mail / MTA-STS Policy HTTPS",
                status = "INFO",
                detail = "Skipped because MTA-STS DNS record is missing",
            )
        )
    }

    val tlsRpt = resolver.lookup("_smtp._tls.$domain", Type.TXT).records.find { it.data.startsWith("v=TLSRPTv1") }?.data
    checks.add(
        DomainHealthCheck(
            check = "Mail / TLS-RPT Record",
            status = if (tlsRpt != null) "PASS" else "INFO",
            detail = tlsRpt ?: "No TLS-RPT record",
        )
    )

    checks.addAll(buildSecureMailConnectionChecks(resolver, mxHosts, mxIpMap, hasNullMx))

    val primaryMx = mxHosts.firstOrNull()
    val mxPtrIp = primaryMx?.let { mxIpMap[it] }
    if (mxPtrIp != null) {
        val ptr = resolver.reverseLookup(mxPtrIp)
        checks.add(
            DomainHealthCheck(
                check = "Mail / PTR For Primary MX",
                status = if (ptr.isSuccessful && ptr.records.isNotEmpty()) "PASS" else "WARN",
                detail = if (ptr.records.isNotEmpty()) "${mxPtrIp} → ${ptr.records.first().data}" else "No PTR for $mxPtrIp",
            )
        )
    } else {
        checks.add(
            DomainHealthCheck(
                check = "Mail / PTR For Primary MX",
                status = "INFO",
                detail = if (primaryMx == null) "Skipped because no MX host is available" else "Skipped because primary MX IP is unavailable",
            )
        )
    }

    checks.addAll(buildMailRpkiChecks(resolver = resolver, mxHosts = mxHosts, hasNullMx = hasNullMx))
    checks.addAll(buildMailDomainNameserverRpkiChecks(resolver = resolver, domainNsHosts = domainNsHosts, hasNullMx = hasNullMx))
    checks.addAll(buildMailMxNameserverRpkiChecks(resolver = resolver, mxHosts = mxHosts, hasNullMx = hasNullMx))

    // DNSSEC check
    val dnskeyResult = resolver.lookup(domain, Type.DNSKEY)
    val hasDnssec = dnskeyResult.isSuccessful && dnskeyResult.records.isNotEmpty()
    checks.add(
        DomainHealthCheck(
            check = "DNSSEC",
            status = if (hasDnssec) "PASS" else "INFO",
            detail = if (hasDnssec) "DNSSEC enabled" else "DNSSEC not configured",
        )
    )

    // Web checks
    try {
        val httpClient = HttpClient(timeout = Duration.ofSeconds(10))
        val httpsResult = httpClient.get("https://$domain", includeBody = false)
        checks.add(
            DomainHealthCheck(
                check = "HTTPS",
                status = if (httpsResult.statusCode in 200..399) "PASS" else "WARN",
                detail = "HTTP ${httpsResult.statusCode} (${httpsResult.responseTimeMs}ms)",
            )
        )
    } catch (e: Exception) {
        checks.add(
            DomainHealthCheck(
                check = "HTTPS",
                status = "FAIL",
                detail = "HTTPS not available: ${e.message}",
            )
        )
    }

    // CAA check
    val caaResult = resolver.lookup(domain, Type.CAA)
    checks.add(
        DomainHealthCheck(
            check = "CAA Record",
            status = if (caaResult.isSuccessful && caaResult.records.isNotEmpty()) "PASS" else "INFO",
            detail = if (caaResult.records.isNotEmpty()) "${caaResult.records.size} CAA record(s)" else "No CAA records",
        )
    )

    val passCount = checks.count { it.status == "PASS" }
    val warnCount = checks.count { it.status == "WARN" }
    val failCount = checks.count { it.status == "FAIL" }
    val overall = when {
        failCount > 0 -> "FAIL"
        warnCount > 2 -> "WARN"
        else -> "PASS"
    }

    val response = DomainHealthResponse(
        domain = domain,
        overallStatus = overall,
        summary = CheckSummary(
            pass = passCount,
            warn = warnCount,
            fail = failCount,
            info = checks.count { it.status == "INFO" },
            total = null,
        ),
        checks = checks,
    )
    ctx.jsonResult(response)
}

private val SECURE_MAIL_CHECK_NAMES = listOf(
    "Mail / STARTTLS Available",
    "Mail / TLS Version",
    "Mail / Ciphers (Algorithm Selections)",
    "Mail / Cipher Order",
    "Mail / Key Exchange Parameters",
    "Mail / Hash Function For Key Exchange",
    "Mail / TLS Compression",
    "Mail / Secure Renegotiation",
    "Mail / Client-Initiated Renegotiation",
    "Mail / 0-RTT",
    "Mail / Trust Chain Of Certificate",
    "Mail / Public Key Of Certificate",
    "Mail / Signature Of Certificate",
    "Mail / Domain Name On Certificate",
    "Mail / CAA For Mail Server",
    "Mail / DANE Existence",
    "Mail / DANE Validity",
    "Mail / DANE Rollover Scheme",
)

private data class MailRpkiTarget(
    val host: String,
    val ip: String,
)

private data class MailRpkiObservation(
    val host: String,
    val ip: String,
    val state: String,
    val reason: String?,
    val details: String? = null,
)

private fun buildMailRpkiChecks(
    resolver: DnsResolver,
    mxHosts: List<String>,
    hasNullMx: Boolean,
): List<DomainHealthCheck> {
    val checkedMxHosts = mxHosts.take(3)
    val notApplicableReason = when {
        hasNullMx -> "Not applicable: Null MX is configured for this domain"
        checkedMxHosts.isEmpty() -> "Not applicable: no MX host is available"
        else -> null
    }
    if (notApplicableReason != null) {
        return listOf(
            DomainHealthCheck(
                check = "Mail / RPKI ROA Coverage",
                status = "INFO",
                detail = notApplicableReason,
            ),
            DomainHealthCheck(
                check = "Mail / RPKI Route Announcement Validity",
                status = "INFO",
                detail = notApplicableReason,
            ),
        )
    }

    val targets = checkedMxHosts
        .flatMap { host ->
            resolveHostIps(resolver, host).map { ip -> MailRpkiTarget(host = host, ip = ip) }
        }
        .distinctBy { it.ip }
    return buildRpkiChecksFromTargets(
        resolver = resolver,
        coverageCheckName = "Mail / RPKI ROA Coverage",
        validityCheckName = "Mail / RPKI Route Announcement Validity",
        targets = targets,
        emptyDetail = "No MX IP addresses available for RPKI evaluation",
        scopeDetail = describeTargetScope(
            scopeLabel = "MX hosts only (first ${checkedMxHosts.size})",
            hosts = checkedMxHosts,
            targets = targets,
            additionalInfo = "authoritative DNS name server IPs are not included",
        ),
    )
}

private fun buildMailDomainNameserverRpkiChecks(
    resolver: DnsResolver,
    domainNsHosts: List<String>,
    hasNullMx: Boolean,
): List<DomainHealthCheck> {
    val checkedDomainNsHosts = domainNsHosts.take(6)
    val notApplicableReason = when {
        hasNullMx -> "Not applicable: Null MX is configured for this domain"
        checkedDomainNsHosts.isEmpty() -> "Not applicable: no authoritative domain name servers are available"
        else -> null
    }
    if (notApplicableReason != null) {
        return listOf(
            DomainHealthCheck(
                check = "Mail / RPKI ROA Coverage (Domain NS)",
                status = "INFO",
                detail = notApplicableReason,
            ),
            DomainHealthCheck(
                check = "Mail / RPKI Route Announcement Validity (Domain NS)",
                status = "INFO",
                detail = notApplicableReason,
            ),
        )
    }

    val targets = checkedDomainNsHosts
        .flatMap { host ->
            resolveHostIps(resolver, host).map { ip -> MailRpkiTarget(host = host, ip = ip) }
        }
        .distinctBy { it.ip }

    return buildRpkiChecksFromTargets(
        resolver = resolver,
        coverageCheckName = "Mail / RPKI ROA Coverage (Domain NS)",
        validityCheckName = "Mail / RPKI Route Announcement Validity (Domain NS)",
        targets = targets,
        emptyDetail = "No authoritative domain name server IP addresses available for RPKI evaluation",
        scopeDetail = describeTargetScope(
            scopeLabel = "authoritative domain name servers (first ${checkedDomainNsHosts.size})",
            hosts = checkedDomainNsHosts,
            targets = targets,
            additionalInfo = "MX host IPs are not included",
        ),
    )
}

private fun buildMailMxNameserverRpkiChecks(
    resolver: DnsResolver,
    mxHosts: List<String>,
    hasNullMx: Boolean,
): List<DomainHealthCheck> {
    val checkedMxHosts = mxHosts.take(3)
    val notApplicableReason = when {
        hasNullMx -> "Not applicable: Null MX is configured for this domain"
        checkedMxHosts.isEmpty() -> "Not applicable: no MX host is available"
        else -> null
    }
    if (notApplicableReason != null) {
        return listOf(
            DomainHealthCheck(
                check = "Mail / RPKI ROA Coverage (MX NS)",
                status = "INFO",
                detail = notApplicableReason,
            ),
            DomainHealthCheck(
                check = "Mail / RPKI Route Announcement Validity (MX NS)",
                status = "INFO",
                detail = notApplicableReason,
            ),
        )
    }

    val mxNsHosts = checkedMxHosts
        .flatMap { mxHost -> resolveAuthoritativeNsHostsForName(resolver, mxHost) }
        .distinct()
        .take(10)

    if (mxNsHosts.isEmpty()) {
        return listOf(
            DomainHealthCheck(
                check = "Mail / RPKI ROA Coverage (MX NS)",
                status = "INFO",
                detail = "No authoritative name servers discovered for checked MX hosts",
            ),
            DomainHealthCheck(
                check = "Mail / RPKI Route Announcement Validity (MX NS)",
                status = "INFO",
                detail = "No authoritative name servers discovered for checked MX hosts",
            ),
        )
    }

    val targets = mxNsHosts
        .flatMap { host ->
            resolveHostIps(resolver, host).map { ip -> MailRpkiTarget(host = host, ip = ip) }
        }
        .distinctBy { it.ip }

    return buildRpkiChecksFromTargets(
        resolver = resolver,
        coverageCheckName = "Mail / RPKI ROA Coverage (MX NS)",
        validityCheckName = "Mail / RPKI Route Announcement Validity (MX NS)",
        targets = targets,
        emptyDetail = "No MX name server IP addresses available for RPKI evaluation",
        scopeDetail = describeTargetScope(
            scopeLabel = "authoritative name servers of checked MX hosts",
            hosts = mxNsHosts,
            targets = targets,
            additionalInfo = "this excludes domain apex NS unless they are also MX name servers",
        ),
    )
}

private fun buildRpkiChecksFromTargets(
    resolver: DnsResolver,
    coverageCheckName: String,
    validityCheckName: String,
    targets: List<MailRpkiTarget>,
    emptyDetail: String,
    scopeDetail: String,
): List<DomainHealthCheck> {
    if (targets.isEmpty()) {
        return listOf(
            DomainHealthCheck(check = coverageCheckName, status = "INFO", detail = emptyDetail),
            DomainHealthCheck(check = validityCheckName, status = "INFO", detail = emptyDetail),
        )
    }

    val validator = RoutinatorRouteValidator()
    val observations = targets.map { target ->
        validateMailRouteOrigin(resolver = resolver, validator = validator, target = target)
    }

    val coveredCount = observations.count { it.state == "VALID" || it.state == "INVALID" }
    val uncoveredCount = observations.count { it.state == "NOT_FOUND" }
    val unknownCoverageCount = observations.size - coveredCount - uncoveredCount

    val roaStatus = when {
        uncoveredCount > 0 -> "WARN"
        coveredCount > 0 && unknownCoverageCount == 0 -> "PASS"
        coveredCount > 0 -> "WARN"
        else -> "INFO"
    }

    val roaProblemSamples = observations
        .filter { it.state == "NOT_FOUND" || it.state == "UNAVAILABLE" }
        .take(4)
        .joinToString("; ") { obs -> describeObservation(obs) }

    val roaDetail = buildString {
        append(
            "Checked ${observations.size} IP(s): " +
                "$coveredCount with ROA coverage, $uncoveredCount without ROA coverage, $unknownCoverageCount unknown",
        )
        if (roaProblemSamples.isNotBlank()) append(". Samples: $roaProblemSamples")
        append(". $scopeDetail")
    }

    val validCount = observations.count { it.state == "VALID" }
    val invalidCount = observations.count { it.state == "INVALID" }
    val notFoundCount = observations.count { it.state == "NOT_FOUND" }
    val unavailableCount = observations.count { it.state == "UNAVAILABLE" }

    val validityStatus = when {
        invalidCount > 0 -> "FAIL"
        notFoundCount > 0 -> "WARN"
        validCount > 0 && unavailableCount == 0 -> "PASS"
        validCount > 0 -> "WARN"
        else -> "INFO"
    }

    val validityProblemSamples = observations
        .filter { it.state != "VALID" }
        .take(4)
        .joinToString("; ") { obs -> describeObservation(obs) }

    val validityDetail = buildString {
        append(
            "Checked ${observations.size} IP(s): " +
                "$validCount valid, $invalidCount invalid, $notFoundCount not-found, $unavailableCount unavailable",
        )
        if (validityProblemSamples.isNotBlank()) append(". Samples: $validityProblemSamples")
        append(". $scopeDetail")
    }

    return listOf(
        DomainHealthCheck(check = coverageCheckName, status = roaStatus, detail = roaDetail),
        DomainHealthCheck(check = validityCheckName, status = validityStatus, detail = validityDetail),
    )
}

private fun describeObservation(observation: MailRpkiObservation): String {
    val detailSuffix =
        observation.details
            ?.takeIf { it.isNotBlank() }
            ?.let { " (${it.replace('\n', ' ').take(120)})" }
            ?: ""
    val reasonSuffix = observation.reason?.let { " [$it]" } ?: ""
    return "${observation.host} (${observation.ip}) ${observation.state.lowercase()}$reasonSuffix$detailSuffix"
}

private fun describeTargetScope(
    scopeLabel: String,
    hosts: List<String>,
    targets: List<MailRpkiTarget>,
    additionalInfo: String,
): String {
    val hostIpCounts = hosts.joinToString(", ") { host ->
        val count = targets.count { it.host == host }
        "$host: $count IP(s)"
    }
    return "Scope: $scopeLabel with A/AAAA IPs ($hostIpCounts); $additionalInfo."
}

private fun resolveAuthoritativeNsHostsForName(resolver: DnsResolver, fqdn: String): List<String> {
    val labels = fqdn.trim().trimEnd('.').split('.').filter { it.isNotBlank() }
    if (labels.isEmpty()) return emptyList()
    for (start in labels.indices) {
        val candidate = labels.drop(start).joinToString(".")
        val nsRecords = resolver.lookup(candidate, Type.NS).records
            .map { it.data.trim().trimEnd('.') }
            .filter { it.isNotBlank() }
            .distinct()
        if (nsRecords.isNotEmpty()) return nsRecords
    }
    return emptyList()
}

private fun resolveHostIps(resolver: DnsResolver, host: String): List<String> {
    val ipv4 = resolver.lookup(host, Type.A).records.map { it.data.trim() }
    val ipv6 = resolver.lookup(host, Type.AAAA).records.map { it.data.trim() }
    return (ipv4 + ipv6)
        .filter { isIpv4Literal(it) || isIpv6Literal(it) }
        .distinct()
}

private fun validateMailRouteOrigin(
    resolver: DnsResolver,
    validator: RoutinatorRouteValidator,
    target: MailRpkiTarget,
): MailRpkiObservation {
    val cymruQuery = buildCymruOriginQueryForIp(target.ip)
        ?: return MailRpkiObservation(
            host = target.host,
            ip = target.ip,
            state = "UNAVAILABLE",
            reason = "unsupported-ip-format",
            details = "Unsupported IP format",
        )

    val lookup = resolver.lookup(cymruQuery, Type.TXT)
    if (!lookup.isSuccessful || lookup.records.isEmpty()) {
        return MailRpkiObservation(
            host = target.host,
            ip = target.ip,
            state = "UNAVAILABLE",
            reason = "asn-lookup-failed",
            details = "Team Cymru lookup failed",
        )
    }

    val parsed = parseCymruOriginRecord(lookup.records.first().data)
        ?: return MailRpkiObservation(
            host = target.host,
            ip = target.ip,
            state = "UNAVAILABLE",
            reason = "asn-parse-failed",
            details = "Could not parse Team Cymru response",
        )

    val validation = validator.validate(prefix = parsed.second, asn = parsed.first)
    return MailRpkiObservation(
        host = target.host,
        ip = target.ip,
        state = validation.state,
        reason = validation.reason,
        details = validation.details,
    )
}

private fun parseCymruOriginRecord(txt: String): Pair<String, String>? {
    val parts = txt.split("|").map { it.trim() }
    if (parts.size < 2) return null
    val asn = parts[0]
    val prefix = parts[1]
    if (asn.isBlank() || prefix.isBlank()) return null
    return asn to prefix
}

private fun buildCymruOriginQueryForIp(ip: String): String? {
    if (isIpv4Literal(ip)) {
        val reversed = ip.split(".").reversed().joinToString(".")
        return "$reversed.origin.asn.cymru.com"
    }
    val ipv6 = ipv6AddressLiteral(ip) ?: return null
    val hex = ipv6.address.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    val reversedNibbles = hex.reversed().toCharArray().joinToString(".") { nibble -> nibble.toString() }
    return "$reversedNibbles.origin6.asn.cymru.com"
}

private fun ipv6AddressLiteral(value: String): Inet6Address? {
    if (!value.contains(':')) return null
    return try {
        val parsed = InetAddress.getByName(value.trim())
        parsed as? Inet6Address
    } catch (_: Exception) {
        null
    }
}

private fun isIpv6Literal(value: String): Boolean = ipv6AddressLiteral(value) != null

private val IPV4_LITERAL_REGEX =
    Regex("""^(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$""")

private fun isIpv4Literal(value: String): Boolean = IPV4_LITERAL_REGEX.matches(value)

private data class MailServerTlsProbe(
    val mxHost: String,
    val ip: String?,
    val banner: String?,
    val starttlsAdvertised: Boolean?,
    val starttlsResponse: String?,
    val tlsProtocol: String?,
    val cipherSuite: String?,
    val trustChainTrusted: Boolean?,
    val trustError: String?,
    val certificateChain: List<X509Certificate>,
    val probeError: String?,
)

private data class SmtpStartTlsAttempt(
    val banner: String?,
    val starttlsAdvertised: Boolean,
    val starttlsResponse: String?,
    val tlsProtocol: String?,
    val cipherSuite: String?,
    val certificateChain: List<X509Certificate>,
    val error: String?,
)

private data class ParsedTlsaRecord(
    val usage: Int,
    val selector: Int,
    val matching: Int,
    val associationData: ByteArray,
)

private fun buildSecureMailConnectionChecks(
    resolver: DnsResolver,
    mxHosts: List<String>,
    mxIpMap: Map<String, String>,
    hasNullMx: Boolean,
): List<DomainHealthCheck> {
    if (hasNullMx) {
        return secureMailChecksNotApplicable("Not applicable: Null MX is configured for this domain")
    }
    if (mxHosts.isEmpty()) {
        return secureMailChecksNotApplicable("Not applicable: no MX host is available")
    }

    val checkedMxHosts = mxHosts.take(3)
    val probes = checkedMxHosts.map { host ->
        val ip = mxIpMap[host]
        if (ip == null) {
            MailServerTlsProbe(
                mxHost = host,
                ip = null,
                banner = null,
                starttlsAdvertised = null,
                starttlsResponse = null,
                tlsProtocol = null,
                cipherSuite = null,
                trustChainTrusted = null,
                trustError = null,
                certificateChain = emptyList(),
                probeError = "No A/AAAA record",
            )
        } else {
            probeSmtpStartTls(host, ip, timeoutMillis = 5000)
        }
    }
    val probeByHost = probes.associateBy { it.mxHost }
    val checks = mutableListOf<DomainHealthCheck>()

    val starttlsFailures = probes.filter { it.starttlsAdvertised != true }.map { it.mxHost }
    checks.add(
        DomainHealthCheck(
            check = "Mail / STARTTLS Available",
            status = if (starttlsFailures.isEmpty()) "PASS" else "FAIL",
            detail = if (starttlsFailures.isEmpty()) {
                "STARTTLS advertised on all checked MX hosts: ${checkedMxHosts.joinToString(", ")}"
            } else {
                "STARTTLS missing or probe failed on: ${starttlsFailures.joinToString(", ")}"
            },
        )
    )

    val tlsVersionStatuses = probes.map { classifyTlsVersion(it.tlsProtocol) }
    checks.add(
        DomainHealthCheck(
            check = "Mail / TLS Version",
            status = worstStatus(tlsVersionStatuses),
            detail = probes.joinToString("; ") { probe ->
                val protocol = probe.tlsProtocol ?: "none"
                "${probe.mxHost}: $protocol"
            },
        )
    )

    val cipherAssessments = probes.map { classifyCipherSuite(it.cipherSuite) }
    checks.add(
        DomainHealthCheck(
            check = "Mail / Ciphers (Algorithm Selections)",
            status = worstStatus(cipherAssessments.map { it.first }),
            detail = probes.joinToString("; ") { probe ->
                val cipher = probe.cipherSuite ?: "none"
                "${probe.mxHost}: $cipher"
            },
        )
    )

    checks.add(
        DomainHealthCheck(
            check = "Mail / Cipher Order",
            status = when (worstStatus(cipherAssessments.map { it.first })) {
                "FAIL" -> "FAIL"
                "WARN" -> "WARN"
                "PASS" -> "PASS"
                else -> "INFO"
            },
            detail = when {
                probes.any { it.cipherSuite == null } ->
                    "Insufficient TLS data to evaluate ordering on all checked MX hosts"
                else ->
                    "Best-effort check based on negotiated cipher only; full server-order validation requires multi-handshake probing"
            },
        )
    )

    val keyExchangeStatuses = probes.map { classifyKeyExchange(it.tlsProtocol, it.cipherSuite) }
    checks.add(
        DomainHealthCheck(
            check = "Mail / Key Exchange Parameters",
            status = worstStatus(keyExchangeStatuses),
            detail = probes.joinToString("; ") { probe ->
                "${probe.mxHost}: ${describeKeyExchange(probe.tlsProtocol, probe.cipherSuite)}"
            },
        )
    )

    val keyExchangeHashStatuses = probes.map { classifyKeyExchangeHash(it.cipherSuite) }
    checks.add(
        DomainHealthCheck(
            check = "Mail / Hash Function For Key Exchange",
            status = worstStatus(keyExchangeHashStatuses),
            detail = probes.joinToString("; ") { probe ->
                "${probe.mxHost}: ${describeKeyExchangeHash(probe.cipherSuite)}"
            },
        )
    )

    val negotiatedProtocols = probes.mapNotNull { it.tlsProtocol?.uppercase() }
    checks.add(
        DomainHealthCheck(
            check = "Mail / TLS Compression",
            status = when {
                negotiatedProtocols.isEmpty() -> "INFO"
                negotiatedProtocols.all { it == "TLSV1.3" } -> "PASS"
                else -> "INFO"
            },
            detail = when {
                negotiatedProtocols.isEmpty() -> "No TLS session established to evaluate compression"
                negotiatedProtocols.all { it == "TLSV1.3" } -> "TLS 1.3 disables TLS-level compression by design"
                else -> "Compression state is not exposed by the JVM SMTP probe for TLS 1.2 and older"
            },
        )
    )

    checks.add(
        DomainHealthCheck(
            check = "Mail / Secure Renegotiation",
            status = when {
                negotiatedProtocols.isEmpty() -> "INFO"
                negotiatedProtocols.all { it == "TLSV1.3" } -> "PASS"
                else -> "INFO"
            },
            detail = when {
                negotiatedProtocols.isEmpty() -> "No TLS session established to evaluate renegotiation behavior"
                negotiatedProtocols.all { it == "TLSV1.3" } -> "TLS 1.3 removes renegotiation"
                else -> "Secure renegotiation signaling requires OpenSSL-style probing for TLS 1.2 and older"
            },
        )
    )

    checks.add(
        DomainHealthCheck(
            check = "Mail / Client-Initiated Renegotiation",
            status = when {
                negotiatedProtocols.isEmpty() -> "INFO"
                negotiatedProtocols.all { it == "TLSV1.3" } -> "PASS"
                else -> "INFO"
            },
            detail = when {
                negotiatedProtocols.isEmpty() -> "No TLS session established to evaluate client-initiated renegotiation"
                negotiatedProtocols.all { it == "TLSV1.3" } -> "TLS 1.3 does not support renegotiation"
                else -> "Client-initiated renegotiation checks require explicit renegotiation probes"
            },
        )
    )

    checks.add(
        DomainHealthCheck(
            check = "Mail / 0-RTT",
            status = when {
                negotiatedProtocols.isEmpty() -> "INFO"
                negotiatedProtocols.none { it == "TLSV1.3" } -> "PASS"
                else -> "INFO"
            },
            detail = when {
                negotiatedProtocols.isEmpty() -> "No TLS session established to evaluate 0-RTT"
                negotiatedProtocols.none { it == "TLSV1.3" } -> "No TLS 1.3 observed; 0-RTT is not applicable"
                else -> "0-RTT requires a session-resumption early-data probe"
            },
        )
    )

    val trustApplicable = probes.filter { it.starttlsAdvertised == true }
    val trustFailures = trustApplicable.filter { it.trustChainTrusted == false }
    checks.add(
        DomainHealthCheck(
            check = "Mail / Trust Chain Of Certificate",
            status = when {
                trustApplicable.isEmpty() -> "INFO"
                trustFailures.isNotEmpty() -> "FAIL"
                trustApplicable.any { it.trustChainTrusted != true } -> "WARN"
                else -> "PASS"
            },
            detail = when {
                trustApplicable.isEmpty() -> "No STARTTLS-capable MX host available for certificate validation"
                trustFailures.isNotEmpty() -> trustFailures.joinToString("; ") { "${it.mxHost}: ${it.trustError ?: "untrusted chain"}" }
                else -> "Certificate chain trusted by default JVM trust store on all checked MX hosts"
            },
        )
    )

    val publicKeyStatuses = mutableListOf<String>()
    val publicKeyDetail = mutableListOf<String>()
    for (probe in trustApplicable) {
        val leaf = probe.certificateChain.firstOrNull()
        if (leaf == null) {
            publicKeyStatuses += "FAIL"
            publicKeyDetail += "${probe.mxHost}: certificate unavailable"
            continue
        }
        val assessment = assessCertificatePublicKey(leaf)
        publicKeyStatuses += assessment.first
        publicKeyDetail += "${probe.mxHost}: ${assessment.second}"
    }
    checks.add(
        DomainHealthCheck(
            check = "Mail / Public Key Of Certificate",
            status = when {
                trustApplicable.isEmpty() -> "INFO"
                publicKeyStatuses.isEmpty() -> "INFO"
                else -> worstStatus(publicKeyStatuses)
            },
            detail = if (publicKeyDetail.isEmpty()) {
                "No certificate data available"
            } else {
                publicKeyDetail.joinToString("; ")
            },
        )
    )

    val signatureStatuses = mutableListOf<String>()
    val signatureDetails = mutableListOf<String>()
    for (probe in trustApplicable) {
        val leaf = probe.certificateChain.firstOrNull()
        if (leaf == null) {
            signatureStatuses += "FAIL"
            signatureDetails += "${probe.mxHost}: certificate unavailable"
            continue
        }
        val assessment = assessCertificateSignature(leaf)
        signatureStatuses += assessment.first
        signatureDetails += "${probe.mxHost}: ${assessment.second}"
    }
    checks.add(
        DomainHealthCheck(
            check = "Mail / Signature Of Certificate",
            status = when {
                trustApplicable.isEmpty() -> "INFO"
                signatureStatuses.isEmpty() -> "INFO"
                else -> worstStatus(signatureStatuses)
            },
            detail = if (signatureDetails.isEmpty()) {
                "No certificate data available"
            } else {
                signatureDetails.joinToString("; ")
            },
        )
    )

    val domainMatchStatuses = mutableListOf<String>()
    val domainMatchDetails = mutableListOf<String>()
    for (probe in trustApplicable) {
        val leaf = probe.certificateChain.firstOrNull()
        if (leaf == null) {
            domainMatchStatuses += "FAIL"
            domainMatchDetails += "${probe.mxHost}: certificate unavailable"
            continue
        }
        val matches = certificateMatchesHost(leaf, probe.mxHost)
        domainMatchStatuses += if (matches) "PASS" else "WARN"
        domainMatchDetails += if (matches) {
            "${probe.mxHost}: certificate matches MX host"
        } else {
            "${probe.mxHost}: MX host not found in SAN/CN"
        }
    }
    checks.add(
        DomainHealthCheck(
            check = "Mail / Domain Name On Certificate",
            status = when {
                trustApplicable.isEmpty() -> "INFO"
                domainMatchStatuses.isEmpty() -> "INFO"
                else -> worstStatus(domainMatchStatuses)
            },
            detail = if (domainMatchDetails.isEmpty()) {
                "No certificate data available"
            } else {
                domainMatchDetails.joinToString("; ")
            },
        )
    )

    val missingCaaHosts = checkedMxHosts.filter { host ->
        resolver.lookup(host, Type.CAA).records.isEmpty()
    }
    checks.add(
        DomainHealthCheck(
            check = "Mail / CAA For Mail Server",
            status = if (missingCaaHosts.isEmpty()) "PASS" else "WARN",
            detail = if (missingCaaHosts.isEmpty()) {
                "CAA records found on all checked MX hosts"
            } else {
                "CAA missing on: ${missingCaaHosts.joinToString(", ")}"
            },
        )
    )

    val rawTlsaByHost = checkedMxHosts.associateWith { host ->
        resolver.lookup("_25._tcp.$host", Type.TLSA).records.map { it.data }
    }
    val parsedTlsaByHost = rawTlsaByHost.mapValues { (_, records) -> records.mapNotNull { parseTlsaRecord(it) } }

    val hostsWithoutDane = checkedMxHosts.filter { rawTlsaByHost[it].isNullOrEmpty() }
    val daneExistenceStatus = if (hostsWithoutDane.isEmpty()) "PASS" else "FAIL"
    checks.add(
        DomainHealthCheck(
            check = "Mail / DANE Existence",
            status = daneExistenceStatus,
            detail = if (hostsWithoutDane.isEmpty()) {
                "TLSA records found for all checked MX hosts"
            } else {
                "Missing TLSA at _25._tcp for: ${hostsWithoutDane.joinToString(", ")}"
            },
        )
    )

    if (daneExistenceStatus != "PASS") {
        checks.add(
            DomainHealthCheck(
                check = "Mail / DANE Validity",
                status = "INFO",
                detail = "Skipped because DANE TLSA records are missing on one or more MX hosts",
            )
        )
        checks.add(
            DomainHealthCheck(
                check = "Mail / DANE Rollover Scheme",
                status = "INFO",
                detail = "Skipped because DANE TLSA records are missing on one or more MX hosts",
            )
        )
    } else {
        val daneValidityStatuses = mutableListOf<String>()
        val daneValidityDetails = mutableListOf<String>()
        for (host in checkedMxHosts) {
            val raw = rawTlsaByHost[host].orEmpty()
            val parsed = parsedTlsaByHost[host].orEmpty()
            if (raw.size != parsed.size || parsed.isEmpty()) {
                daneValidityStatuses += "FAIL"
                daneValidityDetails += "$host: malformed TLSA record detected"
                continue
            }
            val leaf = probeByHost[host]?.certificateChain?.firstOrNull()
            if (leaf == null) {
                daneValidityStatuses += "WARN"
                daneValidityDetails += "$host: TLSA found, but certificate probe data is unavailable"
                continue
            }
            val hasMatchingRecord = parsed.any { tlsaMatchesCertificate(it, leaf) }
            daneValidityStatuses += if (hasMatchingRecord) "PASS" else "WARN"
            daneValidityDetails += if (hasMatchingRecord) {
                "$host: TLSA matches presented certificate"
            } else {
                "$host: TLSA does not match presented certificate"
            }
        }
        checks.add(
            DomainHealthCheck(
                check = "Mail / DANE Validity",
                status = worstStatus(daneValidityStatuses),
                detail = daneValidityDetails.joinToString("; "),
            )
        )

        val noRolloverHosts = checkedMxHosts.filter { host ->
            val records = parsedTlsaByHost[host].orEmpty()
            !hasRecommendedDaneRollover(records)
        }
        checks.add(
            DomainHealthCheck(
                check = "Mail / DANE Rollover Scheme",
                status = if (noRolloverHosts.isEmpty()) "PASS" else "WARN",
                detail = if (noRolloverHosts.isEmpty()) {
                    "Rollover-ready TLSA scheme detected on all checked MX hosts"
                } else {
                    "At least two complementary TLSA records are recommended on: ${noRolloverHosts.joinToString(", ")}"
                },
            )
        )
    }

    return checks
}

private fun secureMailChecksNotApplicable(reason: String): List<DomainHealthCheck> {
    return SECURE_MAIL_CHECK_NAMES.map { check ->
        DomainHealthCheck(check = check, status = "INFO", detail = reason)
    }
}

private fun probeSmtpStartTls(mxHost: String, ip: String, timeoutMillis: Int): MailServerTlsProbe {
    val strict = runSmtpStartTlsAttempt(mxHost, ip, timeoutMillis, trustAll = false)
    var tlsProtocol = strict.tlsProtocol
    var cipherSuite = strict.cipherSuite
    var certChain = strict.certificateChain
    var trustChainTrusted: Boolean? = null
    var trustError: String? = null

    if (strict.tlsProtocol != null) {
        trustChainTrusted = true
    } else if (strict.starttlsAdvertised && strict.starttlsResponse?.startsWith("220") == true) {
        trustChainTrusted = false
        trustError = strict.error ?: "TLS handshake failed"
        val permissive = runSmtpStartTlsAttempt(mxHost, ip, timeoutMillis, trustAll = true)
        if (permissive.tlsProtocol != null) {
            tlsProtocol = permissive.tlsProtocol
            cipherSuite = permissive.cipherSuite
            certChain = permissive.certificateChain
        }
    }

    return MailServerTlsProbe(
        mxHost = mxHost,
        ip = ip,
        banner = strict.banner,
        starttlsAdvertised = strict.starttlsAdvertised,
        starttlsResponse = strict.starttlsResponse,
        tlsProtocol = tlsProtocol,
        cipherSuite = cipherSuite,
        trustChainTrusted = trustChainTrusted,
        trustError = trustError,
        certificateChain = certChain,
        probeError = strict.error,
    )
}

private fun runSmtpStartTlsAttempt(mxHost: String, ip: String, timeoutMillis: Int, trustAll: Boolean): SmtpStartTlsAttempt {
    var banner: String? = null
    var starttlsAdvertised = false
    var starttlsResponse: String? = null
    try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, 25), timeoutMillis)
            socket.soTimeout = timeoutMillis
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

            banner = reader.readLine().orEmpty()
            writer.println("EHLO nortools.check")
            val ehloLines = mutableListOf<String>()
            var line = reader.readLine()
            while (line != null && (line.startsWith("250-") || line.startsWith("250 "))) {
                ehloLines += line
                if (line.startsWith("250 ")) break
                line = reader.readLine()
            }

            starttlsAdvertised = ehloLines.any { it.uppercase().contains("STARTTLS") }
            if (!starttlsAdvertised) {
                writer.println("QUIT")
                return SmtpStartTlsAttempt(
                    banner = banner,
                    starttlsAdvertised = false,
                    starttlsResponse = null,
                    tlsProtocol = null,
                    cipherSuite = null,
                    certificateChain = emptyList(),
                    error = null,
                )
            }

            writer.println("STARTTLS")
            starttlsResponse = reader.readLine().orEmpty()
            if (!starttlsResponse.startsWith("220")) {
                writer.println("QUIT")
                return SmtpStartTlsAttempt(
                    banner = banner,
                    starttlsAdvertised = true,
                    starttlsResponse = starttlsResponse,
                    tlsProtocol = null,
                    cipherSuite = null,
                    certificateChain = emptyList(),
                    error = "STARTTLS rejected: $starttlsResponse",
                )
            }

            val sslFactory = if (trustAll) {
                TRUST_ALL_SSL_CONTEXT.socketFactory
            } else {
                SSLSocketFactory.getDefault() as SSLSocketFactory
            }

            val tlsSocket = sslFactory.createSocket(socket, mxHost, 25, true) as SSLSocket
            tlsSocket.soTimeout = timeoutMillis
            tlsSocket.useClientMode = true
            tlsSocket.startHandshake()
            val session = tlsSocket.session
            val certs = runCatching { session.peerCertificates.filterIsInstance<X509Certificate>() }.getOrDefault(emptyList())

            return SmtpStartTlsAttempt(
                banner = banner,
                starttlsAdvertised = true,
                starttlsResponse = starttlsResponse,
                tlsProtocol = session.protocol,
                cipherSuite = session.cipherSuite,
                certificateChain = certs,
                error = null,
            )
        }
    } catch (e: Exception) {
        return SmtpStartTlsAttempt(
            banner = banner,
            starttlsAdvertised = starttlsAdvertised,
            starttlsResponse = starttlsResponse,
            tlsProtocol = null,
            cipherSuite = null,
            certificateChain = emptyList(),
            error = e.message ?: "SMTP STARTTLS probe failed",
        )
    }
}

private val TRUST_ALL_SSL_CONTEXT: SSLContext by lazy {
    SSLContext.getInstance("TLS").apply {
        init(
            null,
            arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
            ),
            SecureRandom(),
        )
    }
}

private fun classifyTlsVersion(protocol: String?): String {
    val p = protocol?.uppercase() ?: return "FAIL"
    return when {
        p == "TLSV1.3" || p == "TLSV1.2" -> "PASS"
        p == "TLSV1.1" || p == "TLSV1" -> "WARN"
        p.startsWith("SSL") -> "FAIL"
        else -> "WARN"
    }
}

private fun classifyCipherSuite(cipherSuite: String?): Pair<String, String> {
    val c = cipherSuite?.uppercase() ?: return "FAIL" to "No cipher negotiated"
    if (listOf("_NULL_", "_EXPORT_", "_RC4_", "_MD5_", "_PSK_", "_SRP_", "_DES_", "_3DES_").any { c.contains(it) }) {
        return "FAIL" to "Insufficient cipher"
    }
    if (c.contains("CHACHA20") || c.contains("POLY1305") || c.contains("_GCM_")) {
        return "PASS" to "Good/modern cipher"
    }
    if (c.contains("_CBC_")) {
        return "WARN" to "CBC cipher (legacy/phase-out)"
    }
    return "WARN" to "Cipher quality uncertain"
}

private fun classifyKeyExchange(protocol: String?, cipherSuite: String?): String {
    val p = protocol?.uppercase()
    if (p == "TLSV1.3") return "PASS"
    val c = cipherSuite?.uppercase() ?: return "FAIL"
    return when {
        c.contains("_ECDHE_") -> "PASS"
        c.contains("_DHE_") -> "WARN"
        c.contains("_RSA_") -> "FAIL"
        else -> "WARN"
    }
}

private fun describeKeyExchange(protocol: String?, cipherSuite: String?): String {
    val p = protocol?.uppercase()
    if (p == "TLSV1.3") return "TLS 1.3 ephemeral key exchange"
    val c = cipherSuite ?: "none"
    return when (classifyKeyExchange(protocol, cipherSuite)) {
        "PASS" -> "$c (ephemeral)"
        "WARN" -> "$c (legacy/unknown parameters)"
        else -> "$c (insufficient)"
    }
}

private fun classifyKeyExchangeHash(cipherSuite: String?): String {
    val c = cipherSuite?.uppercase() ?: return "FAIL"
    return when {
        c.contains("_MD5") -> "FAIL"
        c.contains("_SHA512") || c.contains("_SHA384") || c.contains("_SHA256") || c.contains("POLY1305") -> "PASS"
        c.contains("_SHA") -> "WARN"
        else -> "INFO"
    }
}

private fun describeKeyExchangeHash(cipherSuite: String?): String {
    val c = cipherSuite?.uppercase() ?: return "No negotiated cipher"
    return when (classifyKeyExchangeHash(c)) {
        "PASS" -> "$c (secure hash)"
        "WARN" -> "$c (legacy hash)"
        "FAIL" -> "$c (insufficient hash)"
        else -> "$c (unknown hash profile)"
    }
}

private fun assessCertificatePublicKey(cert: X509Certificate): Pair<String, String> {
    val key = cert.publicKey
    val alg = key.algorithm.uppercase()
    return when {
        alg == "RSA" -> {
            val bits = runCatching {
                (key as java.security.interfaces.RSAPublicKey).modulus.bitLength()
            }.getOrDefault(0)
            when {
                bits >= 2048 -> "PASS" to "RSA ${bits} bit"
                bits > 0 -> "FAIL" to "RSA ${bits} bit (too small)"
                else -> "WARN" to "RSA key size unknown"
            }
        }
        alg == "EC" || alg == "ECDSA" -> {
            val bits = runCatching {
                (key as java.security.interfaces.ECPublicKey).params.curve.field.fieldSize
            }.getOrDefault(0)
            when {
                bits >= 256 -> "PASS" to "EC ${bits} bit"
                bits >= 224 -> "WARN" to "EC ${bits} bit (phase-out)"
                bits > 0 -> "FAIL" to "EC ${bits} bit (too small)"
                else -> "WARN" to "EC key size unknown"
            }
        }
        alg == "ED25519" || alg == "ED448" -> "PASS" to alg
        else -> "WARN" to "$alg (unsupported key profile check)"
    }
}

private fun assessCertificateSignature(cert: X509Certificate): Pair<String, String> {
    val signatureAlgorithm = cert.sigAlgName.uppercase()
    return when {
        signatureAlgorithm.contains("MD5") || signatureAlgorithm.contains("SHA1") -> "FAIL" to cert.sigAlgName
        signatureAlgorithm.contains("SHA256") ||
            signatureAlgorithm.contains("SHA384") ||
            signatureAlgorithm.contains("SHA512") ||
            signatureAlgorithm.contains("ED25519") ||
            signatureAlgorithm.contains("ED448") -> "PASS" to cert.sigAlgName
        else -> "WARN" to cert.sigAlgName
    }
}

private fun certificateMatchesHost(cert: X509Certificate, host: String): Boolean {
    val hostLower = host.lowercase()
    val sans = cert.subjectAlternativeNames.orEmpty()
        .mapNotNull { entry ->
            if (entry == null) return@mapNotNull null
            if (entry.size < 2) return@mapNotNull null
            val type = entry[0] as? Int ?: return@mapNotNull null
            if (type != 2) return@mapNotNull null
            entry[1]?.toString()?.lowercase()
        }
    val names = if (sans.isNotEmpty()) {
        sans
    } else {
        listOfNotNull(dnAttribute(cert.subjectX500Principal.name, "CN")?.lowercase())
    }
    return names.any { pattern -> hostMatchesPattern(hostLower, pattern) }
}

private fun hostMatchesPattern(host: String, pattern: String): Boolean {
    if (pattern == host) return true
    if (!pattern.startsWith("*.")) return false
    val suffix = pattern.removePrefix("*")
    if (!host.endsWith(suffix)) return false
    val hostLabels = host.split('.').size
    val suffixLabels = suffix.removePrefix(".").split('.').size
    return hostLabels == suffixLabels + 1
}

private fun parseTlsaRecord(raw: String): ParsedTlsaRecord? {
    val parts = raw.trim().split(Regex("\\s+"), limit = 4)
    if (parts.size < 4) return null
    val usage = parts[0].toIntOrNull() ?: return null
    val selector = parts[1].toIntOrNull() ?: return null
    val matching = parts[2].toIntOrNull() ?: return null
    val hex = parts[3].replace(Regex("[^0-9A-Fa-f]"), "")
    if (hex.isEmpty() || hex.length % 2 != 0) return null
    return ParsedTlsaRecord(
        usage = usage,
        selector = selector,
        matching = matching,
        associationData = hexToBytes(hex),
    )
}

private fun hasRecommendedDaneRollover(records: List<ParsedTlsaRecord>): Boolean {
    if (records.size < 2) return false
    val usage23 = records.filter { it.usage == 2 || it.usage == 3 }
    return usage23.size >= 2 && usage23.any { it.usage == 3 }
}

private fun tlsaMatchesCertificate(record: ParsedTlsaRecord, certificate: X509Certificate): Boolean {
    val source = when (record.selector) {
        0 -> certificate.encoded
        1 -> certificate.publicKey.encoded
        else -> return false
    }
    val candidate = when (record.matching) {
        0 -> source
        1 -> MessageDigest.getInstance("SHA-256").digest(source)
        2 -> MessageDigest.getInstance("SHA-512").digest(source)
        else -> return false
    }
    return candidate.contentEquals(record.associationData)
}

private fun hexToBytes(hex: String): ByteArray {
    val normalized = hex.uppercase()
    val out = ByteArray(normalized.length / 2)
    for (i in out.indices) {
        val from = i * 2
        out[i] = normalized.substring(from, from + 2).toInt(16).toByte()
    }
    return out
}

private fun worstStatus(statuses: List<String>, defaultStatus: String = "INFO"): String {
    if (statuses.isEmpty()) return defaultStatus
    val rank = mapOf("FAIL" to 4, "WARN" to 3, "INFO" to 2, "PASS" to 1)
    return statuses.maxByOrNull { rank[it] ?: 0 } ?: defaultStatus
}

private fun dnAttribute(dn: String, attribute: String): String? {
    return try {
        LdapName(dn).rdns.firstOrNull { it.type.equals(attribute, ignoreCase = true) }?.value?.toString()
    } catch (_: Exception) {
        null
    }
}

// ─── Models ─────────────────────────────────────────────────────────────────

data class DomainHealthCheck(
    val check: String,
    val status: String,
    val detail: String,
)

data class DomainHealthResponse(
    val domain: String,
    val overallStatus: String,
    val summary: CheckSummary,
    val checks: List<DomainHealthCheck>,
)
