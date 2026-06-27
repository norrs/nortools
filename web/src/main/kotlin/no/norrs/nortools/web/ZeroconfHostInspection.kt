package no.norrs.nortools.web

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.mssrvs.dto.NetShareInfo1
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import no.norrs.nortools.lib.network.HttpClient
import no.norrs.nortools.lib.network.TcpClient
import no.norrs.nortools.lib.zeroconf.NetbiosNameServiceClient
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

data class ZeroconfDeviceDetail(
    val generatedAt: String,
    val device: ZeroconfDashboardDevice,
    val warnings: List<String>,
    val webLinks: List<ZeroconfWebLink>,
    val portChecks: List<ZeroconfPortCheck>,
    val netbios: ZeroconfNetbiosHostDetail? = null,
    val smb: ZeroconfSmbHostDetail? = null,
)

data class ZeroconfWebLink(
    val label: String,
    val url: String,
    val source: String,
    val httpStatus: Int? = null,
    val server: String? = null,
    val reachable: Boolean = false,
    val error: String? = null,
)

data class ZeroconfPortCheck(
    val host: String,
    val port: Int,
    val label: String,
    val connected: Boolean,
    val responseTimeMs: Long,
    val banner: String? = null,
    val error: String? = null,
)

data class ZeroconfNetbiosHostDetail(
    val host: String,
    val responseCount: Int,
    val names: List<ZeroconfNetbiosNameView>,
    val addresses: List<String>,
    val errors: List<String>,
)

data class ZeroconfNetbiosNameView(
    val name: String,
    val suffix: Int,
    val group: Boolean,
    val flags: Int,
)

data class ZeroconfSmbHostDetail(
    val host: String,
    val attempted: Boolean,
    val dialect: String? = null,
    val signingRequired: Boolean = false,
    val signingEnabled: Boolean = false,
    val encryptionSupported: Boolean = false,
    val serverGuid: String? = null,
    val authenticationMode: String? = null,
    val authenticationStatus: String? = null,
    val shares: List<ZeroconfSmbShare> = emptyList(),
    val note: String? = null,
    val error: String? = null,
)

data class ZeroconfSmbShare(
    val name: String,
    val type: String,
    val comment: String = "",
)

object ZeroconfHostInspector {
    fun inspect(device: ZeroconfDashboardDevice, timeout: Duration, includeSmb: Boolean = false): ZeroconfDeviceDetail {
        val warnings = mutableListOf<String>()
        val tcpClient = TcpClient(timeout = timeout)
        val httpClient = HttpClient(timeout = timeout, followRedirects = true)

        val webLinks = buildWebCandidates(device)
            .map { candidate ->
                val result = httpClient.get(candidate.url)
                ZeroconfWebLink(
                    label = candidate.label,
                    url = candidate.url,
                    source = candidate.source,
                    httpStatus = result.statusCode.takeIf { it >= 0 },
                    server = result.headers.entries.firstOrNull { it.key.equals("server", ignoreCase = true) }?.value?.firstOrNull(),
                    reachable = result.error == null && result.statusCode > 0,
                    error = result.error,
                )
            }

        val portChecks = buildPortCandidates(device)
            .map { candidate ->
                val result = tcpClient.connect(candidate.host, candidate.port, grabBanner = candidate.grabBanner)
                ZeroconfPortCheck(
                    host = candidate.host,
                    port = candidate.port,
                    label = candidate.label,
                    connected = result.connected,
                    responseTimeMs = result.responseTimeMs,
                    banner = result.banner,
                    error = result.error,
                )
            }

        val primaryIpv4 = device.addresses.firstOrNull(::isIpv4Address)
            ?: device.locations.mapNotNull(::locationHost).firstOrNull(::isIpv4Address)

        val netbios = if (device.protocols.contains("NetBIOS") && primaryIpv4 != null) {
            inspectNetbios(primaryIpv4, timeout, warnings)
        } else {
            null
        }

        val smb = if (includeSmb && (device.protocols.contains("NetBIOS") || portChecks.any { it.port == 445 && it.connected }) && primaryIpv4 != null) {
            inspectSmb(primaryIpv4, timeout)
        } else {
            null
        }

        return ZeroconfDeviceDetail(
            generatedAt = Instant.now().toString(),
            device = device,
            warnings = warnings.distinct().sorted(),
            webLinks = webLinks.sortedWith(compareBy<ZeroconfWebLink> { !it.reachable }.thenBy { it.url }),
            portChecks = portChecks.sortedWith(compareBy<ZeroconfPortCheck> { it.host }.thenBy { it.port }),
            netbios = netbios,
            smb = smb,
        )
    }

    private fun inspectNetbios(host: String, timeout: Duration, warnings: MutableList<String>): ZeroconfNetbiosHostDetail {
        val responses = runCatching { NetbiosNameServiceClient(timeout = timeout).nodeStatus(host) }.getOrElse { error ->
            warnings += "NetBIOS node status failed for $host: ${error.message ?: error::class.java.simpleName}"
            return ZeroconfNetbiosHostDetail(host = host, responseCount = 0, names = emptyList(), addresses = emptyList(), errors = listOf(error.message ?: "Node status failed"))
        }
        val names = responses.flatMap { response ->
            response.names.map { name ->
                ZeroconfNetbiosNameView(name = name.name, suffix = name.suffix, group = name.group, flags = name.flags)
            }
        }.distinctBy { "${it.name}:${it.suffix}:${it.group}:${it.flags}" }
        val addresses = responses.flatMap { response -> response.addresses.map { it.address } }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
        val errors = responses.mapNotNull { it.error }.distinct()
        return ZeroconfNetbiosHostDetail(
            host = host,
            responseCount = responses.size,
            names = names.sortedWith(compareBy<ZeroconfNetbiosNameView> { it.name }.thenBy { it.suffix }),
            addresses = addresses,
            errors = errors,
        )
    }

    private fun inspectSmb(host: String, timeout: Duration): ZeroconfSmbHostDetail {
        var client: SMBClient? = null
        var connection: com.hierynomus.smbj.connection.Connection? = null
        var session: com.hierynomus.smbj.session.Session? = null
        try {
            val config = SmbConfig.builder()
                .withTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .withSoTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .build()
            client = SMBClient(config)
            connection = runCatching { client.connect(host) }.getOrElse { error ->
                return ZeroconfSmbHostDetail(
                    host = host,
                    attempted = true,
                    error = error.message ?: "SMB connection failed",
                )
            }

            val connectionInfo = connection.connectionInfo
            val dialect = connection.negotiatedProtocol.dialect.name
            val signingRequired = connectionInfo.isServerRequiresSigning()
            val signingEnabled = connectionInfo.isServerSigningEnabled()
            val serverGuid = connectionInfo.serverGuid?.toString()

            val attempts = listOf(
                "anonymous" to AuthenticationContext.anonymous(),
                "guest" to AuthenticationContext.guest(),
            )

            var lastError: String? = null
            for ((mode, auth) in attempts) {
                try {
                    session = connection.authenticate(auth)
                    val shares = enumerateShares(session)
                    return ZeroconfSmbHostDetail(
                        host = host,
                        attempted = true,
                        dialect = dialect,
                        signingRequired = signingRequired,
                        signingEnabled = signingEnabled,
                        encryptionSupported = false,
                        serverGuid = serverGuid,
                        authenticationMode = mode,
                        authenticationStatus = when {
                            session.isAnonymous -> "anonymous"
                            session.isGuest -> "guest"
                            else -> "authenticated"
                        },
                        shares = shares,
                        note = if (shares.isEmpty()) "Connected, but no shares were returned." else null,
                    )
                } catch (error: Throwable) {
                    lastError = error.message ?: error::class.java.simpleName
                } finally {
                    runCatching { session?.logoff() }
                    session = null
                }
            }

            return ZeroconfSmbHostDetail(
                host = host,
                attempted = true,
                dialect = dialect,
                signingRequired = signingRequired,
                signingEnabled = signingEnabled,
                encryptionSupported = false,
                serverGuid = serverGuid,
                authenticationStatus = "denied",
                note = "Anonymous and guest SMB sessions were not accepted.",
                error = lastError,
            )
        } catch (error: Throwable) {
            return ZeroconfSmbHostDetail(
                host = host,
                attempted = true,
                error = error.message ?: error::class.java.simpleName,
            )
        } finally {
            runCatching { session?.logoff() }
            runCatching { connection?.close(true) }
            runCatching { client?.close() }
        }
    }

    private fun enumerateShares(session: com.hierynomus.smbj.session.Session): List<ZeroconfSmbShare> {
        val transport = SMBTransportFactories.SRVSVC.getTransport(session)
        val service = ServerService(transport)
        return service.getShares1()
            .map(::toSmbShare)
            .filterNot { share -> share.name.isBlank() }
            .sortedWith(compareBy<ZeroconfSmbShare> { it.name.lowercase() })
    }

    private fun toSmbShare(share: NetShareInfo1): ZeroconfSmbShare {
        val typeValue = share.type
        val baseType = when (typeValue and 0xFF) {
            0 -> "Disk"
            1 -> "Printer"
            2 -> "Device"
            3 -> "IPC"
            else -> "Unknown"
        }
        val flags = buildList {
            if ((typeValue and 0x80000000.toInt()) != 0) add("Special")
            if ((typeValue and 0x40000000) != 0) add("Temporary")
        }
        val renderedType = if (flags.isEmpty()) baseType else "$baseType (${flags.joinToString(", ")})"
        return ZeroconfSmbShare(
            name = share.netName ?: "",
            type = renderedType,
            comment = share.remark ?: "",
        )
    }

    private fun buildWebCandidates(device: ZeroconfDashboardDevice): List<WebCandidate> {
        val candidates = linkedMapOf<String, WebCandidate>()

        device.locations
            .filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            .forEach { location ->
                candidates[location] = WebCandidate(
                    label = "Advertised URL",
                    url = location,
                    source = "location",
                )
            }

        device.services.forEach { service ->
            val host = normalizeServiceHost(service.target)
                ?: device.hostnames.firstOrNull()
                ?: device.addresses.firstOrNull()
                ?: locationHost(service.location)
            if (host == null) return@forEach
            val port = service.port ?: return@forEach
            val schemes = inferredHttpSchemes(service, port)
            schemes.forEach { scheme ->
                val url = buildUrl(scheme, host, port)
                val label = when (scheme) {
                    "https" -> "HTTPS interface"
                    else -> "HTTP interface"
                }
                candidates[url] = WebCandidate(label = label, url = url, source = "${service.protocol}:${service.type}")
            }
        }

        return candidates.values.toList()
    }

    private fun buildPortCandidates(device: ZeroconfDashboardDevice): List<PortCandidate> {
        val candidates = linkedMapOf<String, PortCandidate>()

        device.services.forEach { service ->
            val port = service.port ?: return@forEach
            val host = normalizeServiceHost(service.target)
                ?: device.addresses.firstOrNull()
                ?: locationHost(service.location)
                ?: device.hostnames.firstOrNull()
                ?: return@forEach
            val key = "$host:$port"
            candidates[key] = PortCandidate(
                host = host,
                port = port,
                label = "${service.protocol} ${service.type.ifBlank { service.name.ifBlank { "service" } }}",
                grabBanner = port in setOf(21, 22, 25, 80, 110, 143),
            )
        }

        val primaryAddress = device.addresses.firstOrNull()
        if (primaryAddress != null && device.protocols.contains("NetBIOS")) {
            listOf(139 to "SMB over NetBIOS", 445 to "SMB").forEach { (port, label) ->
                val key = "$primaryAddress:$port"
                candidates.putIfAbsent(key, PortCandidate(primaryAddress, port, label))
            }
        }

        buildWebCandidates(device).forEach { candidate ->
            val uri = runCatching { URI(candidate.url) }.getOrNull() ?: return@forEach
            val host = uri.host ?: return@forEach
            val port = when {
                uri.port > 0 -> uri.port
                uri.scheme.equals("https", ignoreCase = true) -> 443
                else -> 80
            }
            val key = "$host:$port"
            candidates.putIfAbsent(key, PortCandidate(host, port, "Web interface", grabBanner = port == 80))
        }`

        return candidates.values.toList()
    }

    private fun inferredHttpSchemes(service: ZeroconfDashboardService, port: Int): List<String> {
        val normalizedType = service.type.lowercase()
        return when {
            normalizedType.contains("_ipps") || normalizedType.contains("https") || port == 443 || port == 8443 -> listOf("https")
            normalizedType.contains("_ipp") || normalizedType.contains("_http") || port in setOf(80, 81, 631, 8008, 8080) -> listOf("http")
            port in setOf(443, 8443) -> listOf("https")
            port in setOf(80, 81, 8008, 8080) -> listOf("http")
            else -> emptyList()
        }
    }

    private fun buildUrl(scheme: String, host: String, port: Int): String {
        val defaultPort = (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
        return if (defaultPort) "$scheme://$host/" else "$scheme://$host:$port/"
    }

    private fun normalizeServiceHost(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (raw.startsWith("uuid:", ignoreCase = true)) return null
        if (raw.startsWith("urn:", ignoreCase = true)) return null
        return raw.removePrefix("http://").removePrefix("https://").substringBefore('/').substringBefore(':').ifBlank { null }
    }

    private fun locationHost(value: String): String? =
        runCatching { URI(value).host }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun isIpv4Address(value: String): Boolean =
        runCatching { InetAddress.getByName(value) }.getOrNull() is Inet4Address

    private data class WebCandidate(
        val label: String,
        val url: String,
        val source: String,
    )

    private data class PortCandidate(
        val host: String,
        val port: Int,
        val label: String,
        val grabBanner: Boolean = false,
    )
}
