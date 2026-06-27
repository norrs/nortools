package no.norrs.nortools.lib.dns

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.xbill.DNS.ARecord
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import org.xbill.DNS.Type
import java.net.InetAddress

class DnsWireCodecTest {
    @Test
    fun `builds query wire with recursion disabled by default`() {
        val packet = DnsWireCodec.buildQueryWire("_services._dns-sd._udp.local", Type.PTR)
        val message = Message(packet)
        val questions = message.getSection(Section.QUESTION)

        assertEquals(1, questions.size)
        assertEquals("_services._dns-sd._udp.local.", questions.single().name.toString())
        assertEquals(Type.PTR, questions.single().type)
        assertFalse(message.header.getFlag(Flags.RD.toInt()))
    }

    @Test
    fun `parses answer records from dns payload`() {
        val message = Message()
        message.header.setFlag(Flags.QR.toInt())
        message.addRecord(
            Record.newRecord(Name.fromString("device.local."), Type.A, DClass.IN),
            Section.QUESTION,
        )
        message.addRecord(
            ARecord(
                Name.fromString("device.local."),
                DClass.IN,
                120,
                InetAddress.getByName("192.168.1.20"),
            ),
            Section.ANSWER,
        )

        val records = DnsWireCodec.parseRecords(message.toWire())

        assertEquals(1, records.size)
        assertEquals("answer", records.single().section)
        assertEquals("device.local.", records.single().name)
        assertEquals("A", records.single().type)
        assertEquals("192.168.1.20", records.single().data)
    }

    @Test
    fun `normalizes names and type codes`() {
        assertEquals("printer.local.", DnsWireCodec.normalizeName("printer.local"))
        assertEquals("printer.local.", DnsWireCodec.normalizeName("printer.local."))
        assertEquals(Type.SRV, DnsWireCodec.typeCode("srv"))
        assertTrue(DnsWireCodec.typeCode("TXT") > 0)
    }
}
