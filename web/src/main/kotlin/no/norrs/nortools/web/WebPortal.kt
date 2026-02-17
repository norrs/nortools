package no.norrs.nortools.web

import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.after
import io.javalin.apibuilder.ApiBuilder.before
import io.javalin.apibuilder.ApiBuilder.get
import io.javalin.apibuilder.ApiBuilder.post
import io.javalin.http.staticfiles.Location
import io.javalin.vue.VueComponent
import java.io.File
import java.nio.file.Files

fun startServer(
    port: Int = (System.getenv("PORT") ?: "7070").toInt()
): Javalin {
    val app = Javalin.create { cfg ->
        val vueRootDir = findVueRootDir()
            ?: materializeVueRootDirFromClasspath()
            ?: throw IllegalStateException("Could not locate Vue templates for JavalinVue mode")
        println("Serving frontend via JavalinVue (external:${vueRootDir.absolutePath})")

        val hasWebjars = Thread.currentThread().contextClassLoader
            .getResource("META-INF/resources/webjars") != null
        if (hasWebjars) {
            cfg.staticFiles.enableWebjars()
        }

        cfg.vue.vueInstanceNameInJs = "app"
        cfg.vue.rootDirectory(vueRootDir.absolutePath, Location.EXTERNAL)

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

    registerJavalinVueRoutes(app)

    app.start(port)
    println("Listening on http://127.0.0.1:${port}/")
    return app
}

fun main() {
    startServer()
}

private fun registerJavalinVueRoutes(app: Javalin) {
    app.get("/", VueComponent("home-page"))
    app.get("/dns", VueComponent("dns-lookup-page"))
    app.get("/dnssec", VueComponent("dnssec-lookup-page"))
    app.get("/dnssec-lookup", VueComponent("dnssec-lookup-page"))
    app.get("/reverse-dns", VueComponent("reverse-dns-page"))
    app.get("/spf", VueComponent("spf-page"))
    app.get("/dkim", VueComponent("dkim-page"))
    app.get("/help/mta-sts-dns", VueComponent("help-mta-sts-dns-page"))
    app.get("/dmarc", VueComponent("dmarc-page"))
    app.get("/tcp", VueComponent("tcp-page"))
    app.get("/http", VueComponent("http-page"))
    app.get("/https", VueComponent("https-page"))
    app.get("/ping", VueComponent("ping-page"))
    app.get("/traceroute", VueComponent("traceroute-page"))
    app.get("/whois", VueComponent("whois-page"))
    app.get("/blacklist", VueComponent("blacklist-page"))
    app.get("/whatismyip", VueComponent("whatismyip-page"))
    app.get("/subnet", VueComponent("subnet-page"))
    app.get("/password", VueComponent("password-page"))
    app.get("/email-extract", VueComponent("email-extract-page"))
    app.get("/spf-generator", VueComponent("spf-generator-page"))
    app.get("/dmarc-generator", VueComponent("dmarc-generator-page"))
    app.get("/dns-health", VueComponent("dns-health-page"))
    app.get("/domain-health", VueComponent("domain-health-page"))
    app.get("/about", VueComponent("about-page"))
}

private fun findVueRootDir(): File? {
    val candidates = listOfNotNull(
        System.getenv("RUNFILES_DIR")?.let { File(it, "_main/web/src/main/resources") },
        File("web/src/main/resources"),
        File(System.getProperty("user.dir"), "web/src/main/resources"),
    )
    return candidates.firstOrNull { it.isDirectory && it.resolve("vue/layout.html").exists() }?.canonicalFile
}

private fun materializeVueRootDirFromClasspath(): File? {
    val root = Files.createTempDirectory("nortools-javalin-vue").toFile()
    val files = listOf(
        "vue/layout.html",
        "vue/components/home-page.vue",
        "vue/components/dns-lookup-page.vue",
        "vue/components/dnssec-lookup-page.vue",
        "vue/components/reverse-dns-page.vue",
        "vue/components/spf-page.vue",
        "vue/components/dkim-page.vue",
        "vue/components/help-mta-sts-dns-page.vue",
        "vue/components/dmarc-page.vue",
        "vue/components/tcp-page.vue",
        "vue/components/http-page.vue",
        "vue/components/https-page.vue",
        "vue/components/ping-page.vue",
        "vue/components/traceroute-page.vue",
        "vue/components/whois-page.vue",
        "vue/components/blacklist-page.vue",
        "vue/components/whatismyip-page.vue",
        "vue/components/subnet-page.vue",
        "vue/components/password-page.vue",
        "vue/components/email-extract-page.vue",
        "vue/components/spf-generator-page.vue",
        "vue/components/dmarc-generator-page.vue",
        "vue/components/dns-health-page.vue",
        "vue/components/domain-health-page.vue",
        "vue/components/about-page.vue",
    )
    val loader = Thread.currentThread().contextClassLoader
    for (relative in files) {
        val stream = sequenceOf(
            relative,
            "web/src/main/resources/$relative",
        ).mapNotNull { path -> loader.getResourceAsStream(path) }.firstOrNull() ?: return null
        val outFile = File(root, relative)
        outFile.parentFile?.mkdirs()
        outFile.outputStream().use { output -> stream.use { input -> input.copyTo(output) } }
    }
    return root
}
