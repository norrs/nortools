package no.norrs.nortools.lib.zeroconf

import no.norrs.nortools.lib.dns.DnsWireCodec
import no.norrs.nortools.lib.dns.DnsWireRecord
import org.xbill.DNS.Type
import java.time.Duration

data class MdnsRecord(
    val section: String,
    val name: String,
    val type: String,
    val dnsClass: String,
    val ttl: Long,
    val data: String,
)

data class MdnsResult(
    val protocol: String = "mDNS",
    val mode: String,
    val status: String,
    val queryName: String? = null,
    val queryType: String? = null,
    val responseCount: Int,
    val records: List<MdnsRecord>,
    val observations: List<UdpObservation>,
    val warnings: List<String> = emptyList(),
)

class MdnsClient(
    private val timeout: Duration = Duration.ofSeconds(5),
) {
    fun query(
        name: String,
        type: String = "PTR",
        bindAddress: String? = null,
        maxPackets: Int = 25,
    ): MdnsResult {
        val normalizedType = MdnsCodec.typeCode(type)
        val payload = MdnsCodec.buildQuery(name, normalizedType)
        val session = BoundedUdpDiscovery(protocol = "mDNS", timeout = timeout)
            .sendAndReceiveMulticast(
                payload = payload,
                groupAddress = MDNS_IPV4_GROUP,
                groupPort = MDNS_PORT,
                bindAddress = bindAddress,
                maxPackets = maxPackets,
            )
        val observations = session.observations
        return MdnsResult(
            mode = "query",
            status = if (observations.any { it.direction == UdpDirection.RECEIVED }) "ok" else "no-responses",
            queryName = MdnsCodec.normalizeName(name),
            queryType = Type.string(normalizedType),
            responseCount = observations.count { it.direction == UdpDirection.RECEIVED },
            records = observations.flatMap { observation ->
                if (observation.direction == UdpDirection.RECEIVED) {
                    MdnsCodec.parseRecords(observation.payload)
                } else {
                    emptyList()
                }
            },
            observations = observations,
            warnings = session.warnings,
        )
    }

    fun listen(bindAddress: String = "0.0.0.0", maxPackets: Int = 25): MdnsResult {
        val session = BoundedUdpDiscovery(protocol = "mDNS", timeout = timeout)
            .listenMulticast(
                groupAddress = MDNS_IPV4_GROUP,
                bindPort = MDNS_PORT,
                bindAddress = bindAddress,
                maxPackets = maxPackets,
            )
        val observations = session.observations
        return MdnsResult(
            mode = "listen",
            status = if (observations.isEmpty()) "no-responses" else "ok",
            responseCount = observations.size,
            records = observations.flatMap { MdnsCodec.parseRecords(it.payload) },
            observations = observations,
            warnings = session.warnings,
        )
    }

    companion object {
        const val MDNS_IPV4_GROUP = "224.0.0.251"
        const val MDNS_PORT = 5353
    }
}

object MdnsCodec {
    fun normalizeName(name: String): String = DnsWireCodec.normalizeName(name)

    fun typeCode(type: String): Int = DnsWireCodec.typeCode(type)

    fun buildQuery(name: String, type: Int): ByteArray =
        DnsWireCodec.buildQueryWire(name, type, recursionDesired = false)

    fun parseRecords(payload: ByteArray): List<MdnsRecord> =
        DnsWireCodec.parseRecords(payload).map(DnsWireRecord::toMdnsRecord)
}

private fun DnsWireRecord.toMdnsRecord(): MdnsRecord =
    MdnsRecord(
        section = section,
        name = name,
        type = type,
        dnsClass = dnsClass,
        ttl = ttl,
        data = data,
    )
