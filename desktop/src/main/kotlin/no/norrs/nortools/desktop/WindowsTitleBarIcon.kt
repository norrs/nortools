package no.norrs.nortools.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.name

internal fun launchWindowsTitleBarIconHelper() {
    if (!System.getProperty("os.name").contains("win", ignoreCase = true)) return

    val exe = currentExecutable() ?: return
    val installDir = exe.parent ?: return
    val helper = installDir.resolve("nortools-titlebar-icon.exe")
    val icon = installDir.resolve("nortools.ico")
    if (!Files.isRegularFile(helper) || !Files.isRegularFile(icon)) return

    runCatching {
        ProcessBuilder(
            helper.absolutePathString(),
            ProcessHandle.current().pid().toString(),
            icon.absolutePathString(),
            "NorTools",
        )
            .directory(installDir.toFile())
            .start()
    }.onFailure { error ->
        System.err.println("[NorTools] Failed to launch title bar icon helper: ${error.message}")
    }
}

private fun currentExecutable(): Path? {
    val command = ProcessHandle.current().info().command().orElse(null)
    if (!command.isNullOrBlank()) {
        val path = Path.of(command).toAbsolutePath().normalize()
        if (Files.exists(path) && path.name.endsWith(".exe", ignoreCase = true)) return path
    }
    return null
}
