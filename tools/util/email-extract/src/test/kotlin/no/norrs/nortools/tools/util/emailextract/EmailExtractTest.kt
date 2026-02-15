package no.norrs.nortools.tools.util.emailextract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for email extraction regex logic.
 * The regex is the core of the EmailExtract tool.
 */
class EmailExtractTest {

    // Replicate the RFC 5322 compatible email regex from EmailExtractCommand
    private val emailRegex = Regex(
        "[a-zA-Z0-9.!#\$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*"
    )

    @Test
    fun `extracts simple email address`() {
        val text = "Contact us at user@example.com for info"
        val emails = emailRegex.findAll(text).map { it.value }.toList()
        assertEquals(listOf("user@example.com"), emails)
    }

    @Test
    fun `extracts multiple email addresses`() {
        val text = "Send to alice@example.com and bob@test.org"
        val emails = emailRegex.findAll(text).map { it.value }.toList()
        assertEquals(2, emails.size)
        assertTrue(emails.contains("alice@example.com"))
        assertTrue(emails.contains("bob@test.org"))
    }

    @Test
    fun `extracts email with subdomain`() {
        val text = "user@mail.example.co.uk"
        val emails = emailRegex.findAll(text).map { it.value }.toList()
        assertEquals(1, emails.size)
        assertEquals("user@mail.example.co.uk", emails[0])
    }

    @Test
    fun `extracts email with plus addressing`() {
        val text = "user+tag@example.com"
        val emails = emailRegex.findAll(text).map { it.value }.toList()
        assertEquals(1, emails.size)
        assertEquals("user+tag@example.com", emails[0])
    }

    @Test
    fun `extracts email with dots in local part`() {
        val text = "first.last@example.com"
        val emails = emailRegex.findAll(text).map { it.value }.toList()
        assertEquals(1, emails.size)
        assertEquals("first.last@example.com", emails[0])
    }

    @Test
    fun `extracts email with special characters in local part`() {
        val text = "user!def#abc@example.com"
        val emails = emailRegex.findAll(text).map { it.value }.toList()
        assertEquals(1, emails.size)
    }

    @Test
    fun `returns empty for text without emails`() {
        val text = "No email addresses here, just some text."
        val emails = emailRegex.findAll(text).map { it.value }.toList()
        assertTrue(emails.isEmpty())
    }

    @Test
    fun `extracts emails from multiline text`() {
        val text = """
            From: sender@example.com
            To: recipient@test.org
            Subject: Test
        """.trimIndent()
        val emails = emailRegex.findAll(text).map { it.value }.toList()
        assertEquals(2, emails.size)
    }

    @Test
    fun `extracts email surrounded by angle brackets`() {
        val text = "From: John Doe <john@example.com>"
        val emails = emailRegex.findAll(text).map { it.value }.toList()
        assertEquals(1, emails.size)
        assertEquals("john@example.com", emails[0])
    }

    @Test
    fun `domain extraction from email`() {
        val email = "user@example.com"
        val domain = email.substringAfter("@")
        assertEquals("example.com", domain)
    }

    @Test
    fun `local part extraction from email`() {
        val email = "user@example.com"
        val localPart = email.substringBefore("@")
        assertEquals("user", localPart)
    }

    @Test
    fun `distinct filters duplicate emails`() {
        val emails = listOf("a@b.com", "a@b.com", "c@d.com")
        val unique = emails.distinct()
        assertEquals(2, unique.size)
    }

    @Test
    fun `sorted sorts emails alphabetically`() {
        val emails = listOf("z@example.com", "a@example.com", "m@example.com")
        val sorted = emails.sorted()
        assertEquals("a@example.com", sorted[0])
        assertEquals("m@example.com", sorted[1])
        assertEquals("z@example.com", sorted[2])
    }
}

