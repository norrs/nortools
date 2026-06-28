package no.norrs.nortools.web

import io.javalin.http.Context
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

fun zeroconfDescription(ctx: Context) {
    val rawUrl = ctx.queryParam("url")?.takeIf { it.isNotBlank() }
        ?: return ctx.jsonResult(errorResponse(protocol = "ZeroConf Description", error = "Missing url query parameter"))
    val uri = runCatching { URI(rawUrl) }.getOrElse {
        return ctx.jsonResult(errorResponse(protocol = "ZeroConf Description", error = "Invalid description URL"))
    }
    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
        return ctx.jsonResult(errorResponse(protocol = "ZeroConf Description", error = "Description URL must be HTTP or HTTPS"))
    }

    val connection = runCatching {
        (uri.toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 2_000
            readTimeout = 3_000
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("Accept", "application/xml,text/xml,*/*;q=0.2")
        }
    }.getOrElse {
        return ctx.jsonResult(errorResponse(protocol = "ZeroConf Description", error = "Could not open description URL: ${it.message}"))
    }

    try {
        val statusCode = connection.responseCode
        if (statusCode !in 200..299) {
            return ctx.jsonResult(
                mapOf(
                    "protocol" to "ZeroConf Description",
                    "status" to "http-error",
                    "url" to rawUrl,
                    "httpStatus" to statusCode,
                    "error" to "Description endpoint returned HTTP $statusCode",
                ),
            )
        }
        val bytes = connection.inputStream.use { input -> input.readNBytes(256 * 1024 + 1) }
        if (bytes.size > 256 * 1024) {
            return ctx.jsonResult(
                mapOf(
                    "protocol" to "ZeroConf Description",
                    "status" to "too-large",
                    "url" to rawUrl,
                    "error" to "Description XML exceeded 256 KiB",
                ),
            )
        }
        val description = parseUpnpDescription(bytes)
        ctx.jsonResult(
            mapOf(
                "protocol" to "ZeroConf Description",
                "status" to "ok",
                "url" to rawUrl,
                "httpStatus" to statusCode,
                "contentType" to connection.contentType,
                "description" to description,
            ),
        )
    } catch (error: Exception) {
        ctx.jsonResult(errorResponse(protocol = "ZeroConf Description", error = error.message ?: "Could not fetch description XML"))
    } finally {
        connection.disconnect()
    }
}

private fun parseUpnpDescription(bytes: ByteArray): Map<String, Any?> {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
        runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
        isExpandEntityReferences = false
    }
    val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    val root = document.documentElement
    val device = root.elementsByTagNameAnyNs("device").firstOrNull()
    val icons = device?.elementsByTagNameAnyNs("icon")?.map { icon ->
        mapOf(
            "mimetype" to icon.childText("mimetype"),
            "width" to icon.childText("width"),
            "height" to icon.childText("height"),
            "url" to icon.childText("url"),
        )
    }.orEmpty()
    val services = device?.elementsByTagNameAnyNs("service")?.map { service ->
        mapOf(
            "serviceType" to service.childText("serviceType"),
            "serviceId" to service.childText("serviceId"),
            "controlURL" to service.childText("controlURL"),
            "eventSubURL" to service.childText("eventSubURL"),
            "SCPDURL" to service.childText("SCPDURL"),
        )
    }.orEmpty()

    return mapOf(
        "specVersion" to mapOf(
            "major" to root.elementsByTagNameAnyNs("major").firstOrNull()?.textContent?.trim(),
            "minor" to root.elementsByTagNameAnyNs("minor").firstOrNull()?.textContent?.trim(),
        ),
        "deviceType" to device?.childText("deviceType"),
        "friendlyName" to device?.childText("friendlyName"),
        "manufacturer" to device?.childText("manufacturer"),
        "manufacturerURL" to device?.childText("manufacturerURL"),
        "modelDescription" to device?.childText("modelDescription"),
        "modelName" to device?.childText("modelName"),
        "modelNumber" to device?.childText("modelNumber"),
        "modelURL" to device?.childText("modelURL"),
        "serialNumber" to device?.childText("serialNumber"),
        "UDN" to device?.childText("UDN"),
        "UPC" to device?.childText("UPC"),
        "presentationURL" to device?.childText("presentationURL"),
        "icons" to icons,
        "services" to services,
    )
}

private fun org.w3c.dom.Element.childText(localName: String): String? =
    elementsByTagNameAnyNs(localName).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotBlank() }

private fun org.w3c.dom.Element.elementsByTagNameAnyNs(localName: String): List<org.w3c.dom.Element> {
    val namespaced = getElementsByTagNameNS("*", localName)
    val plain = getElementsByTagName(localName)
    val elements = linkedMapOf<org.w3c.dom.Node, org.w3c.dom.Element>()
    for (idx in 0 until namespaced.length) {
        val node = namespaced.item(idx)
        if (node is org.w3c.dom.Element) elements[node] = node
    }
    for (idx in 0 until plain.length) {
        val node = plain.item(idx)
        if (node is org.w3c.dom.Element) elements[node] = node
    }
    return elements.values.toList()
}
