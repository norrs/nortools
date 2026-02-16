package no.norrs.nortools.lib.dns

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DnsResolverWindowsConfigTest {

    @Test
    fun `parses dns servers from windows ipconfig output`() {
        val ipconfig = """
Windows IP Configuration

Ethernet adapter Ethernet:
   Connection-specific DNS Suffix  . : localdomain
   Description . . . . . . . . . . . : Intel(R) Ethernet
   DNS Servers . . . . . . . . . . . : 192.168.50.1
                                       1.1.1.1
   NetBIOS over Tcpip. . . . . . . . : Enabled
""".trimIndent()

        val servers = DnsResolver.parseWindowsIpconfigDnsServers(ipconfig)
        assertEquals(listOf("192.168.50.1", "1.1.1.1"), servers)
    }
}

