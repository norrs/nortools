package no.norrs.nortools.lib.dns

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class DnsModelsTest {

    // --- DnsRecord tests ---

    @Test
    fun `DnsRecord stores all fields correctly`() {
        val record = DnsRecord(name = "example.com", type = "A", ttl = 300L, data = "1.2.3.4")
        assertEquals("example.com", record.name)
        assertEquals("A", record.type)
        assertEquals(300L, record.ttl)
        assertEquals("1.2.3.4", record.data)
    }

    @Test
    fun `DnsRecord equality works for identical records`() {
        val r1 = DnsRecord("example.com", "A", 300L, "1.2.3.4")
        val r2 = DnsRecord("example.com", "A", 300L, "1.2.3.4")
        assertEquals(r1, r2)
        assertEquals(r1.hashCode(), r2.hashCode())
    }

    @Test
    fun `DnsRecord inequality for different data`() {
        val r1 = DnsRecord("example.com", "A", 300L, "1.2.3.4")
        val r2 = DnsRecord("example.com", "A", 300L, "5.6.7.8")
        assertNotEquals(r1, r2)
    }

    @Test
    fun `DnsRecord copy works`() {
        val original = DnsRecord("example.com", "A", 300L, "1.2.3.4")
        val copied = original.copy(ttl = 600L)
        assertEquals(600L, copied.ttl)
        assertEquals("example.com", copied.name)
    }

    @Test
    fun `DnsRecord toString contains all fields`() {
        val record = DnsRecord("example.com", "MX", 3600L, "10 mail.example.com")
        val str = record.toString()
        assertTrue(str.contains("example.com"))
        assertTrue(str.contains("MX"))
        assertTrue(str.contains("3600"))
        assertTrue(str.contains("10 mail.example.com"))
    }

    // --- DnsLookupResult tests ---

    @Test
    fun `DnsLookupResult stores all fields correctly`() {
        val records = listOf(DnsRecord("example.com", "A", 300L, "1.2.3.4"))
        val result = DnsLookupResult(
            name = "example.com",
            type = "A",
            status = "NOERROR",
            records = records,
            isSuccessful = true,
        )
        assertEquals("example.com", result.name)
        assertEquals("A", result.type)
        assertEquals("NOERROR", result.status)
        assertEquals(1, result.records.size)
        assertTrue(result.isSuccessful)
    }

    @Test
    fun `DnsLookupResult with empty records`() {
        val result = DnsLookupResult(
            name = "nonexistent.example.com",
            type = "A",
            status = "NXDOMAIN",
            records = emptyList(),
            isSuccessful = false,
        )
        assertTrue(result.records.isEmpty())
        assertFalse(result.isSuccessful)
        assertEquals("NXDOMAIN", result.status)
    }

    @Test
    fun `DnsLookupResult equality works`() {
        val records = listOf(DnsRecord("example.com", "A", 300L, "1.2.3.4"))
        val r1 = DnsLookupResult("example.com", "A", "NOERROR", records, true)
        val r2 = DnsLookupResult("example.com", "A", "NOERROR", records, true)
        assertEquals(r1, r2)
    }

    @Test
    fun `DnsLookupResult copy works`() {
        val result = DnsLookupResult("example.com", "A", "NOERROR", emptyList(), true)
        val copied = result.copy(isSuccessful = false, status = "SERVFAIL")
        assertFalse(copied.isSuccessful)
        assertEquals("SERVFAIL", copied.status)
        assertEquals("example.com", copied.name)
    }
}

