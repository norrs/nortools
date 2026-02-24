package no.norrs.nortools.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class ReflectConfigArchTest {

    @Test
    fun `public top-level web data classes are listed in graal reflect config`() {
        val expected = publicTopLevelWebDataClasses()
            .map { "no.norrs.nortools.web.$it" }
            .toSortedSet()

        val actual = reflectConfigClassNames()
            .filter { it.startsWith("no.norrs.nortools.web.") }
            .toSortedSet()

        assertEquals(
            expected,
            actual,
            "Reflect config mismatch for web data classes. Keep desktop/graal/reflect-config.json in sync.",
        )
    }

    @Test
    fun `required non-web serializer classes are listed in graal reflect config`() {
        val actual = reflectConfigClassNames().toSet()
        val missing = requiredNonWebSerializerClasses().filterNot { it in actual }
        assertEquals(
            emptyList<String>(),
            missing,
            "Reflect config is missing required non-web serializer classes.",
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

    private fun publicTopLevelWebDataClasses(): Set<String> {
        val classNames = mutableSetOf<String>()
        val declRegex = Regex("^(?:public\\s+)?data\\s+class\\s+([A-Za-z_][A-Za-z0-9_]*)\\b", setOf(RegexOption.MULTILINE))

        for (file in webSourceFiles()) {
            val text = Files.readString(file)
            declRegex.findAll(text).forEach { match ->
                classNames += match.groupValues[1]
            }
        }
        return classNames
    }

    private fun requiredNonWebSerializerClasses(): List<String> =
        listOf(
            "build.krema.core.plugin.builtin.UpdaterPlugin\$CheckResult",
            "build.krema.core.plugin.builtin.UpdaterPlugin\$DownloadResult",
        )

    private fun webSourceFiles(): List<Path> {
        val testSrcDir = System.getenv("TEST_SRCDIR")
        val workspace = System.getenv("TEST_WORKSPACE") ?: "_main"

        val roots = listOfNotNull(
            Paths.get("web/src/main/kotlin/no/norrs/nortools/web"),
            testSrcDir?.let { Paths.get(it, workspace, "web/src/main/kotlin/no/norrs/nortools/web") },
            testSrcDir?.let { Paths.get(it, "_main", "web/src/main/kotlin/no/norrs/nortools/web") },
        )
        val root = roots.firstOrNull { Files.isDirectory(it) }
            ?: error("Could not locate web source directory from candidates: $roots")

        return Files.list(root).use { stream ->
            stream
                .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
                .sorted()
                .toList()
        }
    }
}
