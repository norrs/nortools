package no.norrs.nortools.tools.whois.asn

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.TimeUnit

data class RouteAnnouncementValidationResult(
    val state: String,
    val source: String,
    val reason: String? = null,
    val details: String? = null,
    val vrpObjects: RouteValidationVrpObjects? = null,
)

data class RouteValidationVrpObjects(
    val matched: List<Map<String, String>> = emptyList(),
    val unmatchedAs: List<Map<String, String>> = emptyList(),
    val unmatchedLength: List<Map<String, String>> = emptyList(),
)

data class CommandExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

fun interface CommandExecutor {
    fun execute(command: List<String>, timeoutSeconds: Long): CommandExecutionResult
}

private object ProcessCommandExecutor : CommandExecutor {
    override fun execute(command: List<String>, timeoutSeconds: Long): CommandExecutionResult {
        return try {
            val process = ProcessBuilder(command).start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                CommandExecutionResult(
                    exitCode = 124,
                    stdout = "",
                    stderr = "Command timed out after ${timeoutSeconds}s",
                )
            } else {
                val stdout = process.inputStream.bufferedReader().use { it.readText() }
                val stderr = process.errorStream.bufferedReader().use { it.readText() }
                CommandExecutionResult(
                    exitCode = process.exitValue(),
                    stdout = stdout,
                    stderr = stderr,
                )
            }
        } catch (e: Exception) {
            CommandExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "Failed to execute command",
            )
        }
    }
}

fun routinatorResourceCandidates(osName: String, archName: String): List<String> {
    val os = osName.lowercase(Locale.ROOT)
    val arch = archName.lowercase(Locale.ROOT)
    val platform = when {
        os.contains("win") -> "windows"
        os.contains("mac") || os.contains("darwin") -> "macos"
        else -> "linux"
    }
    val targetArch = when (arch) {
        "amd64", "x86_64", "x64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> null
    } ?: return emptyList()
    val executable = if (platform == "windows") "routinator.exe" else "routinator"
    return listOf("native/routinator/$platform-$targetArch/$executable")
}

fun parseRoutinatorValidateJson(json: String): RouteAnnouncementValidationResult? {
    val trimmed = json.trim()
    if (trimmed.isBlank()) return null
    return try {
        val rootElement = JsonParser.parseString(trimmed)
        val validity = when {
            rootElement.isJsonObject -> {
                val rootObject = rootElement.asJsonObject
                rootObject["validity"]?.asJsonObject
                    ?: rootObject["validated_routes"]
                        ?.asJsonArray
                        ?.firstOrNull()
                        ?.asJsonObject
                        ?.get("validity")
                        ?.asJsonObject
            }
            rootElement.isJsonArray -> {
                rootElement.asJsonArray
                    .firstOrNull()
                    ?.asJsonObject
                    ?.get("validity")
                    ?.asJsonObject
            }
            else -> null
        } ?: return null
        val stateRaw = validity["state"]?.asString?.trim().orEmpty()
        if (stateRaw.isBlank()) return null
        val state = stateRaw.uppercase(Locale.ROOT).replace("-", "_")
        val reason = validity["reason"]?.asString?.trim()?.ifBlank { null }
        val description = validity["description"]?.asString?.trim()?.ifBlank { null }
        val vrpObjects = parseValidationVrpObjects(validity)
        val unmatchedLen =
            validity["unmatched_as"]?.asJsonArray?.size()
                ?: validity["VRPs"]?.asJsonObject?.get("unmatched_as")?.asJsonArray?.size()
                ?: 0
        val details = buildList {
            if (!description.isNullOrBlank()) add(description)
            if (vrpObjects != null) {
                add(
                    "VRPs: ${vrpObjects.matched.size} matched, " +
                        "${vrpObjects.unmatchedAs.size} ASN mismatch, " +
                        "${vrpObjects.unmatchedLength.size} max-length mismatch",
                )
            } else if (unmatchedLen > 0) {
                add("$unmatchedLen VRP(s) with different origin ASN")
            }
        }.joinToString("; ").ifBlank { null }
        RouteAnnouncementValidationResult(
            state = state,
            source = "routinator",
            reason = reason,
            details = details,
            vrpObjects = vrpObjects,
        )
    } catch (_: Exception) {
        null
    }
}

private fun parseValidationVrpObjects(validity: JsonObject): RouteValidationVrpObjects? {
    val vrps = validity["VRPs"]?.takeIf { it.isJsonObject }?.asJsonObject
    val matched = parseVrpArray(vrps?.get("matched") ?: validity["matched"])
    val unmatchedAs = parseVrpArray(vrps?.get("unmatched_as") ?: validity["unmatched_as"])
    val unmatchedLength = parseVrpArray(vrps?.get("unmatched_length") ?: validity["unmatched_length"])
    if (matched.isEmpty() && unmatchedAs.isEmpty() && unmatchedLength.isEmpty()) return null
    return RouteValidationVrpObjects(
        matched = matched,
        unmatchedAs = unmatchedAs,
        unmatchedLength = unmatchedLength,
    )
}

private fun parseVrpArray(element: JsonElement?): List<Map<String, String>> {
    val array = element?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
    return array.map { parseVrpObject(it) }
}

private fun parseVrpObject(element: JsonElement): Map<String, String> {
    if (!element.isJsonObject) {
        return mapOf("value" to jsonElementToDisplay(element))
    }
    val obj = element.asJsonObject
    val out = linkedMapOf<String, String>()
    for ((key, value) in obj.entrySet()) {
        out[key] = jsonElementToDisplay(value)
    }
    return out
}

private fun jsonElementToDisplay(element: JsonElement?): String {
    if (element == null || element.isJsonNull) return "null"
    if (!element.isJsonPrimitive) return element.toString()
    val primitive = element.asJsonPrimitive
    return when {
        primitive.isString -> primitive.asString
        primitive.isBoolean -> primitive.asBoolean.toString()
        primitive.isNumber -> primitive.asNumber.toString()
        else -> primitive.toString()
    }
}

class RoutinatorRouteValidator(
    private val explicitBinary: String? = null,
    private val envBinary: String? = System.getenv("NORTOOLS_ROUTINATOR_BIN"),
    private val classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
    private val commandExecutor: CommandExecutor = ProcessCommandExecutor,
    private val osName: String = System.getProperty("os.name"),
    private val archName: String = System.getProperty("os.arch"),
) {
    private companion object {
        const val VALIDATE_TIMEOUT_SECONDS = 30L
    }

    private data class ResolvedExecutable(
        val path: String,
        val source: String,
    )

    private var resolvedExecutable: ResolvedExecutable? = null

    fun validate(prefix: String, asn: String): RouteAnnouncementValidationResult {
        val asnNumber = asn.trim().removePrefix("AS").removePrefix("as")
        if (asnNumber.isBlank()) {
            return RouteAnnouncementValidationResult(
                state = "UNAVAILABLE",
                source = "routinator",
                reason = "invalid-input",
                details = "ASN is missing",
            )
        }
        if (prefix.isBlank()) {
            return RouteAnnouncementValidationResult(
                state = "UNAVAILABLE",
                source = "routinator",
                reason = "invalid-input",
                details = "Prefix is missing",
            )
        }

        val executable = resolveExecutable() ?: return RouteAnnouncementValidationResult(
            state = "UNAVAILABLE",
            source = "routinator",
            reason = "binary-missing",
            details = "Routinator not found (checked explicit path, env override, embedded binary, and PATH)",
        )

        val commandVariants = listOf(
            listOf(
                executable.path,
                "validate",
                "--json",
                "--noupdate",
                "--asn",
                asnNumber,
                "--prefix",
                prefix,
            ),
            listOf(
                executable.path,
                "validate",
                "--output-format",
                "json",
                "--noupdate",
                asnNumber,
                prefix,
            ),
            listOf(
                executable.path,
                "validate",
                "--json",
                "--noupdate",
                asnNumber,
                prefix,
            ),
        )
        var lastFailure: CommandExecutionResult? = null
        for ((index, command) in commandVariants.withIndex()) {
            val commandResult = commandExecutor.execute(command, timeoutSeconds = VALIDATE_TIMEOUT_SECONDS)
            val parsed = parseRoutinatorValidateJson(commandResult.stdout)
            if (parsed != null) {
                return parsed.copy(source = executable.source)
            }
            if (commandResult.exitCode == 0) {
                return RouteAnnouncementValidationResult(
                    state = "UNAVAILABLE",
                    source = executable.source,
                    reason = "parse-failed",
                    details = "Routinator returned unparseable output",
                )
            }

            val errorText = commandResult.stderr.ifBlank { commandResult.stdout }
            val shouldTryCompatibilityFallback = when (index) {
                0 -> looksLikeUnsupportedModernValidateArgs(errorText)
                1 -> looksLikeUnsupportedLegacyOutputFormat(errorText)
                else -> false
            }
            if (!shouldTryCompatibilityFallback && index < commandVariants.lastIndex) {
                return RouteAnnouncementValidationResult(
                    state = "UNAVAILABLE",
                    source = executable.source,
                    reason = "execution-failed",
                    details = errorText.ifBlank { "Routinator exited with ${commandResult.exitCode}" },
                )
            }
            lastFailure = commandResult
        }

        return RouteAnnouncementValidationResult(
            state = "UNAVAILABLE",
            source = executable.source,
            reason = "execution-failed",
            details = lastFailure?.stderr?.ifBlank { "Routinator exited with ${lastFailure.exitCode}" }
                ?: "Routinator execution failed",
        )
    }

    private fun resolveExecutable(): ResolvedExecutable? {
        resolvedExecutable?.let { return it }

        val explicit = explicitBinary?.takeIf { it.isNotBlank() }
        if (explicit != null) {
            val candidate = ResolvedExecutable(explicit, "explicit")
            if (isRunnable(candidate.path)) {
                resolvedExecutable = candidate
                return candidate
            }
        }

        val env = envBinary?.takeIf { it.isNotBlank() }
        if (env != null) {
            val candidate = ResolvedExecutable(env, "env")
            if (isRunnable(candidate.path)) {
                resolvedExecutable = candidate
                return candidate
            }
        }

        val bundled = findBundledExecutable()
        if (bundled != null && isRunnable(bundled.path)) {
            resolvedExecutable = bundled
            return bundled
        }

        val embedded = extractEmbeddedExecutable()
        if (embedded != null && isRunnable(embedded.path)) {
            resolvedExecutable = embedded
            return embedded
        }

        val pathCandidate = ResolvedExecutable("routinator", "path")
        if (isRunnable(pathCandidate.path)) {
            resolvedExecutable = pathCandidate
            return pathCandidate
        }

        return null
    }

    private fun findBundledExecutable(): ResolvedExecutable? {
        val executableName = if (osName.lowercase(Locale.ROOT).contains("win")) "routinator.exe" else "routinator"
        val userDir = File(System.getProperty("user.dir"))
        val processDir = runningExecutableDirectory()
        val candidates = listOfNotNull(
            File(userDir, executableName),
            File(userDir, "bin/$executableName"),
            processDir?.let { File(it, executableName) },
            processDir?.let { File(it, "bin/$executableName") },
        ).distinctBy { it.absolutePath }
        val existing = candidates.firstOrNull { it.exists() }
        return existing?.let { ResolvedExecutable(it.absolutePath, "bundled") }
    }

    private fun runningExecutableDirectory(): File? {
        val commandPath = try {
            ProcessHandle.current().info().command().orElse(null)
        } catch (_: Exception) {
            null
        } ?: return null
        return File(commandPath).parentFile
    }

    private fun extractEmbeddedExecutable(): ResolvedExecutable? {
        val candidates = routinatorResourceCandidates(osName, archName)
        for (resourcePath in candidates) {
            val stream = classLoader.getResourceAsStream(resourcePath) ?: continue
            val fileName = resourcePath.substringAfterLast("/")
            val tempDir = File(System.getProperty("java.io.tmpdir"), "nortools-routinator")
            tempDir.mkdirs()
            val outFile = File(tempDir, "${osName.lowercase(Locale.ROOT)}-${archName.lowercase(Locale.ROOT)}-$fileName")
            stream.use {
                Files.copy(it, outFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            outFile.setExecutable(true, true)
            return ResolvedExecutable(outFile.absolutePath, "embedded")
        }
        return null
    }

    private fun isRunnable(executable: String): Boolean {
        val result = commandExecutor.execute(listOf(executable, "--version"), timeoutSeconds = 5)
        return result.exitCode == 0 || result.stdout.contains("routinator", ignoreCase = true)
    }

    private fun looksLikeUnsupportedModernValidateArgs(errorText: String): Boolean {
        if (errorText.isBlank()) return false
        val lowered = errorText.lowercase(Locale.ROOT)
        if (!lowered.contains("unexpected argument") && !lowered.contains("wasn't expected")) return false
        return lowered.contains("--json") || lowered.contains("--asn") || lowered.contains("--prefix")
    }

    private fun looksLikeUnsupportedLegacyOutputFormat(errorText: String): Boolean {
        if (errorText.isBlank()) return false
        val lowered = errorText.lowercase(Locale.ROOT)
        if (!lowered.contains("unexpected argument") && !lowered.contains("wasn't expected")) return false
        return lowered.contains("--output-format")
    }
}
