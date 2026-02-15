package no.norrs.nortools.tools.util.subnetcalc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for subnet calculation logic.
 * Since the logic is embedded in the Clikt command, we test the core
 * IPv4 math functions directly by reimplementing the pure calculations.
 */
class SubnetCalcTest {

    // Helper: replicates longToIpv4 from SubnetCalcCommand
    private fun longToIpv4(value: Long): String {
        return "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"
    }

    // Helper: replicates IPv4 address to long conversion
    private fun ipv4ToLong(ip: String): Long {
        return ip.split(".").fold(0L) { acc, part -> (acc shl 8) or (part.toLong() and 0xFF) }
    }

    @Test
    fun `longToIpv4 converts zero correctly`() {
        assertEquals("0.0.0.0", longToIpv4(0L))
    }

    @Test
    fun `longToIpv4 converts broadcast address correctly`() {
        assertEquals("255.255.255.255", longToIpv4(0xFFFFFFFFL))
    }

    @Test
    fun `longToIpv4 converts common addresses correctly`() {
        assertEquals("192.168.1.0", longToIpv4(0xC0A80100L))
        assertEquals("10.0.0.1", longToIpv4(0x0A000001L))
        assertEquals("172.16.0.0", longToIpv4(0xAC100000L))
    }

    @Test
    fun `ipv4ToLong roundtrip works`() {
        val addresses = listOf("0.0.0.0", "255.255.255.255", "192.168.1.100", "10.0.0.1")
        for (addr in addresses) {
            assertEquals(addr, longToIpv4(ipv4ToLong(addr)))
        }
    }

    @Test
    fun `subnet mask calculation for slash 24`() {
        val prefix = 24
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        assertEquals("255.255.255.0", longToIpv4(mask))
    }

    @Test
    fun `subnet mask calculation for slash 16`() {
        val prefix = 16
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        assertEquals("255.255.0.0", longToIpv4(mask))
    }

    @Test
    fun `subnet mask calculation for slash 8`() {
        val prefix = 8
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        assertEquals("255.0.0.0", longToIpv4(mask))
    }

    @Test
    fun `subnet mask calculation for slash 32`() {
        val prefix = 32
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        assertEquals("255.255.255.255", longToIpv4(mask))
    }

    @Test
    fun `network address calculation for 192_168_1_100 slash 24`() {
        val ipInt = ipv4ToLong("192.168.1.100")
        val prefix = 24
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val network = ipInt and mask
        assertEquals("192.168.1.0", longToIpv4(network))
    }

    @Test
    fun `broadcast address calculation for 192_168_1_0 slash 24`() {
        val ipInt = ipv4ToLong("192.168.1.0")
        val prefix = 24
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val network = ipInt and mask
        val broadcast = network or mask.inv() and 0xFFFFFFFFL
        assertEquals("192.168.1.255", longToIpv4(broadcast))
    }

    @Test
    fun `total hosts for slash 24 is 254`() {
        val prefix = 24
        val totalHosts = (1L shl (32 - prefix)) - 2
        assertEquals(254L, totalHosts)
    }

    @Test
    fun `total hosts for slash 32 is 1`() {
        val prefix = 32
        val totalHosts = if (prefix <= 30) (1L shl (32 - prefix)) - 2 else if (prefix == 31) 2L else 1L
        assertEquals(1L, totalHosts)
    }

    @Test
    fun `total hosts for slash 31 is 2`() {
        val prefix = 31
        val totalHosts = if (prefix <= 30) (1L shl (32 - prefix)) - 2 else if (prefix == 31) 2L else 1L
        assertEquals(2L, totalHosts)
    }

    @Test
    fun `IP class A detection`() {
        val ipInt = ipv4ToLong("10.0.0.1")
        val ipClass = when {
            ipInt and 0x80000000L == 0L -> "A"
            ipInt and 0xC0000000L == 0x80000000L -> "B"
            ipInt and 0xE0000000L == 0xC0000000L -> "C"
            ipInt and 0xF0000000L == 0xE0000000L -> "D (Multicast)"
            else -> "E (Reserved)"
        }
        assertEquals("A", ipClass)
    }

    @Test
    fun `IP class C detection`() {
        val ipInt = ipv4ToLong("192.168.1.1")
        val ipClass = when {
            ipInt and 0x80000000L == 0L -> "A"
            ipInt and 0xC0000000L == 0x80000000L -> "B"
            ipInt and 0xE0000000L == 0xC0000000L -> "C"
            ipInt and 0xF0000000L == 0xE0000000L -> "D (Multicast)"
            else -> "E (Reserved)"
        }
        assertEquals("C", ipClass)
    }

    @Test
    fun `private range detection for 10_x`() {
        val network = ipv4ToLong("10.0.0.0")
        val isPrivate = network in 0x0A000000L..0x0AFFFFFFL
        assertEquals(true, isPrivate)
    }

    @Test
    fun `private range detection for 192_168_x`() {
        val network = ipv4ToLong("192.168.0.0")
        val isPrivate = network in 0xC0A80000L..0xC0A8FFFFL
        assertEquals(true, isPrivate)
    }
}

