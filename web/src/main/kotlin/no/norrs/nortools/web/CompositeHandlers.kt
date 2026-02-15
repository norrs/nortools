package no.norrs.nortools.web

import io.javalin.http.Context
import no.norrs.nortools.lib.dns.DnsResolver
import no.norrs.nortools.lib.network.HttpClient
import org.xbill.DNS.Type
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

    // Email checks
    val mxResult = resolver.lookup(domain, Type.MX)
    checks.add(
        DomainHealthCheck(
            check = "MX Records",
            status = if (mxResult.isSuccessful && mxResult.records.isNotEmpty()) "PASS" else "WARN",
            detail = "${mxResult.records.size} MX record(s)",
        )
    )

    val spfResult = resolver.lookup(domain, Type.TXT)
    val hasSpf = spfResult.records.any { it.data.startsWith("v=spf1") }
    checks.add(
        DomainHealthCheck(
            check = "SPF Record",
            status = if (hasSpf) "PASS" else "WARN",
            detail = if (hasSpf) "SPF record found" else "No SPF record",
        )
    )

    val dmarcResult = resolver.lookup("_dmarc.$domain", Type.TXT)
    val hasDmarc = dmarcResult.records.any { it.data.startsWith("v=DMARC1") }
    checks.add(
        DomainHealthCheck(
            check = "DMARC Record",
            status = if (hasDmarc) "PASS" else "WARN",
            detail = if (hasDmarc) "DMARC record found" else "No DMARC record",
        )
    )

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
