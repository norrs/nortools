package no.norrs.nortools.lib.zeroconf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SsdpDiscoveryTest {
    @Test
    fun `builds m-search request`() {
        val payload = SsdpCodec.buildSearch("ssdp:all").toString(Charsets.UTF_8)

        assertTrue(payload.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue("HOST: 239.255.255.250:1900\r\n" in payload)
        assertTrue("ST: ssdp:all\r\n" in payload)
        assertTrue(payload.endsWith("\r\n\r\n"))
    }

    @Test
    fun `parses ssdp response headers`() {
        val payload = """
            HTTP/1.1 200 OK
            CACHE-CONTROL: max-age=1800
            LOCATION: http://192.168.50.66:80/description.xml
            SERVER: Linux/5.10 UPnP/1.0 IpBridge/1.65.0
            ST: upnp:rootdevice
            USN: uuid:2f402f80-da50-11e1-9b23-001788102201::upnp:rootdevice
            
        """.trimIndent().replace("\n", "\r\n").toByteArray()

        val message = SsdpCodec.parseMessage(payload)

        assertTrue(message.isResponse)
        assertFalse(message.isNotify)
        assertEquals("upnp:rootdevice", message.searchTarget)
        assertEquals("http://192.168.50.66:80/description.xml", message.location)
        assertTrue(message.uniqueServiceName!!.startsWith("uuid:"))
        assertFalse(message.isDlnaLike)
    }

    @Test
    fun `parses notify and flags dlna like servers`() {
        val payload = """
            NOTIFY * HTTP/1.1
            HOST: 239.255.255.250:1900
            CACHE-CONTROL: max-age=1800
            LOCATION: http://192.168.50.20:60006/dd.xml
            NT: urn:schemas-upnp-org:device:MediaRenderer:1
            NTS: ssdp:alive
            SERVER: Linux/5.15 UPnP/1.0 DLNADOC/1.50
            USN: uuid:renderer-1::urn:schemas-upnp-org:device:MediaRenderer:1
            
        """.trimIndent().replace("\n", "\r\n").toByteArray()

        val message = SsdpCodec.parseMessage(payload)

        assertTrue(message.isNotify)
        assertFalse(message.isResponse)
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", message.notificationType)
        assertTrue(message.isDlnaLike)
    }
}
