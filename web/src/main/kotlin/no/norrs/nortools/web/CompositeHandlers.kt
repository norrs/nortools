package no.norrs.nortools.web

import io.javalin.http.Context
import no.norrs.nortools.lib.dns.DnsResolver
import no.norrs.nortools.lib.network.HttpClient
import org.xbill.DNS.Type
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration

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

    val primaryMx = mxHosts.firstOrNull()
    if (primaryMx != null) {
        val primaryIp = mxIpMap[primaryMx]
        if (primaryIp != null) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(primaryIp, 25), 5000)
                    socket.soTimeout = 5000
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    reader.readLine() // SMTP banner
                    writer.println("EHLO nortools.check")
                    val ehloLines = mutableListOf<String>()
                    var line = reader.readLine()
                    while (line != null && (line.startsWith("250-") || line.startsWith("250 "))) {
                        ehloLines.add(line)
                        if (line.startsWith("250 ")) break
                        line = reader.readLine()
                    }
                    val starttlsAdvertised = ehloLines.any { it.uppercase().contains("STARTTLS") }
                    checks.add(
                        DomainHealthCheck(
                            check = "Mail / STARTTLS Advertised",
                            status = if (starttlsAdvertised) "PASS" else "WARN",
                            detail = if (starttlsAdvertised) {
                                "Primary MX advertises STARTTLS"
                            } else {
                                "Primary MX does not advertise STARTTLS"
                            },
                        )
                    )

                    if (starttlsAdvertised) {
                        writer.println("STARTTLS")
                        val starttlsResponse = reader.readLine().orEmpty()
                        checks.add(
                            DomainHealthCheck(
                                check = "Mail / STARTTLS Handshake Command",
                                status = if (starttlsResponse.startsWith("220")) "PASS" else "FAIL",
                                detail = starttlsResponse,
                            )
                        )
                    } else {
                        checks.add(
                            DomainHealthCheck(
                                check = "Mail / STARTTLS Handshake Command",
                                status = "INFO",
                                detail = "Skipped because STARTTLS is not advertised",
                            )
                        )
                    }
                    writer.println("QUIT")
                }
            } catch (e: Exception) {
                checks.add(
                    DomainHealthCheck(
                        check = "Mail / STARTTLS Advertised",
                        status = "FAIL",
                        detail = "SMTP probe failed: ${e.message}",
                    )
                )
                checks.add(
                    DomainHealthCheck(
                        check = "Mail / STARTTLS Handshake Command",
                        status = "INFO",
                        detail = "Skipped because SMTP probe failed",
                    )
                )
            }
        } else {
            checks.add(
                DomainHealthCheck(
                    check = "Mail / STARTTLS Advertised",
                    status = "INFO",
                    detail = "Primary MX has no resolved IP address",
                )
            )
            checks.add(
                DomainHealthCheck(
                    check = "Mail / STARTTLS Handshake Command",
                    status = "INFO",
                    detail = "Skipped because primary MX has no resolved IP",
                )
            )
        }

        val tlsaRecords = resolver.lookup("_25._tcp.$primaryMx", Type.TLSA)
        checks.add(
            DomainHealthCheck(
                check = "Mail / DANE TLSA Record",
                status = if (tlsaRecords.isSuccessful && tlsaRecords.records.isNotEmpty()) "PASS" else "INFO",
                detail = if (tlsaRecords.records.isNotEmpty()) {
                    "${tlsaRecords.records.size} TLSA record(s) found for _25._tcp.$primaryMx"
                } else {
                    "No TLSA record for _25._tcp.$primaryMx"
                },
            )
        )

        val mxCaa = resolver.lookup(primaryMx, Type.CAA)
        checks.add(
            DomainHealthCheck(
                check = "Mail / CAA Record On MX Host",
                status = if (mxCaa.isSuccessful && mxCaa.records.isNotEmpty()) "PASS" else "INFO",
                detail = if (mxCaa.records.isNotEmpty()) {
                    "${mxCaa.records.size} CAA record(s) on $primaryMx"
                } else {
                    "No CAA records on $primaryMx"
                },
            )
        )

        val mxPtrIp = mxIpMap[primaryMx]
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
                    detail = "Skipped because primary MX IP is unavailable",
                )
            )
        }
    } else {
        checks.add(
            DomainHealthCheck(
                check = "Mail / STARTTLS Advertised",
                status = "INFO",
                detail = "Skipped because no MX host is available",
            )
        )
        checks.add(
            DomainHealthCheck(
                check = "Mail / STARTTLS Handshake Command",
                status = "INFO",
                detail = "Skipped because no MX host is available",
            )
        )
        checks.add(
            DomainHealthCheck(
                check = "Mail / DANE TLSA Record",
                status = "INFO",
                detail = "Skipped because no MX host is available",
            )
        )
        checks.add(
            DomainHealthCheck(
                check = "Mail / CAA Record On MX Host",
                status = "INFO",
                detail = "Skipped because no MX host is available",
            )
        )
        checks.add(
            DomainHealthCheck(
                check = "Mail / PTR For Primary MX",
                status = "INFO",
                detail = "Skipped because no MX host is available",
            )
        )
    }

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
