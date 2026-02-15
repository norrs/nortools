package no.norrs.nortools.web

import io.javalin.http.Context
import no.norrs.nortools.lib.dns.DnsResolver
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.NSRecord
import org.xbill.DNS.Rcode
import org.xbill.DNS.Record
import org.xbill.DNS.SOARecord
import org.xbill.DNS.Section
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.TXTRecord
import org.xbill.DNS.Type
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration

// ─── DNS Health Check ────────────────────────────────────────────────────────

private fun addCheck(
    checks: MutableList<DnsHealthCheck>,
    category: String,
    check: String,
    status: String,
    detail: String,
) {
    checks.add(DnsHealthCheck(category = category, check = check, status = status, detail = detail))
}

fun dnsHealthCheck(ctx: Context) {
    val domain = ctx.pathParam("domain")
    val resolver = DnsResolver()
    val checks = mutableListOf<DnsHealthCheck>()
    val nameservers = mutableListOf<NameserverStatus>()

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
        } catch (_: Exception) {
        }
    }

    // 3. For each NS: resolve IP, query it directly, measure response time, check auth
    val serialNumbers = mutableListOf<Long>()
    val nsIpMap = mutableMapOf<String, String>() // nsName -> IP
    for (nsName in nsNames) {
        val nsInfo = NameserverStatus(
            type = "NS",
            name = nsName,
            ttl = nsResult.records.firstOrNull { it.data.trimEnd('.') == nsName }?.ttl,
        )
        try {
            val aResult = resolver.lookup(nsName, Type.A)
            val ip = aResult.records.firstOrNull()?.data
            if (ip != null) {
                nsInfo.ip = ip
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
                    nsInfo.timeMs = elapsed
                    nsInfo.responding = true
                    nsInfo.authoritative = response.header.getFlag(Flags.AA.toInt())
                    nsInfo.status = if (response.header.getFlag(Flags.AA.toInt())) "OK" else "WARN"
                    val nsSoa = response.getSection(Section.ANSWER)
                        .filterIsInstance<SOARecord>().firstOrNull()
                    if (nsSoa != null) {
                        serialNumbers.add(nsSoa.serial)
                        nsInfo.serial = nsSoa.serial
                    }
                } catch (_: Exception) {
                    nsInfo.responding = false
                    nsInfo.status = "FAIL"
                }
            }
        } catch (_: Exception) {
        }
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
    } catch (_: Exception) {
    }

    // Collect per-NS SOA data for consistency checks
    data class NsSoaData(val rname: String, val mname: String, val refresh: Long, val retry: Long, val expire: Long, val minimum: Long)
    val nsSoaMap = mutableMapOf<String, NsSoaData>()
    val nsNsSetMap = mutableMapOf<String, Set<String>>()
    for (ns in nameservers) {
        val ip = ns.ip ?: continue
        val nsName = ns.name
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
        } catch (_: Exception) {
        }
    }

    val nsIps = nameservers.mapNotNull { it.ip }

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
    val anyResponding = nameservers.any { it.responding }
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
    val allAuth = nameservers.all { it.authoritative }
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
        } catch (_: Exception) {
        }
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
    val allResponding = nameservers.all { it.responding }
    addCheck(checks, "Connectivity", "UDP Connectivity To All NS", if (allResponding && nameservers.isNotEmpty()) "PASS" else "FAIL",
        if (allResponding) "All ${nameservers.size} NS responded via UDP" else "${nameservers.count { it.responding }}/${nameservers.size} responding via UDP")

    // CONNECTIVITY02: TCP connectivity to NS (port 53)
    val tcpFailed = mutableListOf<String>()
    for (ns in nameservers) {
        val ip = ns.ip ?: continue
        try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(ip, 53), 5000)
            }
        } catch (_: Exception) { tcpFailed.add("${ns.name} ($ip)") }
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
        } catch (_: Exception) {
        }
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
        val ip = ns.ip ?: continue
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
                openRecursiveNs.add("${ns.name} ($ip)")
            }
        } catch (_: Exception) {
        }
    }
    addCheck(checks, "Nameserver", "No Open Recursive NS Detected", if (!openRecursive) "PASS" else "FAIL",
        if (!openRecursive) "No open recursive resolvers found" else "Open recursive: ${openRecursiveNs.joinToString(", ")}")

    // NAMESERVER02: EDNS0 support
    val ednsUnsupported = mutableListOf<String>()
    for (ns in nameservers) {
        val ip = ns.ip ?: continue
        try {
            val nsResolver = SimpleResolver(ip)
            nsResolver.setTimeout(Duration.ofSeconds(3))
            nsResolver.setEDNS(0, 4096, 0, emptyList())
            val ednsQ = Message.newQuery(Record.newRecord(Name.fromString("$domain."), Type.SOA, DClass.IN))
            val ednsR = nsResolver.send(ednsQ)
            val opt = ednsR.getOPT()
            if (opt == null) ednsUnsupported.add(ns.name)
        } catch (_: Exception) { ednsUnsupported.add(ns.name) }
    }
    addCheck(checks, "Nameserver", "EDNS0 Support", if (ednsUnsupported.isEmpty() && nsIps.isNotEmpty()) "PASS" else if (nsIps.isEmpty()) "WARN" else "WARN",
        if (ednsUnsupported.isEmpty()) "All NS support EDNS0" else "EDNS0 not confirmed: ${ednsUnsupported.joinToString(", ")}")

    // NAMESERVER03: AXFR disabled (zone transfer should be refused)
    val axfrAllowed = mutableListOf<String>()
    for (ns in nameservers) {
        val ip = ns.ip ?: continue
        try {
            val nsResolver = SimpleResolver(ip)
            nsResolver.setTimeout(Duration.ofSeconds(3))
            val axfrQ = Message.newQuery(Record.newRecord(Name.fromString("$domain."), Type.AXFR, DClass.IN))
            val axfrR = nsResolver.send(axfrQ)
            if (axfrR.header.rcode == Rcode.NOERROR && axfrR.getSection(Section.ANSWER).size > 1) {
                axfrAllowed.add("${ns.name} ($ip)")
            }
        } catch (_: Exception) { /* refused or timeout = good */ }
    }
    addCheck(checks, "Nameserver", "AXFR (Zone Transfer) Disabled", if (axfrAllowed.isEmpty()) "PASS" else "FAIL",
        if (axfrAllowed.isEmpty()) "Zone transfer refused by all NS" else "AXFR allowed: ${axfrAllowed.joinToString(", ")}")

    // NAMESERVER08: QNAME case insensitivity
    val caseInsensitiveFail = mutableListOf<String>()
    for (ns in nameservers) {
        val ip = ns.ip ?: continue
        try {
            val nsResolver = SimpleResolver(ip)
            nsResolver.setTimeout(Duration.ofSeconds(3))
            val mixedCase = domain.mapIndexed { i, c -> if (i % 2 == 0) c.uppercaseChar() else c.lowercaseChar() }.joinToString("")
            val caseQ = Message.newQuery(Record.newRecord(Name.fromString("$mixedCase."), Type.SOA, DClass.IN))
            val caseR = nsResolver.send(caseQ)
            if (caseR.header.rcode != Rcode.NOERROR) {
                caseInsensitiveFail.add(ns.name)
            }
        } catch (_: Exception) {
        }
    }
    addCheck(checks, "Nameserver", "QNAME Case Insensitivity", if (caseInsensitiveFail.isEmpty() && nsIps.isNotEmpty()) "PASS" else if (caseInsensitiveFail.isNotEmpty()) "WARN" else "INFO",
        if (caseInsensitiveFail.isEmpty()) "All NS handle mixed-case queries correctly" else "Case sensitivity issues: ${caseInsensitiveFail.joinToString(", ")}")

    // NAMESERVER15: Software version hidden (version.bind)
    val versionExposed = mutableListOf<String>()
    for (ns in nameservers) {
        val ip = ns.ip ?: continue
        try {
            val nsResolver = SimpleResolver(ip)
            nsResolver.setTimeout(Duration.ofSeconds(3))
            val versionQ = Message.newQuery(Record.newRecord(Name.fromString("version.bind."), Type.TXT, DClass.CH))
            val versionR = nsResolver.send(versionQ)
            val versionTxt = versionR.getSection(Section.ANSWER).filterIsInstance<TXTRecord>()
            if (versionTxt.isNotEmpty()) {
                val ver = versionTxt.first().strings.joinToString("")
                versionExposed.add("${ns.name}: $ver")
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
                val dsRecs = dsMsg.getSection(Section.ANSWER).filterIsInstance<org.xbill.DNS.DSRecord>()
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
            val dnskeyRecs = dnskeyMsg.getSection(Section.ANSWER).filterIsInstance<org.xbill.DNS.DNSKEYRecord>()
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
            val rrsigs = rrsigMsg.getSection(Section.ANSWER).filterIsInstance<org.xbill.DNS.RRSIGRecord>()
            val dnskeyRrsig = rrsigs.any { it.typeCovered == Type.DNSKEY }
            addCheck(checks, "DNSSEC", "RRSIG Exists For DNSKEY", if (dnskeyRrsig) "PASS" else "FAIL",
                if (dnskeyRrsig) "DNSKEY RRset is signed" else "No RRSIG covering DNSKEY")
        } catch (_: Exception) {
            addCheck(checks, "DNSSEC", "RRSIG Exists For DNSKEY", "WARN", "Could not verify DNSKEY RRSIG")
        }

        try {
            val soaRrsigMsg = resolver.dnssecQuery(domain, Type.SOA)
            val soaRrsigs = soaRrsigMsg.getSection(Section.ANSWER).filterIsInstance<org.xbill.DNS.RRSIGRecord>()
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
            } catch (_: Exception) {
            }
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

    val passCount = checks.count { it.status == "PASS" }
    val warnCount = checks.count { it.status == "WARN" }
    val failCount = checks.count { it.status == "FAIL" }
    val infoCount = checks.count { it.status == "INFO" }
    val overall = when {
        failCount > 0 -> "FAIL"
        warnCount > 3 -> "WARN"
        else -> "PASS"
    }

    val categories = checks.map { it.category }.distinct()

    val response = DnsHealthResponse(
        domain = domain,
        overallStatus = overall,
        soa = SoaInfo(
            primary = soaPrimary,
            admin = soaAdmin,
            serial = soaSerial,
            refresh = soaRefresh,
            retry = soaRetry,
            expire = soaExpire,
            minimum = soaMinimum,
        ),
        nameservers = nameservers,
        summary = CheckSummary(pass = passCount, warn = warnCount, fail = failCount, info = infoCount, total = checks.size),
        categories = categories,
        checks = checks,
    )
    ctx.jsonResult(response)
}

// ─── Models ─────────────────────────────────────────────────────────────────

data class DnsHealthCheck(
    val category: String,
    val check: String,
    val status: String,
    val detail: String,
)

data class NameserverStatus(
    val type: String,
    val name: String,
    val ttl: Long?,
    var ip: String? = null,
    var status: String = "FAIL",
    var timeMs: Long? = null,
    var authoritative: Boolean = false,
    var responding: Boolean = false,
    var serial: Long? = null,
)

data class SoaInfo(
    val primary: String,
    val admin: String,
    val serial: Long,
    val refresh: Long,
    val retry: Long,
    val expire: Long,
    val minimum: Long,
)

data class DnsHealthResponse(
    val domain: String,
    val overallStatus: String,
    val soa: SoaInfo,
    val nameservers: List<NameserverStatus>,
    val summary: CheckSummary,
    val categories: List<String>,
    val checks: List<DnsHealthCheck>,
)
