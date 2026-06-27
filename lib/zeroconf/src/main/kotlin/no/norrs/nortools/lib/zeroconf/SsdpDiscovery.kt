package no.norrs.nortools.lib.zeroconf

import java.nio.charset.StandardCharsets
import java.time.Duration

data class SsdpMessage(
    val startLine: String,
    val headers: Map<String, String>,
    val isNotify: Boolean,
    val isResponse: Boolean,
    val searchTarget: String?,
    val notificationType: String?,
    val uniqueServiceName: String?,
    val location: String?,
    val server: String?,
    val cacheControl: String?,
    val isDlnaLike: Boolean,
)

data class SsdpResult(
    val protocol: String = "SSDP",
    val mode: String,
    val status: String,
    val searchTarget: String? = null,
    val responseCount: Int,
    val messages: List<SsdpMessage>,
    val observations: List<UdpObservation>,
    val warnings: List<String> = emptyList(),
)

class SsdpClient(
    private val timeout: Duration = Duration.ofSeconds(5),
) {
    fun search(
        searchTarget: String = "ssdp:all",
        bindAddress: String? = null,
        maxPackets: Int = 25,
    ): SsdpResult {
        val payload = SsdpCodec.buildSearch(searchTarget)
        val session = BoundedUdpDiscovery(protocol = "SSDP", timeout = timeout)
            .sendAndReceiveMulticast(
                payload = payload,
                groupAddress = SSDP_IPV4_GROUP,
                groupPort = SSDP_PORT,
                bindAddress = bindAddress,
                maxPackets = maxPackets,
            )
        val messages = session.observations
            .filter { it.direction == UdpDirection.RECEIVED }
            .mapNotNull { observation -> runCatching { SsdpCodec.parseMessage(observation.payload) }.getOrNull() }
        return SsdpResult(
            mode = "search",
            status = if (messages.isEmpty()) "no-responses" else "ok",
            searchTarget = searchTarget,
            responseCount = messages.size,
            messages = messages,
            observations = session.observations,
            warnings = session.warnings,
        )
    }

    fun listen(
        bindAddress: String = "0.0.0.0",
        maxPackets: Int = 25,
    ): SsdpResult {
        val session = BoundedUdpDiscovery(protocol = "SSDP", timeout = timeout)
            .listenMulticast(
                groupAddress = SSDP_IPV4_GROUP,
                bindPort = SSDP_PORT,
                bindAddress = bindAddress,
                maxPackets = maxPackets,
            )
        val messages = session.observations
            .mapNotNull { observation -> runCatching { SsdpCodec.parseMessage(observation.payload) }.getOrNull() }
        return SsdpResult(
            mode = "listen",
            status = if (messages.isEmpty()) "no-responses" else "ok",
            responseCount = messages.size,
            messages = messages,
            observations = session.observations,
            warnings = session.warnings,
        )
    }

    companion object {
        const val SSDP_IPV4_GROUP = "239.255.255.250"
        const val SSDP_PORT = 1900
    }
}

object SsdpCodec {
    fun buildSearch(searchTarget: String): ByteArray {
        val request = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: ${SsdpClient.SSDP_IPV4_GROUP}:${SsdpClient.SSDP_PORT}\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 2\r\n")
            append("ST: $searchTarget\r\n")
            append("\r\n")
        }
        return request.toByteArray(StandardCharsets.UTF_8)
    }

    fun parseMessage(payload: ByteArray): SsdpMessage {
        val text = payload.toString(StandardCharsets.UTF_8)
        val lines = text.split("\r\n")
            .filter { it.isNotBlank() }
        require(lines.isNotEmpty()) { "Empty SSDP payload" }
        val startLine = lines.first()
        val headers = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim().uppercase()
            val value = line.substring(idx + 1).trim()
            headers[name] = value
        }

        val server = headers["SERVER"]
        val usn = headers["USN"]
        val st = headers["ST"]
        val nt = headers["NT"]
        val location = headers["LOCATION"]
        val cacheControl = headers["CACHE-CONTROL"]

        return SsdpMessage(
            startLine = startLine,
            headers = headers,
            isNotify = startLine.startsWith("NOTIFY ", ignoreCase = true),
            isResponse = startLine.startsWith("HTTP/1.1 ", ignoreCase = true),
            searchTarget = st,
            notificationType = nt,
            uniqueServiceName = usn,
            location = location,
            server = server,
            cacheControl = cacheControl,
            isDlnaLike = listOf(server, usn, st, nt)
                .filterNotNull()
                .any { value ->
                    val normalized = value.uppercase()
                    "DLNA" in normalized || "MEDIARENDERER" in normalized || "MEDIASERVER" in normalized
                },
        )
    }
}
