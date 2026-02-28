package no.norrs.nortools.tools.whois.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WhoisClientTest {
    @Test
    fun `detects explicit referral server`() {
        val response = """
            NetRange: 193.0.0.0 - 193.255.255.255
            ReferralServer: whois://whois.ripe.net
        """.trimIndent()

        val next = WhoisClient.findReferralServer(response, "whois.arin.net")

        assertEquals("whois.ripe.net", next)
    }

    @Test
    fun `infers ripe referral from arin rir hints`() {
        val response = """
            NetType: Allocated to RIPE NCC
            OrgId: RIPE
        """.trimIndent()

        val next = WhoisClient.findReferralServer(response, "whois.arin.net")

        assertEquals("whois.ripe.net", next)
    }

    @Test
    fun `does not self refer`() {
        val response = "ReferralServer: whois://whois.arin.net"

        val next = WhoisClient.findReferralServer(response, "whois.arin.net")

        assertNull(next)
    }

    @Test
    fun `parses regular and norid style key value lines`() {
        val response = """
            Domain Name................: example.no
            Name Server Handle.........: DNSS3211H-NORID
            Name Server Handle.........: DNSS3212H-NORID
            Registrar: Example Registrar
        """.trimIndent()

        val fields = WhoisClient.parseWhoisFields(response)

        assertEquals("example.no", fields["Domain Name"])
        assertEquals("DNSS3211H-NORID, DNSS3212H-NORID", fields["Name Server Handle"])
        assertEquals("Example Registrar", fields["Registrar"])
    }

    @Test
    fun `maps no tld to norid whois`() {
        assertEquals("whois.norid.no", WhoisClient.determineWhoisServer("example.no"))
    }

    @Test
    fun `maps ipv4 query to arin whois`() {
        assertEquals("whois.arin.net", WhoisClient.determineWhoisServer("193.0.0.1"))
    }

    @Test
    fun `adds norid disclaimer fields for no domains`() {
        val result = WhoisResult(
            query = "example.no",
            initialServer = "whois.norid.no",
            hops = listOf(
                WhoisHop(
                    server = "whois.norid.no",
                    query = "example.no",
                    fields = mapOf("Domain Name" to "example.no"),
                    raw = "Domain Name: example.no",
                ),
            ),
        )

        val fields = result.mergedFields

        assertTrue(fields["Disclaimer"]!!.contains("Norid AS holds the copyright"))
        assertEquals("https://www.norid.no/en/domeneoppslag/vilkar", fields["Terms URL"])
    }

    @Test
    fun `appends norid notice to raw output for no domains`() {
        val result = WhoisResult(
            query = "example.no",
            initialServer = "whois.norid.no",
            hops = listOf(
                WhoisHop(
                    server = "whois.norid.no",
                    query = "example.no",
                    fields = emptyMap(),
                    raw = "Domain Name: example.no",
                ),
            ),
        )

        val raw = result.combinedRaw()

        assertTrue(raw.contains("# Norid Notice"))
        assertTrue(raw.contains("https://www.norid.no/en/domeneoppslag/vilkar"))
    }

    @Test
    fun `normalizes terms field variants`() {
        val response = """
            Terms of service: First variant
            Terms and Conditions: Second variant
            Whois Terms: Third variant
        """.trimIndent()

        val fields = WhoisClient.parseWhoisFields(response)

        assertEquals(
            "First variant, Second variant, Third variant",
            fields["Terms of Use"],
        )
    }

    @Test
    fun `filters registrar disclaimer boilerplate while keeping useful fields`() {
        val response = """
            Domain Name: example.org
            Registrar: Example Registrar, Inc.
            Notice: This Whois database is provided for information purposes only and is designed to assist persons in obtaining information related to domain name registration records.
            Terms of Use: By submitting this query, you agree to abide by these terms and conditions. This data is protected by copyright and may not be used for commercial use or targeted marketing.
            URL of the ICANN Whois Data Problem Reporting System: http://wdprs.internic.net/
            Name Server: NS1.EXAMPLE.ORG
        """.trimIndent()

        val fields = WhoisClient.parseWhoisFields(response)

        assertEquals("example.org", fields["Domain Name"])
        assertEquals("Example Registrar, Inc.", fields["Registrar"])
        assertEquals("NS1.EXAMPLE.ORG", fields["Name Server"])
        assertNull(fields["Notice"])
        assertNull(fields["Terms of Use"])
        assertNull(fields["URL of the ICANN Whois Data Problem Reporting System"])
    }
}
