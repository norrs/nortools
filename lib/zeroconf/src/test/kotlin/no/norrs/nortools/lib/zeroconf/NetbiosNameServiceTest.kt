package no.norrs.nortools.lib.zeroconf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetbiosNameServiceTest {
    @Test
    fun `encodes and decodes netbios name with suffix`() {
        val encoded = NetbiosCodec.encodeName("MYPC", 0x20)
        val (decoded, nextOffset) = NetbiosCodec.decodeEncodedName(encoded, 0)

        assertEquals("MYPC<20>", decoded)
        assertEquals(encoded.size, nextOffset)
        assertEquals(34, encoded.size)
    }

    @Test
    fun `builds NB name query packet`() {
        val packet = NetbiosCodec.buildQuestion(0x1234, "MYPC", 0x20, NetbiosQuestionType.NB)

        assertEquals(0x12, packet[0].toInt() and 0xff)
        assertEquals(0x34, packet[1].toInt() and 0xff)
        assertEquals(1, packet[5].toInt() and 0xff)
        assertEquals(0x00, packet[packet.size - 4].toInt() and 0xff)
        assertEquals(0x20, packet[packet.size - 3].toInt() and 0xff)
        assertEquals(0x00, packet[packet.size - 2].toInt() and 0xff)
        assertEquals(0x01, packet[packet.size - 1].toInt() and 0xff)
    }

    @Test
    fun `parses NB address response`() {
        val question = NetbiosCodec.encodeName("MYPC", 0x20)
        val response = byteArrayOf(
            0x12, 0x34,
            0x85.toByte(), 0x00,
            0x00, 0x00,
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x00,
            0xc0.toByte(), 0x0c,
            0x00, 0x20,
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x78,
            0x00, 0x06,
            0x00, 0x00,
            192.toByte(), 168.toByte(), 1, 25,
        )
        val packet = byteArrayOf(
            0x12, 0x34,
            0x85.toByte(), 0x00,
            0x00, 0x01,
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x00,
        ) + question + byteArrayOf(0x00, 0x20, 0x00, 0x01) + response.copyOfRange(12, response.size)

        val parsed = NetbiosCodec.parseResponse(packet, "192.168.1.25", 137)

        assertTrue(parsed.response)
        assertEquals(1, parsed.answerCount)
        assertEquals("192.168.1.25", parsed.sourceAddress)
        assertEquals("MYPC", parsed.addresses.single().name)
        assertEquals(0x20, parsed.addresses.single().suffix)
        assertEquals("192.168.1.25", parsed.addresses.single().address)
        assertFalse(parsed.addresses.single().group)
    }

    @Test
    fun `parses node status names`() {
        val owner = NetbiosCodec.encodeName("*", 0x00)
        val nameBytes = "MYPC".padEnd(15, ' ').toByteArray(Charsets.US_ASCII)
        val packet = byteArrayOf(
            0xab.toByte(), 0xcd.toByte(),
            0x85.toByte(), 0x00,
            0x00, 0x00,
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x00,
        ) + owner + byteArrayOf(
            0x00, 0x21,
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x78,
            0x00, 0x13,
            0x01,
        ) + nameBytes + byteArrayOf(
            0x00,
            0x00, 0x00,
        )

        val parsed = NetbiosCodec.parseResponse(packet)

        assertEquals(1, parsed.names.size)
        assertEquals("MYPC", parsed.names.single().name)
        assertEquals(0x00, parsed.names.single().suffix)
    }
}
