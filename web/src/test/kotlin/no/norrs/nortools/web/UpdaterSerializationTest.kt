package no.norrs.nortools.web

import build.krema.core.plugin.builtin.UpdaterPlugin
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class UpdaterSerializationTest {

    private val mapper = ObjectMapper()

    @Test
    fun `jackson serializes updater check result`() {
        val result = UpdaterPlugin.CheckResult(
            true,
            "0.0.260223001",
            "notes",
            "2026-02-23",
            false,
            1234L,
        )

        val json = mapper.writeValueAsString(result)

        assertTrue("\"updateAvailable\":true" in json)
        assertTrue("\"version\":\"0.0.260223001\"" in json)
    }

    @Test
    fun `reflect config includes updater plugin serializer classes`() {
        val text = readReflectConfigText()
        assertTrue(
            "\"name\": \"build.krema.core.plugin.builtin.UpdaterPlugin\$CheckResult\"" in text,
            "Missing UpdaterPlugin.CheckResult in desktop/graal/reflect-config.json",
        )
        assertTrue(
            "\"name\": \"build.krema.core.plugin.builtin.UpdaterPlugin\$DownloadResult\"" in text,
            "Missing UpdaterPlugin.DownloadResult in desktop/graal/reflect-config.json",
        )
    }

    private fun readReflectConfigText(): String {
        val path = TestRunfiles.resolve("desktop/graal/reflect-config.json")
            ?: error("Could not locate desktop/graal/reflect-config.json in runfiles")
        return Files.readString(path)
    }
}
