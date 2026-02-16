package no.norrs.nortools.web

import io.javalin.http.Context
import java.util.Properties

data class AboutBuildInfo(
    val target: String? = null,
    val mainClass: String? = null,
    val buildTime: String? = null,
    val buildTimestamp: String? = null,
    val gitCommit: String? = null,
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
    val response = AboutResponse(
        appName = "NorTools",
        version = "0.1.0",
        build = AboutBuildInfo(
            target = props.getProperty("build.target"),
            mainClass = props.getProperty("main.class"),
            buildTime = props.getProperty("build.time"),
            buildTimestamp = props.getProperty("build.timestamp"),
            gitCommit = props.getProperty("stable.git.commit"),
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

private fun loadBuildProperties(): Properties {
    val props = Properties()
    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream("build-data.properties")
    if (stream != null) {
        stream.use { props.load(it) }
    }
    return props
}

private fun readTextResource(path: String): String {
    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(path)
    return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        ?: "Resource not available: $path"
}
