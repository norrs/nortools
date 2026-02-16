package no.norrs.nortools.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NetworkTraceParsingTest {

    @Test
    fun `parses Windows tracert output with host and ip`() {
        val lines = listOf(
            "Tracing route to one.one.one.one [1.1.1.1]",
            "over a maximum of 4 hops:",
            "  1    <1 ms    <1 ms    <1 ms  unifi.localdomain [192.168.50.1]",
            "  2     1 ms    <1 ms    <1 ms  192.168.67.1",
            "  3     9 ms     6 ms     6 ms  188.113.104.1",
            "  4     *        *        *     Request timed out.",
            "Trace complete.",
        )

        val hops = parseTraceHopsFromOutput(lines, isWindows = true)
        assertEquals(4, hops.size)

        assertEquals("1", hops[0].hop)
        assertEquals("unifi.localdomain", hops[0].host)
        assertEquals("192.168.50.1", hops[0].ip)
        assertEquals("<1 ms <1 ms <1 ms", hops[0].rtt)

        assertEquals("2", hops[1].hop)
        assertEquals("192.168.67.1", hops[1].host)
        assertEquals("192.168.67.1", hops[1].ip)
        assertEquals("1 ms <1 ms <1 ms", hops[1].rtt)

        assertEquals("*", hops[3].host)
        assertEquals("*", hops[3].ip)
        assertEquals("* * *", hops[3].rtt)
    }

    @Test
    fun `parses Linux traceroute output with ip and timeout`() {
        val lines = listOf(
            "traceroute to 1.1.1.1 (1.1.1.1), 4 hops max, 60 byte packets",
            " 1  192.168.50.1  1.123 ms",
            " 2  10.10.0.1  2.456 ms",
            " 3  * * *",
            " 4  1.1.1.1  15.789 ms",
        )

        val hops = parseTraceHopsFromOutput(lines, isWindows = false)
        assertEquals(4, hops.size)

        assertEquals("1", hops[0].hop)
        assertEquals("192.168.50.1", hops[0].host)
        assertEquals("192.168.50.1", hops[0].ip)
        assertEquals("1.123 ms", hops[0].rtt)

        assertEquals("2", hops[1].hop)
        assertEquals("10.10.0.1", hops[1].host)
        assertEquals("10.10.0.1", hops[1].ip)
        assertEquals("2.456 ms", hops[1].rtt)

        assertEquals("3", hops[2].hop)
        assertEquals("*", hops[2].host)
        assertEquals("*", hops[2].ip)
        assertEquals("* * *", hops[2].rtt)

        assertEquals("4", hops[3].hop)
        assertEquals("1.1.1.1", hops[3].host)
        assertEquals("1.1.1.1", hops[3].ip)
        assertEquals("15.789 ms", hops[3].rtt)
    }

    @Test
    fun `parses average rtt from Windows style timings`() {
        val avg = parseAverageRttMs("1 ms <1 ms 2 ms")
        assertNotNull(avg)
        assertEquals(1.3333333333333333, avg!!)
    }

    @Test
    fun `returns null rtt when no timing present`() {
        val avg = parseAverageRttMs("* * *")
        assertNull(avg)
    }
}
