package no.norrs.nortools.desktop

import build.krema.core.Krema
import no.norrs.nortools.web.FrontendMode
import no.norrs.nortools.web.startServer

fun main(args: Array<String>) {
    val devMode = args.contains("--dev")

    // Start the embedded Javalin web server (Vue SPA + API routes)
    // Port 0 picks a random available port
    val server = startServer(FrontendMode.CLASSPATH_SPA, port = 0)
    val serverUrl = "http://localhost:${server.port()}"
    println("[NorTools] Embedded server running at $serverUrl")

    val app = Krema.app()
        .title("NorTools")
        .version("0.1.0")
        .identifier("no.norrs.nortools")
        .size(1200, 800)
        .minSize(900, 600)
        .debug(devMode)
        .devUrl(serverUrl)

    println("[NorTools] Opening desktop window")
    app.run()

    // Shut down the server when the window closes
    server.stop()
}

