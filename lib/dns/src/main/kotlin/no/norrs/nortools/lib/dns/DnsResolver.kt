package no.norrs.nortools.lib.dns

import org.xbill.DNS.*
import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Shared DNS resolver wrapping dnsjava.
 * Provides a unified interface for all DNS lookup tools.
 */
class DnsResolver(
    private val server: String? = null,
    private val timeout: Duration = Duration.ofSeconds(10),
) {
    private val configuredResolvers: List<String> =
        if (server != null) {
            listOf(server)
        } else {
            systemNameServers().ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        }

    private val resolver: Resolver by lazy {
        buildResolver(dnssec = false)
    }

    private fun buildResolver(dnssec: Boolean): Resolver {
        val r = if (server != null) {
            createSimpleResolver(server)
        } else {
            val resolvers = configuredResolvers.mapNotNull { ns ->
                try {
                    createSimpleResolver(ns)
                } catch (_: UnknownHostException) {
                    null
                }
            }
            when {
                resolvers.isEmpty() -> createSimpleResolver("1.1.1.1")
                resolvers.size == 1 -> resolvers[0]
                else -> ExtendedResolver(resolvers.toTypedArray()).apply { setTimeout(timeout) }
            }
        }

        if (dnssec) {
            r.setEDNS(0, 4096, ExtendedFlags.DO, emptyList())
        }
        return r
    }

    private fun createSimpleResolver(host: String): SimpleResolver =
        SimpleResolver(host).apply { setTimeout(timeout) }

    private fun systemNameServers(): List<String> {
        val fromDnsJava = dnsjavaNameServers()
        if (fromDnsJava.isNotEmpty()) return fromDnsJava
        if (isWindowsHost()) {
            val fromIpconfig = ipconfigNameServers()
            if (fromIpconfig.isNotEmpty()) return fromIpconfig
        }
        return emptyList()
    }

    private fun dnsjavaNameServers(): List<String> =
        try {
            ResolverConfig.getCurrentConfig()
                .servers()
                .mapNotNull { server ->
                    // Some Windows providers return unresolved socket addresses.
                    // Keep hostString instead of dropping unresolved entries.
                    server.address?.hostAddress
                        ?: server.hostString?.takeIf { it.isNotBlank() }
                }
                .map { normalizeResolverHost(it) }
                .filter { it.isNotBlank() }
                .distinct()
        } catch (_: Throwable) {
            emptyList()
        }

    private fun ipconfigNameServers(): List<String> {
        return try {
            val process = ProcessBuilder("ipconfig", "/all")
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(3, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                emptyList()
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }
                parseWindowsIpconfigDnsServers(output)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun isWindowsHost(): Boolean =
        System.getProperty("os.name")?.lowercase(Locale.ROOT)?.contains("win") == true

    /**
     * Return the resolver list currently configured for this resolver instance.
     */
    fun activeResolvers(): List<String> = configuredResolvers

    /**
     * Perform a DNS lookup for the given name and record type.
     * Returns a list of DnsRecord results.
     */
    fun lookup(name: String, type: Int): DnsLookupResult {
        return try {
            val dnsName = if (name.endsWith(".")) Name.fromString(name) else Name.fromString("$name.")
            val record = Record.newRecord(dnsName, type, DClass.IN)
            val query = Message.newQuery(record)
            val response = resolver.send(query)
            val answers = response.getSection(Section.ANSWER)
            val rcode = response.header.rcode

            DnsLookupResult(
                name = name,
                type = Type.string(type),
                status = Rcode.string(rcode),
                records = answers.map { rec ->
                    DnsRecord(
                        name = rec.name.toString(),
                        type = Type.string(rec.type),
                        ttl = rec.ttl,
                        data = formatRecord(rec),
                    )
                },
                isSuccessful = rcode == Rcode.NOERROR && answers.isNotEmpty(),
                resolvers = configuredResolvers,
            )
        } catch (e: Exception) {
            DnsLookupResult(
                name = name,
                type = Type.string(type),
                status = "ERROR: ${e.message}",
                records = emptyList(),
                isSuccessful = false,
                resolvers = configuredResolvers,
            )
        }
    }

    /**
     * Perform a reverse DNS lookup for an IP address.
     */
    fun reverseLookup(ip: String): DnsLookupResult {
        val addr = InetAddress.getByName(ip)
        val reverseName = ReverseMap.fromAddress(addr)
        return lookup(reverseName.toString(), Type.PTR)
    }

    /**
     * Query for a specific record type by string name (e.g., "MX", "A", "AAAA").
     */
    fun lookup(name: String, typeString: String): DnsLookupResult {
        val type = Type.value(typeString)
        require(type != -1) { "Unknown DNS record type: $typeString" }
        return lookup(name, type)
    }

    /**
     * Perform a raw DNS query and return the full Message response.
     * Useful for DNSSEC and advanced queries.
     */
    fun rawQuery(name: String, type: Int, flags: Int = 0): Message {
        val dnsName = if (name.endsWith(".")) Name.fromString(name) else Name.fromString("$name.")
        val record = Record.newRecord(dnsName, type, DClass.IN)
        val query = Message.newQuery(record)
        if (flags != 0) {
            query.header.setFlag(flags)
        }
        return resolver.send(query)
    }

    /**
     * Perform a DNSSEC-aware DNS query with the DO (DNSSEC OK) bit set.
     * Returns the full Message response including DNSSEC records.
     */
    fun dnssecQuery(name: String, type: Int): Message {
        val dnsName = if (name.endsWith(".")) Name.fromString(name) else Name.fromString("$name.")
        val record = Record.newRecord(dnsName, type, DClass.IN)
        val query = Message.newQuery(record)
        // Set the AD (Authenticated Data) flag
        query.header.setFlag(Flags.AD.toInt())
        val dnssecResolver = buildResolver(dnssec = true)
        return dnssecResolver.send(query)
    }

    /**
     * Perform a DNSSEC lookup and return structured results.
     * Uses the DO bit to request DNSSEC records.
     */
    fun dnssecLookup(name: String, type: Int): DnsLookupResult {
        return try {
            val response = dnssecQuery(name, type)
            val rcode = response.header.rcode
            val answers = response.getSection(Section.ANSWER)

            DnsLookupResult(
                name = name,
                type = Type.string(type),
                status = Rcode.string(rcode),
                records = answers
                    .filter { it.type == type }
                    .map { record ->
                        DnsRecord(
                            name = record.name.toString(),
                            type = Type.string(record.type),
                            ttl = record.ttl,
                            data = formatRecord(record),
                        )
                    },
                isSuccessful = rcode == Rcode.NOERROR && answers.any { it.type == type },
                resolvers = configuredResolvers,
            )
        } catch (e: Exception) {
            DnsLookupResult(
                name = name,
                type = Type.string(type),
                status = "ERROR: ${e.message}",
                records = emptyList(),
                isSuccessful = false,
                resolvers = configuredResolvers,
            )
        }
    }

    companion object {
        private val IPV4_REGEX =
            Regex("""\b(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\b""")

        private val IPV6_REGEX =
            Regex("""^[0-9A-Fa-f:]+(?:%\S+)?$""")

        private fun normalizeResolverHost(raw: String): String {
            val trimmed = raw.trim().removePrefix("[").removeSuffix("]")
            return trimmed.substringBefore(" ").trim()
        }

        private fun extractIpToken(raw: String): String? {
            val candidate = raw.substringBefore("(").trim().substringBefore(" ").trim()
            if (candidate.isBlank()) return null
            return if (IPV4_REGEX.matches(candidate) || (candidate.contains(":") && IPV6_REGEX.matches(candidate))) {
                candidate
            } else {
                null
            }
        }

        fun parseWindowsIpconfigDnsServers(text: String): List<String> {
            val out = mutableListOf<String>()
            var inDnsBlock = false

            for (line in text.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isBlank()) {
                    inDnsBlock = false
                    continue
                }

                val colonIdx = line.indexOf(':')
                if (colonIdx >= 0) {
                    val key = line.substring(0, colonIdx).trim().lowercase(Locale.ROOT)
                    val value = line.substring(colonIdx + 1).trim()
                    if (key.contains("dns") && key.contains("server")) {
                        inDnsBlock = true
                        extractIpToken(value)?.let { out += it }
                        continue
                    }
                    inDnsBlock = false
                    continue
                }

                if (inDnsBlock) {
                    extractIpToken(trimmed)?.let { out += it } ?: run { inDnsBlock = false }
                }
            }

            return out.distinct()
        }

        /**
         * Format a DNS record's RDATA into a human-readable string.
         */
        fun formatRecord(record: Record): String {
            return when (record) {
                is MXRecord -> "${record.priority} ${record.target}"
                is ARecord -> record.address.hostAddress ?: ""
                is AAAARecord -> record.address.hostAddress ?: ""
                is CNAMERecord -> record.target.toString()
                is TXTRecord -> record.strings.joinToString("")
                is SOARecord -> "${record.host} ${record.admin} ${record.serial} ${record.refresh} ${record.retry} ${record.expire} ${record.minimum}"
                is PTRRecord -> record.target.toString()
                is NSRecord -> record.target.toString()
                is SRVRecord -> "${record.priority} ${record.weight} ${record.port} ${record.target}"
                is DNSKEYRecord -> "${record.flags} ${record.protocol} ${record.algorithm} ${java.util.Base64.getEncoder().encodeToString(record.key)}"
                is DSRecord -> "${record.footprint} ${record.algorithm} ${record.digestID} ${record.digest.joinToString("") { "%02X".format(it) }}"
                is RRSIGRecord -> "${record.typeCovered} ${record.algorithm} ${record.labels} ${record.origTTL} ${record.expire} ${record.timeSigned} ${record.footprint} ${record.signer}"
                is NSECRecord -> "${record.next} ${record.types.joinToString(" ") { Type.string(it) }}"
                is NSEC3PARAMRecord -> "${record.hashAlgorithm} ${record.flags} ${record.iterations} ${record.salt?.joinToString("") { "%02X".format(it) } ?: "-"}"
                else -> record.rdataToString()
            }
        }
    }
}
