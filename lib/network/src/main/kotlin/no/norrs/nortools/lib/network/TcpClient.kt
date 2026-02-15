package no.norrs.nortools.lib.network

import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration

/**
 * Result of a TCP connection attempt.
 */
data class TcpConnectResult(
    val host: String,
    val port: Int,
    val connected: Boolean,
    val responseTimeMs: Long,
    val error: String? = null,
    val banner: String? = null,
)

/**
 * TCP client for port connectivity checks and banner grabbing.
 */
class TcpClient(
    private val timeout: Duration = Duration.ofSeconds(10),
) {
    /**
     * Attempt a TCP connection to the given host and port.
     * Optionally grabs the server banner if [grabBanner] is true.
     */
    fun connect(host: String, port: Int, grabBanner: Boolean = false): TcpConnectResult {
        val startTime = System.currentTimeMillis()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeout.toMillis().toInt())
                val elapsed = System.currentTimeMillis() - startTime

                val banner = if (grabBanner) {
                    try {
                        socket.soTimeout = timeout.toMillis().toInt()
                        val buffer = ByteArray(1024)
                        val bytesRead = socket.getInputStream().read(buffer)
                        if (bytesRead > 0) String(buffer, 0, bytesRead).trim() else null
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }

                TcpConnectResult(
                    host = host,
                    port = port,
                    connected = true,
                    responseTimeMs = elapsed,
                    banner = banner,
                )
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            TcpConnectResult(
                host = host,
                port = port,
                connected = false,
                responseTimeMs = elapsed,
                error = e.message,
            )
        }
    }
}
