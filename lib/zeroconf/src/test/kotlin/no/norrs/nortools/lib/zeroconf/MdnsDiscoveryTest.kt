package no.norrs.nortools.lib.zeroconf

import org.junit.jupiter.api.Assertions.assertEquals
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

class MdnsDiscoveryTest {
    @Test
    fun `builds mdns query without recursion desired`() {
        val packet = MdnsCodec.buildQuery("_services._dns-sd._udp.local", Type.PTR)
        val message = Message(packet)
        val questions = message.getSection(Section.QUESTION)

        assertEquals(1, questions.size)
        assertEquals("_services._dns-sd._udp.local.", questions.single().name.toString())
        assertEquals(Type.PTR, questions.single().type)
        assertEquals(false, message.header.getFlag(Flags.RD.toInt()))
    }

    @Test
    fun `parses mdns response records`() {
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

        val records = MdnsCodec.parseRecords(message.toWire())

        assertEquals(1, records.size)
        assertEquals("answer", records.single().section)
        assertEquals("device.local.", records.single().name)
        assertEquals("A", records.single().type)
        assertEquals("192.168.1.20", records.single().data)
    }

    @Test
    fun `normalizes names and record types`() {
        assertEquals("printer.local.", MdnsCodec.normalizeName("printer.local"))
        assertEquals("printer.local.", MdnsCodec.normalizeName("printer.local."))
        assertEquals(Type.SRV, MdnsCodec.typeCode("srv"))
        assertTrue(MdnsCodec.typeCode("TXT") > 0)
    }
}
