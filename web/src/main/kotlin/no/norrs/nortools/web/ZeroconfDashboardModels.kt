package no.norrs.nortools.web

data class ZeroconfDashboardSnapshot(
    val generatedAt: String,
    val running: Boolean,
    val scanning: Boolean,
    val lastCycleStartedAt: String?,
    val lastCycleFinishedAt: String?,
    val deviceCount: Int,
    val serviceCount: Int,
    val devices: List<ZeroconfDashboardDevice>,
    val hostnames: List<ZeroconfHostnameResolution>,
    val serviceCatalog: List<ZeroconfServiceTypeInfo>,
    val events: List<ZeroconfDashboardEvent>,
    val protocolStats: List<ZeroconfProtocolStat>,
    val warnings: List<String>,
)

data class ZeroconfDashboardDevice(
    val id: String,
    val displayName: String,
    val category: String,
    val addresses: List<String>,
    val hostnames: List<String>,
    val protocols: List<String>,
    val services: List<ZeroconfDashboardService>,
    val dnsRecords: List<ZeroconfDnsRecordView>,
    val txtRecords: List<ZeroconfTxtRecordView>,
    val locations: List<String>,
    val documents: List<ZeroconfDiscoveryDocument>,
    val firstSeen: String,
    val lastSeen: String,
    val evidenceCount: Int,
    val confidence: String,
    val details: Map<String, String>,
)

data class ZeroconfDashboardService(
    val protocol: String,
    val type: String,
    val name: String,
    val target: String,
    val location: String,
    val port: Int? = null,
    val description: String = "",
)

data class ZeroconfDiscoveryDocument(
    val index: Int,
    val protocol: String,
    val label: String,
    val contentType: String,
    val sizeBytes: Int,
)

data class ZeroconfDnsRecordView(
    val hostname: String,
    val type: String,
    val value: String,
    val ttl: Long,
)

data class ZeroconfTxtRecordView(
    val service: String,
    val key: String,
    val value: String,
)

data class ZeroconfHostnameResolution(
    val hostname: String,
    val addresses: List<String>,
    val protocols: List<String>,
    val records: List<ZeroconfDnsRecordView>,
)

data class ZeroconfServiceTypeInfo(
    val protocol: String,
    val type: String,
    val title: String,
    val description: String,
    val observed: Int,
)

data class ZeroconfDashboardEvent(
    val seenAt: String,
    val protocol: String,
    val summary: String,
)

data class ZeroconfProtocolStat(
    val protocol: String,
    val status: String,
    val observations: Int,
    val lastObservations: Int,
    val lastSeen: String?,
)
