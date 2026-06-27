package no.norrs.nortools.web

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal object TestRunfiles {
    fun resolve(relative: String): Path? {
        val direct = Paths.get(relative)
        if (Files.exists(direct)) return direct

        val testSrcDir = System.getenv("TEST_SRCDIR")
        val workspace = System.getenv("TEST_WORKSPACE") ?: "_main"
        val fromRunfilesDir = listOfNotNull(
            testSrcDir?.let { Paths.get(it, workspace, relative) },
            testSrcDir?.let { Paths.get(it, "_main", relative) },
        ).firstOrNull { Files.exists(it) }
        if (fromRunfilesDir != null) return fromRunfilesDir

        val manifest = System.getenv("RUNFILES_MANIFEST_FILE")?.let { Paths.get(it) }
        if (manifest != null && Files.exists(manifest)) {
            val keyCandidates = listOf("$workspace/$relative", "_main/$relative")
            Files.newBufferedReader(manifest).useLines { lines ->
                for (line in lines) {
                    val split = line.indexOf(' ')
                    if (split <= 0) continue
                    val key = line.substring(0, split)
                    if (key in keyCandidates) {
                        val path = Paths.get(line.substring(split + 1))
                        if (Files.exists(path)) return path
                    }
                }
            }
        }

        return null
    }

    fun list(prefix: String): List<Path> {
        val direct = Paths.get(prefix)
        if (Files.isDirectory(direct)) {
            return Files.list(direct).use { stream ->
                stream.filter { Files.isRegularFile(it) }.sorted().toList()
            }
        }

        val testSrcDir = System.getenv("TEST_SRCDIR")
        val workspace = System.getenv("TEST_WORKSPACE") ?: "_main"
        val expanded = listOfNotNull(
            testSrcDir?.let { Paths.get(it, workspace, prefix) },
            testSrcDir?.let { Paths.get(it, "_main", prefix) },
        ).firstOrNull { Files.isDirectory(it) }
        if (expanded != null) {
            return Files.list(expanded).use { stream ->
                stream.filter { Files.isRegularFile(it) }.sorted().toList()
            }
        }

        val manifest = System.getenv("RUNFILES_MANIFEST_FILE")?.let { Paths.get(it) }
        if (manifest != null && Files.exists(manifest)) {
            val keyPrefixes = listOf("$workspace/$prefix/", "_main/$prefix/")
            return Files.readAllLines(manifest)
                .mapNotNull { line ->
                    val split = line.indexOf(' ')
                    if (split <= 0) return@mapNotNull null
                    val key = line.substring(0, split)
                    if (keyPrefixes.none { key.startsWith(it) }) return@mapNotNull null
                    val path = Paths.get(line.substring(split + 1))
                    path.takeIf { Files.isRegularFile(it) }
                }
                .sorted()
        }

        return emptyList()
    }
}
