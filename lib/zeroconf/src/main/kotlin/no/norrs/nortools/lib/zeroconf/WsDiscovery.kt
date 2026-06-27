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

data class WsDiscoveryMetadata(
    val friendlyName: String? = null,
    val manufacturer: String? = null,
    val modelName: String? = null,
    val modelNumber: String? = null,
    val serialNumber: String? = null,
    val presentationUrl: String? = null,
    val computerName: String? = null,
    val workgroup: String? = null,
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
        ipFamily: IpFamily = IpFamily.IPV4,
        bindAddress: String? = null,
        maxPackets: Int = 25,
    ): WsDiscoveryResult {
        val payload = WsDiscoverySoapCodec.buildProbe(types = types, scopes = scopes)
        val sessions = multicastFamilies(ipFamily).map { family ->
            BoundedUdpDiscovery(protocol = "WS-Discovery", timeout = timeout).sendAndReceiveMulticast(
                payload = payload,
                groupAddress = groupFor(family),
                groupPort = WSD_PORT,
                bindAddress = bindAddressForFamily(bindAddress, family),
                bindPort = 0,
                maxPackets = maxPackets,
                ipFamily = family,
            )
        }
        val observations = sessions.flatMap { it.observations }
        val warnings = sessions.flatMap { it.warnings }
        val messages = observations
            .filter { it.direction == UdpDirection.RECEIVED }
            .mapNotNull { observation -> runCatching { WsDiscoverySoapCodec.parseMessage(observation.payload) }.getOrNull() }
        return WsDiscoveryResult(
            mode = "probe",
            status = if (messages.isEmpty()) "no-responses" else "ok",
            probeTypes = types?.takeIf { it.isNotBlank() },
            scopes = scopes?.takeIf { it.isNotBlank() },
            responseCount = messages.size,
            messages = messages,
            observations = observations,
            warnings = warnings,
        )
    }

    fun resolve(
        endpointReference: String,
        ipFamily: IpFamily = IpFamily.IPV4,
        bindAddress: String? = null,
        maxPackets: Int = 25,
    ): WsDiscoveryResult {
        val payload = WsDiscoverySoapCodec.buildResolve(endpointReference = endpointReference)
        val sessions = multicastFamilies(ipFamily).map { family ->
            BoundedUdpDiscovery(protocol = "WS-Discovery", timeout = timeout).sendAndReceiveMulticast(
                payload = payload,
                groupAddress = groupFor(family),
                groupPort = WSD_PORT,
                bindAddress = bindAddressForFamily(bindAddress, family),
                bindPort = 0,
                maxPackets = maxPackets,
                ipFamily = family,
            )
        }
        val observations = sessions.flatMap { it.observations }
        val warnings = sessions.flatMap { it.warnings }
        val messages = observations
            .filter { it.direction == UdpDirection.RECEIVED }
            .mapNotNull { observation -> runCatching { WsDiscoverySoapCodec.parseMessage(observation.payload) }.getOrNull() }
        return WsDiscoveryResult(
            mode = "resolve",
            status = if (messages.isEmpty()) "no-responses" else "ok",
            responseCount = messages.size,
            messages = messages,
            observations = observations,
            warnings = warnings,
        )
    }

    fun listen(
        bindAddress: String = "0.0.0.0",
        ipFamily: IpFamily = IpFamily.IPV4,
        maxPackets: Int = 25,
    ): WsDiscoveryResult {
        val sessions = multicastFamilies(ipFamily).map { family ->
            BoundedUdpDiscovery(protocol = "WS-Discovery", timeout = timeout).listenMulticast(
                groupAddress = groupFor(family),
                bindPort = WSD_PORT,
                bindAddress = bindAddressForFamily(bindAddress, family),
                maxPackets = maxPackets,
                ipFamily = family,
            )
        }
        val observations = sessions.flatMap { it.observations }
        val warnings = sessions.flatMap { it.warnings }
        val messages = observations
            .mapNotNull { observation -> runCatching { WsDiscoverySoapCodec.parseMessage(observation.payload) }.getOrNull() }
        return WsDiscoveryResult(
            mode = "listen",
            status = if (messages.isEmpty()) "no-responses" else "ok",
            responseCount = messages.size,
            messages = messages,
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
            IpFamily.IPV4 -> WSD_IPV4_GROUP
            IpFamily.IPV6 -> WSD_IPV6_GROUP
            IpFamily.BOTH -> error("Unexpected BOTH family")
        }

    private fun bindAddressForFamily(bindAddress: String?, ipFamily: IpFamily): String? {
        if (bindAddress.isNullOrBlank() || bindAddress == "0.0.0.0") return null
        if (ipFamily == IpFamily.IPV6 && !bindAddress.contains(':')) return null
        if (ipFamily == IpFamily.IPV4 && bindAddress.contains(':')) return null
        return bindAddress
    }

    companion object {
        const val WSD_IPV4_GROUP = "239.255.255.250"
        const val WSD_IPV6_GROUP = "ff02::c"
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

    fun buildResolve(
        endpointReference: String,
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
                    <w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Resolve</w:Action>
                    <w:MessageID>$messageId</w:MessageID>
                    <w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>
                  </e:Header>
                  <e:Body>
                    <d:Resolve>
                      <w:EndpointReference>
                        <w:Address>${xmlEscape(endpointReference)}</w:Address>
                      </w:EndpointReference>
                    </d:Resolve>
                  </e:Body>
                </e:Envelope>
                """.trimIndent(),
            )
        }
        return envelope.toByteArray(StandardCharsets.UTF_8)
    }

    fun buildGet(
        messageId: String = "urn:uuid:${UUID.randomUUID()}",
    ): ByteArray {
        val envelope = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append(
                """
                <e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope"
                    xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                    xmlns:x="http://schemas.xmlsoap.org/ws/2004/09/transfer">
                  <e:Header>
                    <w:Action>http://schemas.xmlsoap.org/ws/2004/09/transfer/Get</w:Action>
                    <w:MessageID>$messageId</w:MessageID>
                    <w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>
                  </e:Header>
                  <e:Body>
                    <x:Get />
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

    fun parseMetadata(payload: ByteArray): WsDiscoveryMetadata {
        val xml = payload.toString(StandardCharsets.UTF_8).trim()
        require(xml.isNotBlank()) { "Empty WS-Discovery metadata payload" }
        val scopes = extractAllTags(xml, "Scopes").joinToString(" ")
        val computerName = extractTag(xml, "Computer") ?: Regex("""/Computer:([^/\s<]+)""", RegexOption.IGNORE_CASE)
            .find(scopes)
            ?.groupValues
            ?.getOrNull(1)
        val workgroup = Regex("""/Workgroup:([^/\s<]+)""", RegexOption.IGNORE_CASE)
            .find(scopes)
            ?.groupValues
            ?.getOrNull(1)
        return WsDiscoveryMetadata(
            friendlyName = extractTag(xml, "FriendlyName"),
            manufacturer = extractTag(xml, "Manufacturer"),
            modelName = extractTag(xml, "ModelName"),
            modelNumber = extractTag(xml, "ModelNumber"),
            serialNumber = extractTag(xml, "SerialNumber"),
            presentationUrl = extractTag(xml, "PresentationUrl") ?: extractTag(xml, "PresentationURL"),
            computerName = computerName,
            workgroup = workgroup,
            rawXml = xml,
        )
    }

    private fun extractTag(xml: String, localName: String): String? {
        return extractAllTags(xml, localName).firstOrNull()
    }

    private fun extractAllTags(xml: String, localName: String): List<String> {
        val regex = Regex(
            pattern = """<(?:(?:\w+):)?$localName\b[^>]*>(.*?)</(?:(?:\w+):)?$localName>""",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        return regex.findAll(xml)
            .mapNotNull { match ->
                match.groupValues
                    .getOrNull(1)
                    ?.replace(Regex("""\s+"""), " ")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
            .toList()
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
