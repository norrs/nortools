package no.norrs.nortools.tools.util.passwordgen

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import no.norrs.nortools.lib.cli.BaseCommand
import java.security.SecureRandom
import kotlin.math.ln
import kotlin.math.log2

/**
 * Password Generator tool — generates cryptographically secure random passwords.
 *
 * Uses java.security.SecureRandom for cryptographic randomness.
 * Supports configurable length, character sets, and multiple password generation.
 */
class PasswordGenCommand : BaseCommand(
    name = "password-gen",
    helpText = "Generate cryptographically secure random passwords",
) {
    private val length by option("--length", "-l", help = "Password length").int().default(16)
    private val count by option("--count", "-c", help = "Number of passwords to generate").int().default(1)
    private val noUppercase by option("--no-uppercase", help = "Exclude uppercase letters").flag()
    private val noLowercase by option("--no-lowercase", help = "Exclude lowercase letters").flag()
    private val noDigits by option("--no-digits", help = "Exclude digits").flag()
    private val noSpecial by option("--no-special", help = "Exclude special characters").flag()
    private val customChars by option("--chars", help = "Custom character set to use")

    override fun run() {
        val formatter = createFormatter()
        val random = SecureRandom()

        val charset = if (customChars != null) {
            customChars!!
        } else {
            buildString {
                if (!noUppercase) append("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                if (!noLowercase) append("abcdefghijklmnopqrstuvwxyz")
                if (!noDigits) append("0123456789")
                if (!noSpecial) append("!@#\$%^&*()-_=+[]{}|;:,.<>?")
            }
        }

        if (charset.isEmpty()) {
            echo("Error: No characters available. At least one character set must be enabled.")
            return
        }

        val entropy = length * log2(charset.length.toDouble())

        val rows = (1..count).map { i ->
            val password = buildString {
                repeat(length) {
                    append(charset[random.nextInt(charset.length)])
                }
            }
            mapOf(
                "#" to "$i",
                "Password" to password,
                "Length" to "$length",
                "Entropy" to "%.1f bits".format(entropy),
            )
        }

        echo("Character set size: ${charset.length}")
        echo("Entropy per password: ${"%.1f".format(entropy)} bits")
        val strength = when {
            entropy >= 128 -> "Very Strong"
            entropy >= 80 -> "Strong"
            entropy >= 60 -> "Moderate"
            entropy >= 40 -> "Weak"
            else -> "Very Weak"
        }
        echo("Strength: $strength")
        echo()
        echo(formatter.format(rows))
    }
}

fun main(args: Array<String>) = PasswordGenCommand().main(args)
