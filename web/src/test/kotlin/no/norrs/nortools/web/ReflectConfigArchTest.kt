package no.norrs.nortools.web

import com.tngtech.archunit.core.importer.ClassFileImporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

class ReflectConfigArchTest {

    private val dtoNameSuffixes = setOf(
        "Response",
        "Info",
        "Reply",
        "Result",
        "Status",
        "Check",
        "Zone",
        "Key",
        "Rrsig",
        "Ds",
        "ChainLink",
        "Mechanism",
        "Entry",
        "Options",
        "Hop",
        "Sample",
        "BuildInfo",
    )

    private val dtoNameExplicit = setOf(
        "ErrorResponse",
        "CheckSummary",
        "SslInfo",
    )

    private val dtoNameExclusions = setOf(
        "PingProbeResult",
    )

    @Test
    fun `reflect managed DTOs are listed in graal reflect config`() {
        val imported = ClassFileImporter().importPackages("no.norrs.nortools.web")
        val dtoClasses = imported
            .filter { it.packageName.startsWith("no.norrs.nortools.web") }
            .filter { it.simpleName.isNotBlank() && !it.simpleName.endsWith("Kt") }
            .filter { it.modifiers.contains(com.tngtech.archunit.core.domain.JavaModifier.PUBLIC) }
            .map { it.fullName }
            .filter { isReflectManagedDto(simpleNameOf(it)) }
            .toSortedSet()

        val reflectConfigNames = reflectConfigClassNames()
            .filter { it.startsWith("no.norrs.nortools.web.") }
            .filter { isReflectManagedDto(simpleNameOf(it)) }
            .toSortedSet()

        assertEquals(
            dtoClasses,
            reflectConfigNames,
            "Reflect config mismatch for web DTOs. Keep desktop/graal/reflect-config.json in sync.",
        )
    }

    @Test
    fun `web classes listed in reflect config follow DTO suffix convention`() {
        val offenders = reflectConfigClassNames()
            .filter { it.startsWith("no.norrs.nortools.web.") }
            .filterNot { isReflectManagedDto(simpleNameOf(it)) }

        assertTrue(
            offenders.isEmpty(),
            "Web classes in reflect-config.json must follow DTO naming convention. Offenders: $offenders",
        )
    }

    private fun reflectConfigClassNames(): List<String> {
        val text = readReflectConfigText()
        val regex = "\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"".toRegex()
        return regex.findAll(text)
            .map { it.groupValues[1] }
            .toList()
    }

    private fun readReflectConfigText(): String {
        val testSrcDir = System.getenv("TEST_SRCDIR")
        val workspace = System.getenv("TEST_WORKSPACE") ?: "_main"

        val candidates = listOfNotNull(
            Paths.get("desktop/graal/reflect-config.json"),
            testSrcDir?.let { Paths.get(it, workspace, "desktop/graal/reflect-config.json") },
            testSrcDir?.let { Paths.get(it, "_main", "desktop/graal/reflect-config.json") },
        )

        val path = candidates.firstOrNull { Files.exists(it) }
            ?: error("Could not locate desktop/graal/reflect-config.json from candidates: $candidates")
        return Files.readString(path)
    }

    private fun isReflectManagedDto(simpleName: String): Boolean {
        if (simpleName in dtoNameExclusions) return false
        return simpleName in dtoNameExplicit || dtoNameSuffixes.any { simpleName.endsWith(it) }
    }

    private fun simpleNameOf(fqcn: String): String = fqcn.substringAfterLast('.')
}
