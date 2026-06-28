package no.norrs.nortools.web

import no.norrs.nortools.lib.zeroconf.WsDiscoveryMessage
import no.norrs.nortools.lib.zeroconf.WsDiscoveryMetadata
import java.time.Instant

internal data class EnrichedWsdMessage(
    val message: WsDiscoveryMessage,
    val metadata: WsDiscoveryMetadata?,
)

internal data class MutableDiscoveryDocument(
    val protocol: String,
    val label: String,
    val contentType: String,
    val content: String,
) {
    fun toDocument(index: Int): ZeroconfDiscoveryDocument =
        ZeroconfDiscoveryDocument(
            index = index,
            protocol = protocol,
            label = label,
            contentType = contentType,
            sizeBytes = content.toByteArray(Charsets.UTF_8).size,
        )
}

internal data class SmbSweepHit(
    val address: String,
    val hostname: String?,
    val wsdTcpOpen: Boolean,
)

internal class MutableDashboardDevice(
    val id: String,
    var displayName: String,
    var category: String,
) {
    val addresses = linkedSetOf<String>()
    val hostnames = linkedSetOf<String>()
    val protocols = linkedSetOf<String>()
    val services = linkedSetOf<ZeroconfDashboardService>()
    val dnsRecords = linkedSetOf<ZeroconfDnsRecordView>()
    val txtRecords = linkedSetOf<ZeroconfTxtRecordView>()
    val locations = linkedSetOf<String>()
    val documents = mutableListOf<MutableDiscoveryDocument>()
    val details = linkedMapOf<String, String>()
    val firstSeen: String = Instant.now().toString()
    var lastSeen: String = firstSeen
    var evidenceCount: Int = 0

    fun touch() {
        lastSeen = Instant.now().toString()
        evidenceCount += 1
    }

    fun toDevice(): ZeroconfDashboardDevice =
        ZeroconfDashboardDevice(
            id = id,
            displayName = displayName,
            category = category,
            addresses = addresses.toList().sorted(),
            hostnames = hostnames.toList().sorted(),
            protocols = protocols.toList().sorted(),
            services = services.toList().takeLast(20),
            dnsRecords = dnsRecords.toList().takeLast(30),
            txtRecords = txtRecords.toList().sortedWith(compareBy({ it.service }, { it.key })),
            locations = locations.toList().sorted(),
            documents = documents.mapIndexed { index, document -> document.toDocument(index) },
            firstSeen = firstSeen,
            lastSeen = lastSeen,
            evidenceCount = evidenceCount,
            confidence = when {
                protocols.size >= 2 -> "high"
                evidenceCount >= 3 -> "medium"
                else -> "low"
            },
            details = details.toMap(),
        )
}

internal class MutableHostnameResolution(
    val hostname: String,
) {
    val addresses = linkedSetOf<String>()
    val protocols = linkedSetOf<String>()
    val records = linkedSetOf<ZeroconfDnsRecordView>()

    fun toResolution(): ZeroconfHostnameResolution =
        ZeroconfHostnameResolution(
            hostname = hostname,
            addresses = addresses.toList().sorted(),
            protocols = protocols.toList().sorted(),
            records = records.toList(),
        )
}

internal class MutableServiceTypeInfo(
    val protocol: String,
    val type: String,
    val title: String,
    val description: String,
) {
    val observedKeys = linkedSetOf<String>()

    fun toInfo(): ZeroconfServiceTypeInfo =
        ZeroconfServiceTypeInfo(
            protocol = protocol,
            type = type,
            title = title,
            description = description,
            observed = observedKeys.size,
        )
}
