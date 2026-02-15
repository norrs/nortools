package no.norrs.nortools.lib.network

import java.net.URI
import java.net.http.HttpClient as JHttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession

/**
 * Result of an HTTP request.
 */
data class HttpResult(
    val url: String,
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val responseTimeMs: Long,
    val body: String? = null,
    val error: String? = null,
    val sslSession: SslInfo? = null,
)

/**
 * SSL/TLS session information.
 */
data class SslInfo(
    val protocol: String,
    val cipherSuite: String,
    val peerCertificates: List<String>,
)

/**
 * HTTP client for making HTTP/HTTPS requests with timing and SSL info.
 */
class HttpClient(
    private val timeout: Duration = Duration.ofSeconds(10),
    private val followRedirects: Boolean = true,
) {
    private val client: JHttpClient = JHttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(
            if (followRedirects) JHttpClient.Redirect.NORMAL else JHttpClient.Redirect.NEVER,
        )
        .build()

    /**
     * Perform an HTTP GET request.
     */
    fun get(url: String, includeBody: Boolean = false): HttpResult {
        val startTime = System.currentTimeMillis()
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val elapsed = System.currentTimeMillis() - startTime

            val sslInfo = response.sslSession().map { session ->
                extractSslInfo(session)
            }.orElse(null)

            HttpResult(
                url = response.uri().toString(),
                statusCode = response.statusCode(),
                headers = response.headers().map(),
                responseTimeMs = elapsed,
                body = if (includeBody) response.body() else null,
                sslSession = sslInfo,
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            HttpResult(
                url = url,
                statusCode = -1,
                headers = emptyMap(),
                responseTimeMs = elapsed,
                error = e.message,
            )
        }
    }

    private fun extractSslInfo(session: SSLSession): SslInfo {
        return SslInfo(
            protocol = session.protocol,
            cipherSuite = session.cipherSuite,
            peerCertificates = try {
                session.peerCertificates.map { it.toString() }
            } catch (_: Exception) {
                emptyList()
            },
        )
    }
}
