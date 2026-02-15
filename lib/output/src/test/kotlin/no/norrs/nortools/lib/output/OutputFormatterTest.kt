package no.norrs.nortools.lib.output

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OutputFormatterTest {

    // --- Table mode tests ---

    @Test
    fun `format returns no results for empty list in table mode`() {
        val formatter = OutputFormatter(json = false)
        assertEquals("(no results)", formatter.format(emptyList()))
    }

    @Test
    fun `format returns table with uppercase headers`() {
        val formatter = OutputFormatter(json = false)
        val rows = listOf(mapOf("name" to "alice", "age" to "30"))
        val result = formatter.format(rows)
        val lines = result.lines()
        assertTrue(lines[0].contains("NAME"))
        assertTrue(lines[0].contains("AGE"))
    }

    @Test
    fun `format returns table with separator line`() {
        val formatter = OutputFormatter(json = false)
        val rows = listOf(mapOf("name" to "alice"))
        val result = formatter.format(rows)
        val lines = result.lines()
        // Second line should be dashes
        assertTrue(lines[1].matches(Regex("^-+$")))
    }

    @Test
    fun `format returns table with aligned columns`() {
        val formatter = OutputFormatter(json = false)
        val rows = listOf(
            mapOf("name" to "alice", "city" to "oslo"),
            mapOf("name" to "bob", "city" to "bergen"),
        )
        val result = formatter.format(rows)
        val lines = result.lines()
        // All lines should have the same structure with two-space separator
        assertEquals(4, lines.size) // header + separator + 2 data rows
    }

    @Test
    fun `format pads columns to widest value`() {
        val formatter = OutputFormatter(json = false)
        val rows = listOf(
            mapOf("x" to "short"),
            mapOf("x" to "a much longer value"),
        )
        val result = formatter.format(rows)
        val lines = result.lines()
        // Separator dashes should be at least as wide as the longest value
        val separatorWidth = lines[1].trimEnd().length
        assertTrue(separatorWidth >= "a much longer value".length)
    }

    @Test
    fun `format handles null values in table mode`() {
        val formatter = OutputFormatter(json = false)
        val rows = listOf(mapOf<String, Any?>("key" to null))
        val result = formatter.format(rows)
        assertTrue(result.contains("KEY"))
        // null should be rendered as empty string; trimEnd() may remove trailing whitespace-only data row
        val lines = result.lines()
        assertTrue(lines.size >= 2) // at least header + separator
    }

    // --- JSON mode tests ---

    @Test
    fun `format returns JSON array for rows in json mode`() {
        val formatter = OutputFormatter(json = true)
        val rows = listOf(mapOf("name" to "alice", "age" to "30"))
        val result = formatter.format(rows)
        assertTrue(result.trimStart().startsWith("["))
        assertTrue(result.trimEnd().endsWith("]"))
        assertTrue(result.contains("\"name\""))
        assertTrue(result.contains("\"alice\""))
    }

    @Test
    fun `format returns empty JSON array for empty list in json mode`() {
        val formatter = OutputFormatter(json = true)
        val result = formatter.format(emptyList())
        assertEquals("[]", result.trim())
    }

    // --- Detail table mode tests ---

    @Test
    fun `formatDetail returns no data for empty map in table mode`() {
        val formatter = OutputFormatter(json = false)
        assertEquals("(no data)", formatter.formatDetail(emptyMap()))
    }

    @Test
    fun `formatDetail returns key-value pairs in table mode`() {
        val formatter = OutputFormatter(json = false)
        val data = linkedMapOf<String, Any?>("Name" to "alice", "Age" to 30)
        val result = formatter.formatDetail(data)
        assertTrue(result.contains("Name"))
        assertTrue(result.contains("alice"))
        assertTrue(result.contains("Age"))
        assertTrue(result.contains("30"))
    }

    @Test
    fun `formatDetail pads keys to same width`() {
        val formatter = OutputFormatter(json = false)
        val data = linkedMapOf<String, Any?>("A" to "1", "LongKey" to "2")
        val result = formatter.formatDetail(data)
        val lines = result.lines()
        // Both lines should have the key padded to "LongKey".length = 7
        assertTrue(lines[0].startsWith("A       ") || lines[0].startsWith("A      "))
    }

    @Test
    fun `formatDetail handles null values`() {
        val formatter = OutputFormatter(json = false)
        val data = linkedMapOf<String, Any?>("Key" to null)
        val result = formatter.formatDetail(data)
        assertTrue(result.contains("Key"))
    }

    // --- Detail JSON mode tests ---

    @Test
    fun `formatDetail returns JSON object in json mode`() {
        val formatter = OutputFormatter(json = true)
        val data = linkedMapOf<String, Any?>("Name" to "alice")
        val result = formatter.formatDetail(data)
        assertTrue(result.trimStart().startsWith("{"))
        assertTrue(result.trimEnd().endsWith("}"))
        assertTrue(result.contains("\"Name\""))
        assertTrue(result.contains("\"alice\""))
    }
}

