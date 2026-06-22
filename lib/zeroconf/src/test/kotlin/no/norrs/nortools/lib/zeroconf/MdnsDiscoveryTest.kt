package no.norrs.nortools.lib.zeroconf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.xbill.DNS.Type

class MdnsDiscoveryTest {
    @Test
    fun `normalizes names and record types`() {
        assertEquals("printer.local.", MdnsCodec.normalizeName("printer.local"))
        assertEquals("printer.local.", MdnsCodec.normalizeName("printer.local."))
        assertEquals(Type.SRV, MdnsCodec.typeCode("srv"))
        assertTrue(MdnsCodec.typeCode("TXT") > 0)
    }
}
