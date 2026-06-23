package no.norrs.nortools.lib.zeroconf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WsDiscoveryTest {
    @Test
    fun `builds ws-discovery probe envelope`() {
        val payload = WsDiscoverySoapCodec.buildProbe(
            types = "dn:NetworkVideoTransmitter",
            scopes = "onvif://www.onvif.org/type/video_encoder",
            messageId = "urn:uuid:test-probe",
        ).toString(Charsets.UTF_8)

        assertTrue(payload.contains("<w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>"))
        assertTrue(payload.contains("<w:MessageID>urn:uuid:test-probe</w:MessageID>"))
        assertTrue(payload.contains("<d:Types>dn:NetworkVideoTransmitter</d:Types>"))
        assertTrue(payload.contains("<d:Scopes>onvif://www.onvif.org/type/video_encoder</d:Scopes>"))
    }

    @Test
    fun `parses probe match message`() {
        val payload = """
            <?xml version="1.0" encoding="UTF-8"?>
            <e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope"
              xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing"
              xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery">
              <e:Header>
                <a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/ProbeMatches</a:Action>
                <a:MessageID>urn:uuid:response-1</a:MessageID>
                <a:RelatesTo>urn:uuid:probe-1</a:RelatesTo>
              </e:Header>
              <e:Body>
                <d:ProbeMatches>
                  <d:ProbeMatch>
                    <a:EndpointReference>
                      <a:Address>urn:uuid:device-1</a:Address>
                    </a:EndpointReference>
                    <d:Types>wsdp:Device pub:Computer</d:Types>
                    <d:Scopes>ldap:///uuid/1234</d:Scopes>
                    <d:XAddrs>http://192.168.1.20:5357/uuid/device-1</d:XAddrs>
                    <d:MetadataVersion>2</d:MetadataVersion>
                  </d:ProbeMatch>
                </d:ProbeMatches>
              </e:Body>
            </e:Envelope>
        """.trimIndent().toByteArray()

        val message = WsDiscoverySoapCodec.parseMessage(payload)

        assertEquals("ProbeMatches", message.messageType)
        assertEquals("urn:uuid:response-1", message.messageId)
        assertEquals("urn:uuid:probe-1", message.relatesTo)
        assertEquals("urn:uuid:device-1", message.endpointReference)
        assertEquals("wsdp:Device pub:Computer", message.types)
        assertEquals("ldap:///uuid/1234", message.scopes)
        assertEquals("http://192.168.1.20:5357/uuid/device-1", message.xAddrs)
        assertEquals("2", message.metadataVersion)
    }

    @Test
    fun `parses hello announcement`() {
        val payload = """
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
              xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing"
              xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery">
              <s:Header>
                <a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Hello</a:Action>
                <a:MessageID>urn:uuid:hello-1</a:MessageID>
              </s:Header>
              <s:Body>
                <d:Hello>
                  <a:EndpointReference><a:Address>urn:uuid:printer-1</a:Address></a:EndpointReference>
                  <d:Types>wsdp:Device</d:Types>
                  <d:XAddrs>http://printer.local:5357/wsd</d:XAddrs>
                </d:Hello>
              </s:Body>
            </s:Envelope>
        """.trimIndent().toByteArray()

        val message = WsDiscoverySoapCodec.parseMessage(payload)

        assertEquals("Hello", message.messageType)
        assertEquals("urn:uuid:printer-1", message.endpointReference)
        assertEquals("wsdp:Device", message.types)
        assertEquals("http://printer.local:5357/wsd", message.xAddrs)
    }
}
