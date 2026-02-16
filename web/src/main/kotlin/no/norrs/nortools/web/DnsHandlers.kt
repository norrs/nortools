package no.norrs.nortools.web

import io.javalin.http.Context
import no.norrs.nortools.lib.dns.DnsResolver
import org.xbill.DNS.DNSKEYRecord
import org.xbill.DNS.DSRecord
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.RRSIGRecord
import org.xbill.DNS.Section
import org.xbill.DNS.Type
import java.util.Base64

// ─── DNS Tools ───────────────────────────────────────────────────────────────

fun dnsLookup(ctx: Context) {
    val type = ctx.pathParam("type").uppercase()
    val domain = ctx.pathParam("domain")
    val server = ctx.queryParam("server")
    val resolver = DnsResolver(server = if (server.isNullOrBlank()) null else server)
    val typeInt = Type.value(type)
    if (typeInt == -1) {
        ctx.jsonResult(ErrorResponse("Unknown DNS type: $type"))
        return
    }
    val result = resolver.lookup(domain, typeInt)
    ctx.jsonResult(result)
}

fun dnssecLookup(ctx: Context) {
    val type = ctx.pathParam("type").uppercase()
    val domain = ctx.pathParam("domain")
    val server = ctx.queryParam("server")
    val resolver = DnsResolver(server = if (server.isNullOrBlank()) null else server)
    val typeInt = Type.value(type)
    if (typeInt == -1) {
        ctx.jsonResult(ErrorResponse("Unknown DNS type: $type"))
        return
    }
    val result = resolver.dnssecLookup(domain, typeInt)
    ctx.jsonResult(result)
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
    val server = ctx.queryParam("server")
    val resolver = DnsResolver(server = if (server.isNullOrBlank()) null else server)

    // Split domain into zone levels: e.g. "norrs.no" → [".", "no", "norrs.no"]
    val labels = domain.split(".")
    val zones = mutableListOf(".")
    for (i in labels.indices.reversed()) {
        zones.add(labels.subList(i, labels.size).joinToString("."))
    }

    val zoneResults = mutableListOf<DnssecZone>()

    for (zone in zones) {
        val queryZone = if (zone == ".") "." else zone

        val dnskeys = mutableListOf<DnssecKey>()
        val rrsigs = mutableListOf<DnssecRrsig>()
        var adFlag: Boolean? = null
        var dnskeyError: String? = null
        try {
            val dnskeyMsg: Message = resolver.dnssecQuery(queryZone, Type.DNSKEY)
            adFlag = dnskeyMsg.header.getFlag(Flags.AD.toInt())

            for (rec in dnskeyMsg.getSection(Section.ANSWER)) {
                when (rec) {
                    is DNSKEYRecord -> dnskeys.add(
                        DnssecKey(
                            keyTag = rec.footprint,
                            algorithm = rec.algorithm,
                            algorithmName = algorithmName(rec.algorithm),
                            flags = rec.flags,
                            role = keyRole(rec.flags),
                            protocol = rec.protocol,
                            keyLength = keyLengthBits(rec.algorithm, rec.key),
                            ttl = rec.ttl,
                            keyBase64 = Base64.getEncoder().encodeToString(rec.key).take(40) + "...",
                        )
                    )
                    is RRSIGRecord -> rrsigs.add(
                        DnssecRrsig(
                            typeCovered = Type.string(rec.typeCovered),
                            algorithm = rec.algorithm,
                            algorithmName = algorithmName(rec.algorithm),
                            labels = rec.labels,
                            origTTL = rec.origTTL,
                            expiration = rec.expire.toString(),
                            inception = rec.timeSigned.toString(),
                            keyTag = rec.footprint,
                            signerName = rec.signer.toString(),
                        )
                    )
                }
            }
        } catch (_: Exception) {
            dnskeyError = "Failed to query DNSKEY for $zone"
        }

        val dsRecords = mutableListOf<DnssecDs>()
        var dsError: String? = null
        if (zone != ".") {
            try {
                val dsMsg = resolver.dnssecQuery(queryZone, Type.DS)
                for (rec in dsMsg.getSection(Section.ANSWER)) {
                    when (rec) {
                        is DSRecord -> dsRecords.add(
                            DnssecDs(
                                keyTag = rec.footprint,
                                algorithm = rec.algorithm,
                                algorithmName = algorithmName(rec.algorithm),
                                digestType = rec.digestID,
                                digestTypeName = digestTypeName(rec.digestID),
                                digest = rec.digest.joinToString("") { "%02X".format(it) },
                                ttl = rec.ttl,
                            )
                        )
                        is RRSIGRecord -> if (rec.typeCovered == Type.DS) {
                            rrsigs.add(
                                DnssecRrsig(
                                    typeCovered = "DS",
                                    algorithm = rec.algorithm,
                                    algorithmName = algorithmName(rec.algorithm),
                                    labels = rec.labels,
                                    origTTL = rec.origTTL,
                                    expiration = rec.expire.toString(),
                                    inception = rec.timeSigned.toString(),
                                    keyTag = rec.footprint,
                                    signerName = rec.signer.toString(),
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                dsError = "Failed to query DS for $zone"
            }
        }

        val nsRecords = mutableListOf<String>()
        try {
            val nsResult = resolver.lookup(queryZone, Type.NS)
            nsRecords.addAll(nsResult.records.map { it.data })
        } catch (_: Exception) {
        }

        val hasDnskey = dnskeys.isNotEmpty()
        val hasDs = dsRecords.isNotEmpty()
        val dsKeyTags = dsRecords.map { it.keyTag }.toSet()
        val dnskeyKeyTags = dnskeys.map { it.keyTag }.toSet()
        val dsMatchesDnskey = dsKeyTags.intersect(dnskeyKeyTags).isNotEmpty()

        val delegationStatus = when {
            zone == "." -> if (hasDnskey) "Secure (Trust Anchor)" else "Insecure"
            hasDnskey && hasDs && dsMatchesDnskey -> "Secure"
            hasDnskey && hasDs && !dsMatchesDnskey -> "Bogus (DS/DNSKEY mismatch)"
            hasDnskey && !hasDs -> "Insecure (no DS in parent)"
            !hasDnskey && hasDs -> "Bogus (DS exists but no DNSKEY)"
            else -> "Insecure"
        }

        val chainLinks = dsRecords.map { ds ->
            val matchingKey = dnskeys.find { it.keyTag == ds.keyTag }
            DnssecChainLink(
                dsKeyTag = ds.keyTag,
                dsDigestType = ds.digestTypeName,
                matchesDnskey = matchingKey != null,
                dnskeyRole = matchingKey?.role,
                dnskeyAlgorithm = matchingKey?.algorithmName,
            )
        }

        var nsec3: Boolean? = null
        var nsec3Params: String? = null
        if (hasDnskey) {
            try {
                val nsec3Result = resolver.lookup(queryZone, Type.NSEC3PARAM)
                if (nsec3Result.isSuccessful && nsec3Result.records.isNotEmpty()) {
                    nsec3 = true
                    nsec3Params = nsec3Result.records.first().data
                } else {
                    nsec3 = false
                }
            } catch (_: Exception) {
                nsec3 = null
            }
        }

        zoneResults.add(
            DnssecZone(
                zone = zone,
                adFlag = adFlag,
                dnskeys = dnskeys,
                rrsigs = rrsigs,
                dnskeyError = dnskeyError,
                dsRecords = dsRecords,
                dsError = dsError,
                nsRecords = nsRecords,
                delegationStatus = delegationStatus,
                hasDnskey = hasDnskey,
                hasDs = hasDs,
                dsMatchesDnskey = dsMatchesDnskey,
                chainLinks = chainLinks,
                nsec3 = nsec3,
                nsec3Params = nsec3Params,
            )
        )
    }

    val result = DnssecChainResponse(
        domain = domain,
        zones = zoneResults,
        chainSecure = zoneResults.all { it.delegationStatus.startsWith("Secure") },
        resolvers = resolver.activeResolvers(),
    )
    ctx.jsonResult(result)
}

fun reverseLookup(ctx: Context) {
    val ip = ctx.pathParam("ip")
    val server = ctx.queryParam("server")
    val resolver = DnsResolver(server = if (server.isNullOrBlank()) null else server)
    val result = resolver.reverseLookup(ip)
    ctx.jsonResult(result)
}

// ─── Models ─────────────────────────────────────────────────────────────────

data class DnssecChainResponse(
    val domain: String,
    val zones: List<DnssecZone>,
    val chainSecure: Boolean,
    val resolvers: List<String> = emptyList(),
)

data class DnssecZone(
    val zone: String,
    val adFlag: Boolean? = null,
    val dnskeys: List<DnssecKey>,
    val rrsigs: List<DnssecRrsig>,
    val dnskeyError: String? = null,
    val dsRecords: List<DnssecDs>,
    val dsError: String? = null,
    val nsRecords: List<String>,
    val delegationStatus: String,
    val hasDnskey: Boolean,
    val hasDs: Boolean,
    val dsMatchesDnskey: Boolean,
    val chainLinks: List<DnssecChainLink>,
    val nsec3: Boolean? = null,
    val nsec3Params: String? = null,
)

data class DnssecKey(
    val keyTag: Int,
    val algorithm: Int,
    val algorithmName: String,
    val flags: Int,
    val role: String,
    val protocol: Int,
    val keyLength: Int,
    val ttl: Long,
    val keyBase64: String,
)

data class DnssecRrsig(
    val typeCovered: String,
    val algorithm: Int,
    val algorithmName: String,
    val labels: Int,
    val origTTL: Long,
    val expiration: String,
    val inception: String,
    val keyTag: Int,
    val signerName: String,
)

data class DnssecDs(
    val keyTag: Int,
    val algorithm: Int,
    val algorithmName: String,
    val digestType: Int,
    val digestTypeName: String,
    val digest: String,
    val ttl: Long,
)

data class DnssecChainLink(
    val dsKeyTag: Int,
    val dsDigestType: String,
    val matchesDnskey: Boolean,
    val dnskeyRole: String? = null,
    val dnskeyAlgorithm: String? = null,
)
