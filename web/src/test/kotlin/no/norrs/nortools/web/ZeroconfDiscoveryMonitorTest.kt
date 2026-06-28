package no.norrs.nortools.web

import no.norrs.nortools.lib.zeroconf.WsDiscoveryMessage
import no.norrs.nortools.lib.zeroconf.WsDiscoveryMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ZeroconfDiscoveryMonitorTest {
    @Test
    fun `ws-discovery computer metadata is listed in discovered hostnames`() {
        val message = WsDiscoveryMessage(
            messageType = "ProbeMatches",
            action = "http://schemas.xmlsoap.org/ws/2005/04/discovery/ProbeMatches",
            messageId = "urn:uuid:dalaran-response",
            relatesTo = "urn:uuid:dalaran-probe",
            endpointReference = "urn:uuid:dalaran-device",
            types = "wsdp:Device pub:Computer",
            scopes = "ldap:///Workgroup:WORKGROUP ldap:///Computer:dalaran",
            xAddrs = "http://192.168.50.25:3702/uuid/dalaran-device",
            metadataVersion = "2",
            rawXml = "<Envelope><ProbeMatches /></Envelope>",
        )
        val metadata = WsDiscoveryMetadata(
            friendlyName = "dalaran",
            manufacturer = "unknown",
            modelName = "Linux Samba",
            modelNumber = null,
            serialNumber = "0",
            presentationUrl = null,
            computerName = "dalaran",
            workgroup = "WORKGROUP",
            rawXml = "<Envelope><Computer>dalaran</Computer></Envelope>",
        )

        val ingestWsd = ZeroconfDiscoveryMonitor::class.java.getDeclaredMethod(
            "ingestWsd",
            WsDiscoveryMessage::class.java,
            WsDiscoveryMetadata::class.java,
        )
        ingestWsd.isAccessible = true
        ingestWsd.invoke(ZeroconfDiscoveryMonitor, message, metadata)

        val host = ZeroconfDiscoveryMonitor.snapshot().hostnames.firstOrNull { it.hostname == "dalaran" }
        assertTrue(host != null, "Expected dalaran to be present in hostname resolution list")
        assertEquals(listOf("192.168.50.25"), host!!.addresses)
        assertTrue(host.protocols.contains("WS-Discovery"))
    }
}
