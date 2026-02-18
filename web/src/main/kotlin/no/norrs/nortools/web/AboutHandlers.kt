package no.norrs.nortools.web

import io.javalin.http.Context
import java.time.Instant
import java.util.Properties

data class AboutBuildInfo(
    val target: String? = null,
    val mainClass: String? = null,
    val buildTime: String? = null,
    val buildTimestamp: String? = null,
    val gitCommit: String? = null,
    val gitShortCommit: String? = null,
    val gitBranch: String? = null,
    val gitDirty: String? = null,
)

data class AboutResponse(
    val appName: String,
    val version: String,
    val build: AboutBuildInfo,
    val credits: String,
    val inspiration: String,
    val rfc: String,
)

fun aboutInfo(ctx: Context) {
    val props = loadBuildProperties()
    val gitProps = loadGitBuildProperties()
    val version = normalizeBuildValue(props.firstNonBlank("build.version"))
        ?: normalizeBuildValue(gitProps.firstNonBlank("build.version", "git.describe", "STABLE_GIT_DESCRIBE"))
        ?: "unknown"
    var gitCommit = gitProps.firstNonBlank(
        "git.commit",
        "build.changelist",
        "build.git.commit",
        "stable.git.commit",
        "STABLE_GIT_COMMIT",
    ) ?: props.firstNonBlank(
        "stable.git.commit",
        "STABLE_GIT_COMMIT",
        "build.git.commit",
        "build.changelist",
        "git.commit",
    ) ?: gitProps.findFirstByTokens("git", "commit")
        ?: props.findFirstByTokens("git", "commit")
    var gitBranch = gitProps.firstNonBlank(
        "git.branch",
        "build.scm.branch",
        "build.git.branch",
        "stable.git.branch",
        "STABLE_GIT_BRANCH",
    ) ?: props.firstNonBlank(
        "stable.git.branch",
        "STABLE_GIT_BRANCH",
        "build.git.branch",
        "build.scm.branch",
        "git.branch",
    ) ?: gitProps.findFirstByTokens("git", "branch")
        ?: props.findFirstByTokens("git", "branch")
    var gitDirty = gitProps.firstNonBlank(
        "git.dirty",
        "build.scm.status",
        "build.git.dirty",
        "stable.git.dirty",
        "STABLE_GIT_DIRTY",
    ) ?: props.firstNonBlank(
        "stable.git.dirty",
        "STABLE_GIT_DIRTY",
        "build.git.dirty",
        "build.scm.status",
        "git.dirty",
    ) ?: gitProps.findFirstByTokens("git", "dirty")
        ?: props.findFirstByTokens("git", "dirty")
    if (gitDirty.equals("Modified", ignoreCase = true)) gitDirty = "true"
    if (gitDirty.equals("Clean", ignoreCase = true)) gitDirty = "false"
    gitCommit = normalizeBuildValue(gitCommit)
    gitBranch = normalizeBuildValue(gitBranch)
    gitDirty = normalizeBuildValue(gitDirty)
    val target = normalizeBuildValue(props.getProperty("build.target")) ?: "unknown"
    val mainClass = normalizeBuildValue(props.getProperty("main.class"))
        ?: "unknown"
    val buildTimestamp = normalizeBuildValue(
        props.firstNonBlank("build.timestamp", "build.timestamp.as.int"),
    ) ?: "unknown"
    val buildTime = normalizeBuildValue(props.getProperty("build.time"))
        ?: runCatching { buildTimestamp.toLong() }.getOrNull()?.let { Instant.ofEpochSecond(it).toString() }
        ?: "unknown"

    val response = AboutResponse(
        appName = "NorTools",
        version = version,
        build = AboutBuildInfo(
            target = target,
            mainClass = mainClass,
            buildTime = buildTime,
            buildTimestamp = buildTimestamp,
            gitCommit = gitCommit,
            gitShortCommit = gitCommit?.take(12),
            gitBranch = gitBranch,
            gitDirty = gitDirty,
        ),
        credits = """
            NorTools is built with Kotlin, Javalin, Vue, dnsjava, and GraalVM Native Image.
            Thanks to the maintainers and contributors of these open-source projects.
        """.trimIndent(),
        inspiration = readTextResource("docs/inspiration.md"),
        rfc = readTextResource("docs/RFC.md"),
    )
    ctx.jsonResult(response)
}

private fun normalizeBuildValue(value: String?): String? {
    val trimmed = value?.trim()
    if (trimmed.isNullOrEmpty()) return null
    if (trimmed.equals("unknown", ignoreCase = true)) return null
    return trimmed
}

private fun loadBuildProperties(): Properties {
    val candidates = loadPropertiesByResourceNames(
        "build-data.properties",
        "web/build-data.properties",
    )
    if (candidates.isEmpty()) return Properties()
    return candidates.maxByOrNull { scoreBuildProperties(it) } ?: candidates.first()
}

private fun loadGitBuildProperties(): Properties {
    val candidates = loadPropertiesByResourceNames(
        "git-build-info.properties",
        "web/git-build-info.properties",
    )
    if (candidates.isEmpty()) return Properties()
    return candidates.maxByOrNull { scoreGitProperties(it) } ?: candidates.first()
}

private fun loadPropertiesByResourceNames(vararg names: String): List<Properties> {
    val loader = Thread.currentThread().contextClassLoader
    val candidates = mutableListOf<Properties>()
    for (name in names) {
        val resources = loader.getResources(name)
        while (resources.hasMoreElements()) {
            val url = resources.nextElement()
            val props = Properties()
            try {
                url.openStream().use { props.load(it) }
                candidates.add(props)
            } catch (_: Exception) {
                // ignore unreadable candidate
            }
        }
    }
    return candidates
}

private fun readTextResource(path: String): String {
    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(path)
    return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        ?: "Resource not available: $path"
}

private fun Properties.firstNonBlank(vararg keys: String): String? {
    for (key in keys) {
        val value = getProperty(key)?.trim()
        if (!value.isNullOrEmpty()) return value
    }
    return null
}

private fun Properties.findFirstByTokens(vararg tokens: String): String? {
    val names = stringPropertyNames().sorted()
    for (name in names) {
        val lower = name.lowercase()
        if (tokens.all { lower.contains(it.lowercase()) }) {
            val value = getProperty(name)?.trim()
            if (!value.isNullOrEmpty()) return value
        }
    }
    return null
}

private fun scoreBuildProperties(props: Properties): Int {
    var score = 0
    val target = props.getProperty("build.target")?.lowercase().orEmpty()
    val mainClass = props.getProperty("main.class")?.lowercase().orEmpty()
    val tsInt = props.getProperty("build.timestamp.as.int")?.toLongOrNull() ?: 0L
    val hasGitCommit = !props.firstNonBlank(
        "stable.git.commit",
        "STABLE_GIT_COMMIT",
        "build.git.commit",
        "build.changelist",
        "git.commit",
    ).isNullOrBlank()

    if ("desktop:desktop_jar" in target) score += 500
    if ("//web:web" in target || target.endsWith(":web")) score += 450
    if ("kremaappkt" in mainClass) score += 300
    if ("webportalkt" in mainClass) score += 250
    if (tsInt > 0) score += 100
    if (hasGitCommit) score += 200
    if ("unknown" in target) score -= 50
    return score
}

private fun scoreGitProperties(props: Properties): Int {
    var score = 0
    if (!props.firstNonBlank("git.commit", "build.changelist", "STABLE_GIT_COMMIT").isNullOrBlank()) score += 300
    if (!props.firstNonBlank("git.branch", "build.scm.branch", "STABLE_GIT_BRANCH").isNullOrBlank()) score += 200
    if (!props.firstNonBlank("git.dirty", "build.scm.status", "STABLE_GIT_DIRTY").isNullOrBlank()) score += 100
    return score
}
