package no.norrs.nortools.lib.zeroconf

import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Record
import org.xbill.DNS.Section
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
        val observations = BoundedUdpDiscovery(protocol = "mDNS", timeout = timeout)
            .sendAndReceive(
                payload = payload,
                targetAddress = MDNS_IPV4_GROUP,
                targetPort = MDNS_PORT,
                bindAddress = bindAddress,
                bindPort = MDNS_PORT,
                maxPackets = maxPackets,
            )
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
        )
    }

    fun listen(bindAddress: String = "0.0.0.0", maxPackets: Int = 25): MdnsResult {
        val observations = BoundedUdpDiscovery(protocol = "mDNS", timeout = timeout)
            .listen(bindAddress = bindAddress, bindPort = MDNS_PORT, maxPackets = maxPackets)
        return MdnsResult(
            mode = "listen",
            status = if (observations.isEmpty()) "no-responses" else "ok",
            responseCount = observations.size,
            records = observations.flatMap { MdnsCodec.parseRecords(it.payload) },
            observations = observations,
            warnings = listOf(
                "Passive mDNS listen binds UDP 5353. Interface-scoped multicast group joins will be added with the full mDNS browser.",
            ),
        )
    }

    companion object {
        const val MDNS_IPV4_GROUP = "224.0.0.251"
        const val MDNS_PORT = 5353
    }
}

object MdnsCodec {
    fun normalizeName(name: String): String =
        if (name.endsWith(".")) name else "$name."

    fun typeCode(type: String): Int {
        val code = Type.value(type.uppercase())
        require(code > 0) { "Unknown DNS record type: $type" }
        return code
    }

    fun buildQuery(name: String, type: Int): ByteArray {
        val dnsName = Name.fromString(normalizeName(name))
        val record = Record.newRecord(dnsName, type, DClass.IN)
        val message = Message.newQuery(record)
        message.header.unsetFlag(Flags.RD.toInt())
        return message.toWire()
    }

    fun parseRecords(payload: ByteArray): List<MdnsRecord> {
        val message = Message(payload)
        return listOf(
            Section.ANSWER to "answer",
            Section.AUTHORITY to "authority",
            Section.ADDITIONAL to "additional",
        ).flatMap { (section, label) ->
            message.getSection(section).map { record ->
                MdnsRecord(
                    section = label,
                    name = record.name.toString(),
                    type = Type.string(record.type),
                    dnsClass = DClass.string(record.getDClass() and 0x7fff),
                    ttl = record.ttl,
                    data = record.rdataToString(),
                )
            }
        }
    }
}
