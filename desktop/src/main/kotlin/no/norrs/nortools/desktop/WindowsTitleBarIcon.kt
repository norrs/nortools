package no.norrs.nortools.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.io.path.name

private const val RUNTIME_PROPERTIES = "nortools-runtime.properties"
private const val TITLEBAR_HELPER_SHA256 = "nortools.titlebarIconHelper.sha256"

internal fun launchWindowsTitleBarIconHelper() {
    if (!System.getProperty("os.name").contains("win", ignoreCase = true)) return

    val exe = currentExecutable() ?: return
    val installDir = exe.parent ?: return
    val helper = installDir.resolve("nortools-titlebar-icon.exe")
    val icon = installDir.resolve("nortools.ico")
    if (!Files.isRegularFile(helper) || !Files.isRegularFile(icon)) return
    if (!helperMatchesEmbeddedHash(helper)) return

    runCatching {
        ProcessBuilder(helper.absolutePathString())
            .directory(installDir.toFile())
            .start()
    }.onFailure { error ->
        System.err.println("[NorTools] Failed to launch title bar icon helper: ${error.message}")
    }
}

private fun helperMatchesEmbeddedHash(helper: Path): Boolean {
    val expected = runtimeProperties().getProperty(TITLEBAR_HELPER_SHA256)?.trim()?.lowercase()
    if (expected.isNullOrBlank()) return false

    val actual = sha256Hex(helper)
    if (!actual.equals(expected, ignoreCase = true)) {
        System.err.println("[NorTools] Refusing to launch title bar icon helper: SHA-256 mismatch.")
        return false
    }
    return true
}

private fun runtimeProperties(): Properties {
    val props = Properties()
    val loader = Thread.currentThread().contextClassLoader
    val stream = loader.getResourceAsStream(RUNTIME_PROPERTIES)
        ?: WindowsTitleBarIconMarker::class.java.classLoader.getResourceAsStream(RUNTIME_PROPERTIES)
        ?: return props
    stream.use { props.load(it) }
    return props
}

private fun sha256Hex(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun currentExecutable(): Path? {
    val command = ProcessHandle.current().info().command().orElse(null)
    if (!command.isNullOrBlank()) {
        val path = Path.of(command).toAbsolutePath().normalize()
        if (Files.exists(path) && path.name.endsWith(".exe", ignoreCase = true)) return path
    }
    return null
}

private object WindowsTitleBarIconMarker
