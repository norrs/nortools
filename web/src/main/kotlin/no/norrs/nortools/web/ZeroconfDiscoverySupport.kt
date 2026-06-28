package no.norrs.nortools.web

import no.norrs.nortools.lib.zeroconf.SsdpMessage
import no.norrs.nortools.lib.zeroconf.WsDiscoveryMessage
import no.norrs.nortools.lib.zeroconf.WsDiscoveryMetadata
import java.net.URI

internal fun categoryRank(category: String): Int =
    when (category) {
        "Printer", "Printer / Scanner" -> 90
        "Media", "Smart Home", "Camera", "Router" -> 80
        "Windows / SMB host", "Computer", "Host" -> 70
        "Web service" -> 50
        else -> 10
    }

internal fun cleanLabel(value: String): String =
    value.trim()
        .trim('"')
        .removeSuffix(".")
        .replace("\\032", " ")
        .replace("\\(", "(")
        .replace("\\)", ")")
        .ifBlank { "Unknown device" }

internal fun cleanDnsName(value: String): String = cleanLabel(value)

internal data class ParsedSrv(val port: Int, val target: String)

internal fun parseSrvData(data: String): ParsedSrv? {
    val parts = data.trim().split(Regex("\\s+"))
    if (parts.size < 4) return null
    return ParsedSrv(port = parts[2].toIntOrNull() ?: 0, target = cleanDnsName(parts.drop(3).joinToString(" ")))
}

internal fun parseTxt(data: String): List<Pair<String, String>> {
    val quoted = Regex(""""([^"]*)"""").findAll(data).map { it.groupValues[1] }.toList()
    val tokens = quoted.ifEmpty { data.split(Regex("\\s+")).filter { it.isNotBlank() } }
    return tokens.map { token ->
        val idx = token.indexOf('=')
        if (idx < 0) token to "" else token.substring(0, idx) to token.substring(idx + 1)
    }
}

internal fun serviceTypeFromInstance(instance: String): String {
    val idx = instance.indexOf("._")
    return if (idx >= 0) instance.substring(idx + 1) else instance
}

internal fun serviceInstanceName(instance: String, serviceType: String): String =
    instance.removeSuffix(".$serviceType").removeSuffix(serviceType).trim('.')

internal fun locationHost(location: String): String? =
    runCatching { URI(location).host }.getOrNull()?.takeIf { it.isNotBlank() }

internal fun locationPort(location: String): Int? =
    runCatching { URI(location).port }.getOrNull()?.takeIf { it > 0 }

internal fun splitXAddrs(xAddrs: String?): List<String> =
    xAddrs
        ?.split(Regex("\\s+"))
        ?.map { it.trim() }
        ?.filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
        ?.filter { runCatching { URI(it) }.isSuccess }
        ?.distinct()
        ?: emptyList()

internal fun looksLikeIpAddress(value: String): Boolean {
    val clean = value.trim().trim('[', ']')
    return Regex("""\d{1,3}(?:\.\d{1,3}){3}""").matches(clean) || ':' in clean
}

internal fun extractUuid(value: String): String? =
    Regex("""uuid:[a-zA-Z0-9._-]+""").find(value)?.value?.lowercase()

internal fun inferDnsSdCategory(serviceType: String, instance: String): String {
    val text = "$serviceType $instance".lowercase()
    return when {
        "_smb" in text -> "Windows / SMB host"
        "_device-info" in text -> "Device"
        text.hasAny("_ipp", "_printer") -> "Printer"
        text.hasAny("_scanner", "_uscan") -> "Printer / Scanner"
        text.hasAny("_airplay", "_raop", "_spotify", "_googlecast", "_mediaremotetv", "_companion-link") -> "Media"
        text.hasAny("_hap", "_homekit") -> "Smart Home"
        "_hue" in text -> "Smart Home"
        text.hasAny("_ssh", "_sftp-ssh", "_rfb") -> "Remote access"
        "_afpovertcp" in text -> "File sharing"
        "_workstation" in text -> "Host"
        "_http" in text -> "Web service"
        else -> "DNS-SD service"
    }
}

internal fun dnsSdServiceInfo(type: String): Pair<String, String> {
    val normalized = type.lowercase().removeSuffix(".")
    return when {
        normalized == "_smb._tcp.local" -> "SMB File Sharing" to "Samba or SMB/CIFS file sharing advertised through DNS-SD."
        normalized == "_device-info._tcp.local" -> "Device Info" to "DNS-SD metadata record with model and device-class hints."
        normalized == "_ipp._tcp.local" -> "IPP Printer" to "Internet Printing Protocol. AirPrint and Mopria printers commonly expose queues here."
        normalized == "_ipps._tcp.local" -> "Secure IPP Printer" to "Encrypted IPP printing over TLS."
        normalized == "_printer._tcp.local" -> "LPD Printer" to "Legacy printer service discovery."
        normalized == "_scanner._tcp.local" || normalized == "_uscan._tcp.local" -> "Scanner" to "Scanner discovery, often paired with multifunction printers."
        normalized == "_http._tcp.local" -> "HTTP Service" to "Embedded web interface or device administration endpoint."
        normalized == "_http-alt._tcp.local" -> "Alternate HTTP Service" to "HTTP service on a non-standard port."
        normalized == "_ssh._tcp.local" -> "SSH" to "Secure Shell remote login or administration endpoint."
        normalized == "_sftp-ssh._tcp.local" -> "SFTP" to "SSH File Transfer Protocol endpoint."
        normalized == "_rfb._tcp.local" -> "VNC Remote Desktop" to "Remote Framebuffer service, commonly used by VNC remote desktop servers."
        normalized == "_afpovertcp._tcp.local" -> "Apple File Sharing" to "Apple Filing Protocol file sharing over TCP."
        normalized == "_workstation._tcp.local" -> "Workstation" to "Generic host identity advertised by Avahi and similar mDNS responders."
        normalized == "_airplay._tcp.local" -> "AirPlay" to "Apple media playback or display target."
        normalized == "_raop._tcp.local" -> "AirPlay Audio" to "Remote Audio Output Protocol used by AirPlay speakers and receivers."
        normalized == "_companion-link._tcp.local" -> "Apple Companion Link" to "Apple continuity, remote control, and device companion service."
        normalized == "_mediaremotetv._tcp.local" -> "Apple TV Remote" to "Apple TV media remote control endpoint."
        normalized == "_sleep-proxy._udp.local" -> "Sleep Proxy" to "Bonjour Sleep Proxy wake-on-demand service."
        normalized == "_spotify-connect._tcp.local" -> "Spotify Connect" to "Spotify playback target."
        normalized == "_googlecast._tcp.local" -> "Google Cast" to "Chromecast or Google Cast media receiver."
        normalized == "_hap._tcp.local" -> "HomeKit" to "Apple HomeKit accessory service."
        normalized == "_hue._tcp.local" -> "Hue Bridge" to "Philips Hue bridge discovery."
        else -> type to "DNS-SD service type advertised on the local link."
    }
}

internal fun mdnsSeedServiceTypes(): List<String> =
    listOf("_smb._tcp.local", "_device-info._tcp.local", "_http._tcp.local", "_http-alt._tcp.local", "_ipp._tcp.local", "_ipps._tcp.local")

internal fun upnpServiceInfo(type: String): Pair<String, String> {
    val normalized = type.lowercase()
    return when {
        "mediarenderer" in normalized -> "UPnP Media Renderer" to "DLNA/UPnP playback target such as a receiver, TV, or speaker."
        "mediaserver" in normalized -> "UPnP Media Server" to "DLNA/UPnP content library source."
        "internetgatewaydevice" in normalized -> "Internet Gateway Device" to "Router or gateway service exposed through UPnP IGD."
        "rootdevice" in normalized -> "UPnP Root Device" to "Top-level UPnP device advertisement."
        else -> type.ifBlank { "UPnP device" } to "UPnP/SSDP advertised device or service."
    }
}

internal fun wsdTypeInfo(type: String): Pair<String, String> {
    val normalized = type.lowercase()
    return when {
        "print" in normalized -> "WSD Printer" to "Windows Web Services for Devices printer endpoint."
        "scanner" in normalized || "scan" in normalized -> "WSD Scanner" to "Windows Web Services for Devices scanner endpoint."
        "computer" in normalized -> "Windows Computer" to "Computer advertised through WS-Discovery."
        "networkvideotransmitter" in normalized || "onvif" in normalized -> "ONVIF Camera" to "Network camera or video device using WS-Discovery."
        else -> type.ifBlank { "WSD device" } to "SOAP-over-UDP WS-Discovery device type."
    }
}

internal fun inferSsdpCategory(message: SsdpMessage): String {
    val text = listOf(message.notificationType, message.searchTarget, message.server, message.uniqueServiceName)
        .filterNotNull()
        .joinToString(" ")
        .lowercase()
    return when {
        text.hasAny("mediarenderer", "mediaserver", "dlna") -> "Media"
        text.hasAny("printer", "scanner") -> "Printer / Scanner"
        text.hasAny("hue", "bridge") -> "Smart Home"
        text.hasAny("internetgatewaydevice", "wanipconnection") -> "Router"
        else -> "UPnP device"
    }
}

internal fun inferWsdCategory(message: WsDiscoveryMessage, metadata: WsDiscoveryMetadata? = null): String {
    val text = listOf(message.types, message.scopes, message.xAddrs, metadata?.computerName, metadata?.friendlyName)
        .filterNotNull()
        .joinToString(" ")
        .lowercase()
    return when {
        text.hasAny("print", "scanner") -> "Printer / Scanner"
        text.hasAny("camera", "onvif") -> "Camera"
        "computer" in text -> "Computer"
        else -> "WSD device"
    }
}

private fun String.hasAny(vararg tokens: String): Boolean = tokens.any { it in this }
