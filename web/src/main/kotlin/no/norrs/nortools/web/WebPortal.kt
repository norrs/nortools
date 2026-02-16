package no.norrs.nortools.web

import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.after
import io.javalin.apibuilder.ApiBuilder.before
import io.javalin.apibuilder.ApiBuilder.get
import io.javalin.apibuilder.ApiBuilder.post
import io.javalin.http.staticfiles.Location
import java.io.File
import java.nio.file.Path

/**
 * Find the Vue SPA dist directory.
 * When run via Bazel, the dist is in the runfiles tree under frontend/dist.
 */
fun findDistDir(): File? {
    // Check Bazel runfiles paths
    val candidates = listOf(
        // Bazel runfiles: _main is the default repo name for the main module
        System.getenv("RUNFILES_DIR")?.let { File(it, "_main/frontend/dist") },
        // Relative to a working directory (Bazel run)
        File("frontend/dist"),
        // Bazel runfiles via the binary's runfiles directory
        File(System.getProperty("user.dir"), "frontend/dist"),
    )
    return candidates.filterNotNull()
        .firstOrNull { it.isDirectory && it.resolve("index.html").exists() }
        ?.canonicalFile  // Resolve symlinks so Jetty doesn't reject alias references (Bazel runfiles use symlinks)
}

/**
 * How to serve the frontend.
 *
 * DEV_DIST:
 *   - Use findDistDir() and serve from a real frontend/dist directory on disk.
 *   - Intended for local dev (e.g. bazel run with FRONTEND_MODE=dev).
 *
 * CLASSPATH_SPA:
 *   - Serve the built Vue SPA from the classpath under /web.
 *   - Intended for packaged JAR and native image.
 */
enum class FrontendMode { DEV_DIST, CLASSPATH_SPA }

fun detectFrontendMode(): FrontendMode =
    when (System.getenv("FRONTEND_MODE") ?: System.getProperty("frontend.mode")) {
        "dev", "DEV_DIST" -> FrontendMode.DEV_DIST
        "classpath", "CLASSPATH_SPA" -> FrontendMode.CLASSPATH_SPA
        else -> FrontendMode.CLASSPATH_SPA // default for prod/native
    }

/**
 * Existing entry point to start the HTTP server.
 * Updated to support serving the Vue SPA from the classpath.
 */
fun startServer(
    frontendMode: FrontendMode,
    port: Int = (System.getenv("PORT") ?: "7070").toInt()
): Javalin {
    val mode = detectFrontendMode()
    val distDir: Path? = if (frontendMode == FrontendMode.DEV_DIST) {
        requireNotNull(findDistDir()).toPath()
    } else {
        null
    }

    val app = Javalin.create { cfg ->
        when (frontendMode) {
            FrontendMode.DEV_DIST -> {
                println("Serving frontend from dist dir: ${requireNotNull(distDir).toAbsolutePath()}")

                // Serve static assets from filesystem
                cfg.staticFiles.add { static ->
                    static.directory = distDir.toAbsolutePath().toString()
                    static.location = Location.EXTERNAL
                }

            }

            FrontendMode.CLASSPATH_SPA -> {
                println("Serving frontend from classpath:/web")

                // Static assets and index.html are under web/ on the classpath
                cfg.staticFiles.add { static ->
                    static.directory = "web"
                    static.location = Location.CLASSPATH
                }

                cfg.spaRoot.addFile(
                    "/",
                    "web/index.html"
                )
            }
        }


        // DNS tools
        cfg.router.apiBuilder {
            get("/api/dns/{type}/{domain}") { ctx -> dnsLookup(ctx) }
            get("/api/dnssec/{type}/{domain}") { ctx -> dnssecLookup(ctx) }
            get("/api/dnssec-chain/{domain}") { ctx -> dnssecChain(ctx) }
            get("/api/reverse/{ip}") { ctx -> reverseLookup(ctx) }

            // Email auth tools
            get("/api/spf/{domain}") { ctx -> spfLookup(ctx) }
            get("/api/dkim/{selector}/{domain}") { ctx -> dkimLookup(ctx) }
            get("/api/dkim-discover/{domain}") { ctx -> dkimDiscover(ctx) }
            get("/api/dmarc/{domain}") { ctx -> dmarcLookup(ctx) }

            // Network tools
            get("/api/tcp/{host}/{port}") { ctx -> tcpCheck(ctx) }
            get("/api/http/{url}") { ctx -> httpCheck(ctx) }
            get("/api/https/{host}") { ctx -> httpsCheck(ctx) }
            get("/api/ping/{host}") { ctx -> pingCheck(ctx) }
            get("/api/ping-stream/{host}") { ctx -> pingStream(ctx) }
            get("/api/trace/{host}") { ctx -> traceCheck(ctx) }
            get("/api/trace-visual/{host}") { ctx -> traceVisual(ctx) }
            get("/api/trace-visual-stream/{host}") { ctx -> traceVisualStream(ctx) }

            // WHOIS tools
            get("/api/whois/{query}") { ctx -> whoisLookup(ctx) }

            // Utility tools
            get("/api/whatismyip") { ctx -> whatIsMyIp(ctx) }
            get("/api/subnet/{cidr}") { ctx -> subnetCalc(ctx) }
            get("/api/password") { ctx -> passwordGen(ctx) }
            post("/api/email-extract") { ctx -> emailExtract(ctx) }
            get("/api/about") { ctx -> aboutInfo(ctx) }

            // Blocklist tools
            get("/api/blacklist/{ip}") { ctx -> blacklistCheck(ctx) }

            // Composite tools
            get("/api/dns-health/{domain}") { ctx -> dnsHealthCheck(ctx) }
            get("/api/domain-health/{domain}") { ctx -> domainHealth(ctx) }

            // Generator tools
            get("/api/spf-generator") { ctx -> spfGenerator(ctx) }
            get("/api/dmarc-generator") { ctx -> dmarcGenerator(ctx) }


            before("/api/*") { ctx ->
                println("API REQ ${ctx.method()} ${ctx.path()} query=${ctx.queryString() ?: ""}")
            }
            after("/api/*") { ctx ->
                println("API RES ${ctx.method()} ${ctx.path()} -> ${ctx.status()}")
            }

        }
    }
    // Catch-all route for SPA (history mode).
    // Returns index.html from the classpath so the client-side router can handle it.
    app.get("{*path}") { ctx ->
        val path = ctx.path()

        // Let API and other backend-only paths fall through.
        if (path.startsWith("/api/")) {
            ctx.status(404)
            return@get
        }

        if (frontendMode == FrontendMode.DEV_DIST) {
            val indexPath = distDir!!.resolve("index.html").toFile()
            if (!indexPath.exists()) {
                ctx.status(500).result("index.html not found in dist dir")
                return@get
            }
            ctx.contentType("text/html")
            ctx.result(indexPath.inputStream())
        } else {
            ctx.contentType("text/html")
            val indexStream =
                Thread.currentThread().contextClassLoader.getResourceAsStream("web/index.html")
            if (indexStream == null) {
                ctx.status(500)
                    .result("SPA index.html not found in resources (/web/index.html)")
                return@get
            }
            ctx.contentType("text/html; charset=utf-8")
            ctx.result(indexStream)
        }
    }
    app.start(port)
    println("Listening on http://127.0.0.1:${port}/")
    return app
}

fun main() {
    // Keep main thin: just start the server using the existing entry point.
    startServer(FrontendMode.DEV_DIST)
}
