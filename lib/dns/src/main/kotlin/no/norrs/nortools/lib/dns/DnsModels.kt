package no.norrs.nortools.lib.dns

/**
 * Represents a single DNS record in a lookup result.
 */
data class DnsRecord(
    val name: String,
    val type: String,
    val ttl: Long,
    val data: String,
)

/**
 * Represents the result of a DNS lookup operation.
 */
data class DnsLookupResult(
    val name: String,
    val type: String,
    val status: String,
    val records: List<DnsRecord>,
    val isSuccessful: Boolean,
)
