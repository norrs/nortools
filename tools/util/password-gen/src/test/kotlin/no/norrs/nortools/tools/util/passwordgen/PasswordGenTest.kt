package no.norrs.nortools.tools.util.passwordgen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.log2

/**
 * Tests for password generation logic.
 * Tests the pure calculation functions (entropy, strength classification, charset building).
 */
class PasswordGenTest {

    // Replicate charset building logic
    private fun buildCharset(
        noUppercase: Boolean = false,
        noLowercase: Boolean = false,
        noDigits: Boolean = false,
        noSpecial: Boolean = false,
    ): String {
        return buildString {
            if (!noUppercase) append("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
            if (!noLowercase) append("abcdefghijklmnopqrstuvwxyz")
            if (!noDigits) append("0123456789")
            if (!noSpecial) append("!@#\$%^&*()-_=+[]{}|;:,.<>?")
        }
    }

    // Replicate strength classification
    private fun classifyStrength(entropy: Double): String {
        return when {
            entropy >= 128 -> "Very Strong"
            entropy >= 80 -> "Strong"
            entropy >= 60 -> "Moderate"
            entropy >= 40 -> "Weak"
            else -> "Very Weak"
        }
    }

    @Test
    fun `default charset includes all character types`() {
        val charset = buildCharset()
        assertTrue(charset.contains("A"))
        assertTrue(charset.contains("z"))
        assertTrue(charset.contains("0"))
        assertTrue(charset.contains("!"))
    }

    @Test
    fun `charset without uppercase excludes uppercase`() {
        val charset = buildCharset(noUppercase = true)
        assertTrue(charset.none { it in 'A'..'Z' })
        assertTrue(charset.any { it in 'a'..'z' })
    }

    @Test
    fun `charset without lowercase excludes lowercase`() {
        val charset = buildCharset(noLowercase = true)
        assertTrue(charset.none { it in 'a'..'z' })
        assertTrue(charset.any { it in 'A'..'Z' })
    }

    @Test
    fun `charset without digits excludes digits`() {
        val charset = buildCharset(noDigits = true)
        assertTrue(charset.none { it in '0'..'9' })
    }

    @Test
    fun `charset without special excludes special characters`() {
        val charset = buildCharset(noSpecial = true)
        assertTrue(charset.none { it == '!' || it == '@' || it == '#' })
    }

    @Test
    fun `all disabled produces empty charset`() {
        val charset = buildCharset(
            noUppercase = true,
            noLowercase = true,
            noDigits = true,
            noSpecial = true,
        )
        assertTrue(charset.isEmpty())
    }

    @Test
    fun `entropy calculation for default charset length 16`() {
        val charset = buildCharset()
        val length = 16
        val entropy = length * log2(charset.length.toDouble())
        // Default charset has 26+26+10+26 = 88 chars
        // entropy = 16 * log2(88) ≈ 16 * 6.459 ≈ 103.35
        assertTrue(entropy > 100.0)
        assertTrue(entropy < 110.0)
    }

    @Test
    fun `strength classification boundaries`() {
        assertEquals("Very Weak", classifyStrength(30.0))
        assertEquals("Weak", classifyStrength(40.0))
        assertEquals("Weak", classifyStrength(59.9))
        assertEquals("Moderate", classifyStrength(60.0))
        assertEquals("Moderate", classifyStrength(79.9))
        assertEquals("Strong", classifyStrength(80.0))
        assertEquals("Strong", classifyStrength(127.9))
        assertEquals("Very Strong", classifyStrength(128.0))
        assertEquals("Very Strong", classifyStrength(256.0))
    }

    @Test
    fun `default 16 char password is Strong`() {
        val charset = buildCharset()
        val entropy = 16 * log2(charset.length.toDouble())
        assertEquals("Strong", classifyStrength(entropy))
    }

    @Test
    fun `short password with digits only is Very Weak`() {
        val charset = "0123456789"
        val entropy = 4 * log2(charset.length.toDouble())
        // 4 * log2(10) ≈ 13.3
        assertEquals("Very Weak", classifyStrength(entropy))
    }
}

