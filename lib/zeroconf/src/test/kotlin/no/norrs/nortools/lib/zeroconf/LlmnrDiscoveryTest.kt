package no.norrs.nortools.lib.zeroconf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LlmnrDiscoveryTest {
    @Test
    fun `builds llmnr dns query`() {
        val payload = LlmnrCodec.buildQuery("printer.local", LlmnrCodec.typeCode("A"))
        val records = LlmnrCodec.parseRecords(payload)

        assertTrue(payload.isNotEmpty())
        assertTrue(records.isEmpty())
        assertEquals("printer.local.", LlmnrCodec.normalizeName("printer.local"))
    }

    @Test
    fun `parses llmnr a response records`() {
        val payload = byteArrayOf(
            0x12, 0x34, 0x80.toByte(), 0x00, 0x00, 0x01, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x00,
            0x07, 'p'.code.toByte(), 'r'.code.toByte(), 'i'.code.toByte(), 'n'.code.toByte(), 't'.code.toByte(), 'e'.code.toByte(), 'r'.code.toByte(),
            0x05, 'l'.code.toByte(), 'o'.code.toByte(), 'c'.code.toByte(), 'a'.code.toByte(), 'l'.code.toByte(),
            0x00, 0x00, 0x01, 0x00, 0x01,
            0xC0.toByte(), 0x0C, 0x00, 0x01, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x78,
            0x00, 0x04, 0xC0.toByte(), 0xA8.toByte(), 0x01, 0x19,
        )

        val records = LlmnrCodec.parseRecords(payload)

        assertEquals(1, records.size)
        assertEquals("printer.local.", records.first().name)
        assertEquals("A", records.first().type)
        assertEquals("192.168.1.25", records.first().data)
        assertEquals(120, records.first().ttl)
    }
}
