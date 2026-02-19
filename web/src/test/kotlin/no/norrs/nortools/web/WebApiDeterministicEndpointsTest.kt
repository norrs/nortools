package no.norrs.nortools.web

import com.fasterxml.jackson.databind.ObjectMapper
import io.javalin.Javalin
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class WebApiDeterministicEndpointsTest {
    companion object {
        private lateinit var app: Javalin
        private lateinit var baseUrl: String
        private val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build()
        private val mapper = ObjectMapper()

        @JvmStatic
        @BeforeAll
        fun start() {
            val port = ServerSocket(0).use { it.localPort }
            app = startServer(port = port)
            baseUrl = "http://127.0.0.1:$port"
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            app.stop()
        }
    }

    @Test
    fun `password endpoint returns requested count and length`() {
        val response = get("/api/password?length=20&count=3&upper=true&lower=false&digits=true&special=false")
        assertEquals(200, response.statusCode())

        val json = mapper.readTree(response.body())
        assertEquals(3, json["count"].asInt())
        assertEquals(20, json["length"].asInt())
        assertEquals(3, json["passwords"].size())
        assertTrue(json["passwords"].all { it.asText().length == 20 })
    }

    @Test
    fun `subnet endpoint returns expected network details`() {
        val response = get("/api/subnet/192.168.1.5%2F24")
        assertEquals(200, response.statusCode())

        val json = mapper.readTree(response.body())
        assertEquals("192.168.1.5/24", json["cidr"].asText())
        assertEquals("192.168.1.0", json["networkAddress"].asText())
        assertEquals("192.168.1.255", json["broadcastAddress"].asText())
    }

    @Test
    fun `subnet endpoint returns error on invalid cidr`() {
        val response = get("/api/subnet/not-a-cidr")
        assertEquals(200, response.statusCode())

        val json = mapper.readTree(response.body())
        assertTrue(json["error"].asText().contains("Invalid CIDR"))
    }

    @Test
    fun `email extract endpoint returns unique emails and domain counts`() {
        val body = """
            hello alice@example.com and bob@example.com.
            duplicate alice@example.com and user@test.org
        """.trimIndent()
        val response = post("/api/email-extract", body, "text/plain")
        assertEquals(200, response.statusCode())

        val json = mapper.readTree(response.body())
        assertEquals(3, json["totalFound"].asInt())
        assertEquals(2, json["uniqueDomains"].asInt())
        assertEquals(2, json["domainBreakdown"]["example.com"].asInt())
        assertEquals(1, json["domainBreakdown"]["test.org"].asInt())
    }

    @Test
    fun `generator endpoints return valid records`() {
        val spf = get("/api/spf-generator?includes=spf.google.com&all=~all")
        assertEquals(200, spf.statusCode())
        val spfJson = mapper.readTree(spf.body())
        assertTrue(spfJson["record"].asText().startsWith("v=spf1"))
        assertTrue(spfJson["record"].asText().contains("include:spf.google.com"))

        val dmarc = get("/api/dmarc-generator?policy=reject&pct=50")
        assertEquals(200, dmarc.statusCode())
        val dmarcJson = mapper.readTree(dmarc.body())
        assertTrue(dmarcJson["record"].asText().contains("v=DMARC1"))
        assertTrue(dmarcJson["record"].asText().contains("p=reject"))
        assertTrue(dmarcJson["record"].asText().contains("pct=50"))
    }

    @Test
    fun `about endpoint returns metadata envelope`() {
        val response = get("/api/about")
        assertEquals(200, response.statusCode())

        val json = mapper.readTree(response.body())
        assertEquals("NorTools", json["appName"].asText())
        assertTrue(json.has("version"))
        assertTrue(json.has("build"))
        assertTrue(json.has("credits"))
        assertTrue(json.has("inspiration"))
        assertTrue(json.has("rfc"))
        assertTrue(json["build"]["target"].asText().isNotBlank())
        assertTrue(json["build"]["mainClass"].asText().isNotBlank())
        assertTrue(json["build"]["kremaVersion"].asText().isNotBlank())
        assertTrue(json["build"]["buildTime"].asText().isNotBlank())
        assertTrue(json["build"]["buildTimestamp"].asText().isNotBlank())
    }

    private fun get(path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .timeout(Duration.ofSeconds(8))
            .GET()
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun post(path: String, body: String, contentType: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .timeout(Duration.ofSeconds(8))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
