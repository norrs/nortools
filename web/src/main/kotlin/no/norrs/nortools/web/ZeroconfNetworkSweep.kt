package no.norrs.nortools.web

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal fun scanLocalSmbHosts(): List<SmbSweepHit> {
    val candidates = localPrivateIpv4Candidates()
    if (candidates.isEmpty()) return emptyList()
    val executor = Executors.newFixedThreadPool(32) { runnable ->
        Thread(runnable, "smb-sweep-worker").apply { isDaemon = true }
    }
    return try {
        executor.invokeAll(
            candidates.map { address ->
                Callable {
                    if (!isTcpOpen(address, 445, 250)) return@Callable null
                    SmbSweepHit(
                        address = address,
                        hostname = reverseHostname(address),
                        wsdTcpOpen = isTcpOpen(address, 3702, 250),
                    )
                }
            },
            8,
            TimeUnit.SECONDS,
        ).mapNotNull { future ->
            runCatching { future.get() }.getOrNull()
        }
    } finally {
        executor.shutdownNow()
    }
}

private fun localPrivateIpv4Candidates(): List<String> {
    val ranges = linkedSetOf<String>()
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { iface -> iface.interfaceAddresses.asSequence() }
        .mapNotNull { address -> address.address as? Inet4Address }
        .map { it.hostAddress }
        .filter(::isPrivateIpv4)
        .forEach { address ->
            val parts = address.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size == 4) {
                for (host in 1..254) {
                    val candidate = "${parts[0]}.${parts[1]}.${parts[2]}.$host"
                    if (candidate != address) ranges += candidate
                }
            }
        }
    return ranges.toList()
}

private fun isPrivateIpv4(address: String): Boolean {
    val parts = address.split('.').mapNotNull { it.toIntOrNull() }
    if (parts.size != 4) return false
    return parts[0] == 10 ||
        (parts[0] == 172 && parts[1] in 16..31) ||
        (parts[0] == 192 && parts[1] == 168)
}

private fun isTcpOpen(address: String, port: Int, timeoutMs: Int): Boolean =
    runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, port), timeoutMs)
        }
        true
    }.getOrDefault(false)

private fun reverseHostname(address: String): String? =
    runCatching { InetAddress.getByName(address).canonicalHostName }
        .getOrNull()
        ?.takeIf { it.isNotBlank() && it != address }
