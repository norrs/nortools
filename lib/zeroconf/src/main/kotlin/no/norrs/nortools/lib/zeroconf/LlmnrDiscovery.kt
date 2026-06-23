package no.norrs.nortools.lib.zeroconf

import no.norrs.nortools.lib.dns.DnsWireCodec
import no.norrs.nortools.lib.dns.DnsWireRecord
import org.xbill.DNS.Type
import java.time.Duration

data class LlmnrRecord(
    val section: String,
    val name: String,
    val type: String,
    val dnsClass: String,
    val ttl: Long,
    val data: String,
)

data class LlmnrResult(
    val protocol: String = "LLMNR",
    val mode: String,
    val status: String,
    val queryName: String? = null,
    val queryType: String? = null,
    val responseCount: Int,
    val records: List<LlmnrRecord>,
    val observations: List<UdpObservation>,
    val warnings: List<String> = emptyList(),
)

class LlmnrClient(
    private val timeout: Duration = Duration.ofSeconds(5),
) {
    fun query(
        name: String,
        type: String = "A",
        ipFamily: IpFamily = IpFamily.IPV4,
        bindAddress: String? = null,
        maxPackets: Int = 25,
    ): LlmnrResult {
        val normalizedType = LlmnrCodec.typeCode(type)
        val payload = LlmnrCodec.buildQuery(name, normalizedType)
        val sessions = multicastFamilies(ipFamily).map { family ->
            BoundedUdpDiscovery(protocol = "LLMNR", timeout = timeout).sendAndReceiveMulticast(
                payload = payload,
                groupAddress = groupFor(family),
                groupPort = LLMNR_PORT,
                bindAddress = bindAddressForFamily(bindAddress, family),
                maxPackets = maxPackets,
                ipFamily = family,
            )
        }
        val observations = sessions.flatMap { it.observations }
        val warnings = sessions.flatMap { it.warnings }
        val responseObservations = observations.filter { it.direction == UdpDirection.RECEIVED }
        return LlmnrResult(
            mode = "query",
            status = if (responseObservations.isEmpty()) "no-responses" else "ok",
            queryName = LlmnrCodec.normalizeName(name),
            queryType = Type.string(normalizedType),
            responseCount = responseObservations.size,
            records = responseObservations.flatMap { LlmnrCodec.parseRecords(it.payload) },
            observations = observations,
            warnings = warnings,
        )
    }

    fun listen(
        ipFamily: IpFamily = IpFamily.IPV4,
        bindAddress: String? = null,
        maxPackets: Int = 25,
    ): LlmnrResult {
        val sessions = multicastFamilies(ipFamily).map { family ->
            BoundedUdpDiscovery(protocol = "LLMNR", timeout = timeout).listenMulticast(
                groupAddress = groupFor(family),
                bindPort = LLMNR_PORT,
                bindAddress = bindAddressForFamily(bindAddress, family),
                maxPackets = maxPackets,
                ipFamily = family,
            )
        }
        val observations = sessions.flatMap { it.observations }
        val warnings = sessions.flatMap { it.warnings }
        return LlmnrResult(
            mode = "listen",
            status = if (observations.isEmpty()) "no-responses" else "ok",
            responseCount = observations.size,
            records = observations.flatMap { LlmnrCodec.parseRecords(it.payload) },
            observations = observations,
            warnings = warnings,
        )
    }

    private fun multicastFamilies(ipFamily: IpFamily): List<IpFamily> =
        when (ipFamily) {
            IpFamily.IPV4 -> listOf(IpFamily.IPV4)
            IpFamily.IPV6 -> listOf(IpFamily.IPV6)
            IpFamily.BOTH -> listOf(IpFamily.IPV4, IpFamily.IPV6)
        }

    private fun groupFor(ipFamily: IpFamily): String =
        when (ipFamily) {
            IpFamily.IPV4 -> LLMNR_IPV4_GROUP
            IpFamily.IPV6 -> LLMNR_IPV6_GROUP
            IpFamily.BOTH -> error("Unexpected BOTH family")
        }

    private fun bindAddressForFamily(bindAddress: String?, ipFamily: IpFamily): String? {
        if (bindAddress.isNullOrBlank() || bindAddress == "0.0.0.0") {
            return null
        }
        if (ipFamily == IpFamily.IPV6 && !bindAddress.contains(':')) {
            return null
        }
        if (ipFamily == IpFamily.IPV4 && bindAddress.contains(':')) {
            return null
        }
        return bindAddress
    }

    companion object {
        const val LLMNR_IPV4_GROUP = "224.0.0.252"
        const val LLMNR_IPV6_GROUP = "ff02::1:3"
        const val LLMNR_PORT = 5355
    }
}

object LlmnrCodec {
    fun normalizeName(name: String): String = DnsWireCodec.normalizeName(name)

    fun typeCode(type: String): Int = DnsWireCodec.typeCode(type)

    fun buildQuery(name: String, type: Int): ByteArray =
        DnsWireCodec.buildQueryWire(name, type, recursionDesired = false)

    fun parseRecords(payload: ByteArray): List<LlmnrRecord> =
        DnsWireCodec.parseRecords(payload).map(DnsWireRecord::toLlmnrRecord)
}

private fun DnsWireRecord.toLlmnrRecord(): LlmnrRecord =
    LlmnrRecord(
        section = section,
        name = name,
        type = type,
        dnsClass = dnsClass,
        ttl = ttl,
        data = data,
    )
