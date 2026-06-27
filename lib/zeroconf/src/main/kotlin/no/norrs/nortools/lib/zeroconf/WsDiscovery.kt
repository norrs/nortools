package no.norrs.nortools.lib.zeroconf

import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

data class WsDiscoveryMessage(
    val messageType: String,
    val action: String?,
    val messageId: String?,
    val relatesTo: String?,
    val endpointReference: String?,
    val types: String?,
    val scopes: String?,
    val xAddrs: String?,
    val metadataVersion: String?,
    val rawXml: String,
)

data class WsDiscoveryResult(
    val protocol: String = "WS-Discovery",
    val mode: String,
    val status: String,
    val probeTypes: String? = null,
    val scopes: String? = null,
    val responseCount: Int,
    val messages: List<WsDiscoveryMessage>,
    val observations: List<UdpObservation>,
    val warnings: List<String> = emptyList(),
)

class WsDiscoveryClient(
    private val timeout: Duration = Duration.ofSeconds(5),
) {
    fun probe(
        types: String? = null,
        scopes: String? = null,
        bindAddress: String? = null,
        maxPackets: Int = 25,
    ): WsDiscoveryResult {
        val payload = WsDiscoverySoapCodec.buildProbe(types = types, scopes = scopes)
        val session = BoundedUdpDiscovery(protocol = "WS-Discovery", timeout = timeout)
            .sendAndReceiveMulticast(
                payload = payload,
                groupAddress = WSD_IPV4_GROUP,
                groupPort = WSD_PORT,
                bindAddress = bindAddress,
                maxPackets = maxPackets,
            )
        val messages = session.observations
            .filter { it.direction == UdpDirection.RECEIVED }
            .mapNotNull { observation -> runCatching { WsDiscoverySoapCodec.parseMessage(observation.payload) }.getOrNull() }
        return WsDiscoveryResult(
            mode = "probe",
            status = if (messages.isEmpty()) "no-responses" else "ok",
            probeTypes = types?.takeIf { it.isNotBlank() },
            scopes = scopes?.takeIf { it.isNotBlank() },
            responseCount = messages.size,
            messages = messages,
            observations = session.observations,
            warnings = session.warnings,
        )
    }

    fun listen(
        bindAddress: String = "0.0.0.0",
        maxPackets: Int = 25,
    ): WsDiscoveryResult {
        val session = BoundedUdpDiscovery(protocol = "WS-Discovery", timeout = timeout)
            .listenMulticast(
                groupAddress = WSD_IPV4_GROUP,
                bindPort = WSD_PORT,
                bindAddress = bindAddress,
                maxPackets = maxPackets,
            )
        val messages = session.observations
            .mapNotNull { observation -> runCatching { WsDiscoverySoapCodec.parseMessage(observation.payload) }.getOrNull() }
        return WsDiscoveryResult(
            mode = "listen",
            status = if (messages.isEmpty()) "no-responses" else "ok",
            responseCount = messages.size,
            messages = messages,
            observations = session.observations,
            warnings = session.warnings,
        )
    }

    companion object {
        const val WSD_IPV4_GROUP = "239.255.255.250"
        const val WSD_PORT = 3702
    }
}

object WsDiscoverySoapCodec {
    fun buildProbe(
        types: String? = null,
        scopes: String? = null,
        messageId: String = "urn:uuid:${UUID.randomUUID()}",
    ): ByteArray {
        val envelope = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append(
                """
                <e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope"
                    xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                    xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery">
                  <e:Header>
                    <w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>
                    <w:MessageID>$messageId</w:MessageID>
                    <w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>
                  </e:Header>
                  <e:Body>
                    <d:Probe>
                """.trimIndent(),
            )
            if (!types.isNullOrBlank()) {
                append("<d:Types>${xmlEscape(types)}</d:Types>")
            }
            if (!scopes.isNullOrBlank()) {
                append("<d:Scopes>${xmlEscape(scopes)}</d:Scopes>")
            }
            append(
                """
                    </d:Probe>
                  </e:Body>
                </e:Envelope>
                """.trimIndent(),
            )
        }
        return envelope.toByteArray(StandardCharsets.UTF_8)
    }

    fun parseMessage(payload: ByteArray): WsDiscoveryMessage {
        val xml = payload.toString(StandardCharsets.UTF_8).trim()
        require(xml.isNotBlank()) { "Empty WS-Discovery payload" }

        val action = extractTag(xml, "Action")
        val messageType = when {
            action?.endsWith("/Hello") == true -> "Hello"
            action?.endsWith("/Bye") == true -> "Bye"
            action?.endsWith("/ProbeMatches") == true -> "ProbeMatches"
            action?.endsWith("/ResolveMatches") == true -> "ResolveMatches"
            action?.endsWith("/Probe") == true -> "Probe"
            action?.endsWith("/Resolve") == true -> "Resolve"
            else -> "SOAP"
        }

        return WsDiscoveryMessage(
            messageType = messageType,
            action = action,
            messageId = extractTag(xml, "MessageID"),
            relatesTo = extractTag(xml, "RelatesTo"),
            endpointReference = extractTag(xml, "Address"),
            types = extractTag(xml, "Types"),
            scopes = extractTag(xml, "Scopes"),
            xAddrs = extractTag(xml, "XAddrs"),
            metadataVersion = extractTag(xml, "MetadataVersion"),
            rawXml = xml,
        )
    }

    private fun extractTag(xml: String, localName: String): String? {
        val regex = Regex(
            pattern = """<(?:(?:\w+):)?$localName\b[^>]*>(.*?)</(?:(?:\w+):)?$localName>""",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        return regex.find(xml)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun xmlEscape(value: String): String =
        buildString(value.length) {
            value.forEach { ch ->
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(ch)
                }
            }
        }
}
