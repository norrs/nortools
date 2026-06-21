package no.norrs.nortools.lib.zeroconf

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.time.Duration
import java.time.Instant

enum class UdpDirection {
    SENT,
    RECEIVED,
}

data class UdpEndpoint(
    val address: String,
    val port: Int,
)

data class UdpObservation(
    val protocol: String,
    val direction: UdpDirection,
    val local: UdpEndpoint? = null,
    val remote: UdpEndpoint? = null,
    val interfaceName: String? = null,
    val multicastGroup: String? = null,
    val receivedAt: String = Instant.now().toString(),
    val rawLength: Int,
    val payload: ByteArray,
    val warning: String? = null,
    val error: String? = null,
) {
    val payloadPreviewHex: String =
        payload.take(64).joinToString(" ") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UdpObservation) return false

        return protocol == other.protocol &&
            direction == other.direction &&
            local == other.local &&
            remote == other.remote &&
            interfaceName == other.interfaceName &&
            multicastGroup == other.multicastGroup &&
            receivedAt == other.receivedAt &&
            rawLength == other.rawLength &&
            payload.contentEquals(other.payload) &&
            warning == other.warning &&
            error == other.error
    }

    override fun hashCode(): Int {
        var result = protocol.hashCode()
        result = 31 * result + direction.hashCode()
        result = 31 * result + (local?.hashCode() ?: 0)
        result = 31 * result + (remote?.hashCode() ?: 0)
        result = 31 * result + (interfaceName?.hashCode() ?: 0)
        result = 31 * result + (multicastGroup?.hashCode() ?: 0)
        result = 31 * result + receivedAt.hashCode()
        result = 31 * result + rawLength
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + (warning?.hashCode() ?: 0)
        result = 31 * result + (error?.hashCode() ?: 0)
        return result
    }
}

class BoundedUdpDiscovery(
    private val protocol: String,
    private val timeout: Duration = Duration.ofSeconds(5),
    private val maxPayloadBytes: Int = 4096,
) {
    fun listen(
        bindAddress: String = "0.0.0.0",
        bindPort: Int,
        maxPackets: Int,
    ): List<UdpObservation> {
        require(bindPort in 1..65535) { "UDP bind port must be between 1 and 65535" }
        require(maxPackets > 0) { "maxPackets must be positive" }

        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.soTimeout = timeout.toMillis().toInt()
        socket.bind(InetSocketAddress(InetAddress.getByName(bindAddress), bindPort))
        return socket.use { receive(it, maxPackets) }
    }

    fun sendAndReceive(
        payload: ByteArray,
        targetAddress: String,
        targetPort: Int,
        bindAddress: String? = null,
        bindPort: Int = 0,
        broadcast: Boolean = false,
        maxPackets: Int = 25,
    ): List<UdpObservation> {
        require(targetPort in 1..65535) { "UDP target port must be between 1 and 65535" }
        require(bindPort in 0..65535) { "UDP bind port must be between 0 and 65535" }
        require(maxPackets > 0) { "maxPackets must be positive" }

        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.broadcast = broadcast
        socket.soTimeout = timeout.toMillis().toInt()
        socket.bind(InetSocketAddress(bindAddress?.let { InetAddress.getByName(it) }, bindPort))

        return socket.use {
            val target = InetAddress.getByName(targetAddress)
            it.send(DatagramPacket(payload, payload.size, target, targetPort))

            val sent = UdpObservation(
                protocol = protocol,
                direction = UdpDirection.SENT,
                local = it.localEndpoint(),
                remote = UdpEndpoint(target.hostAddress, targetPort),
                rawLength = payload.size,
                payload = payload.copyOf(),
            )
            listOf(sent) + receive(it, maxPackets)
        }
    }

    private fun receive(socket: DatagramSocket, maxPackets: Int): List<UdpObservation> {
        val observations = mutableListOf<UdpObservation>()
        val buffer = ByteArray(maxPayloadBytes)

        while (observations.size < maxPackets) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val payload = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                observations += UdpObservation(
                    protocol = protocol,
                    direction = UdpDirection.RECEIVED,
                    local = socket.localEndpoint(),
                    remote = UdpEndpoint(packet.address.hostAddress, packet.port),
                    rawLength = payload.size,
                    payload = payload,
                )
            } catch (_: SocketTimeoutException) {
                break
            }
        }

        return observations
    }

    private fun DatagramSocket.localEndpoint(): UdpEndpoint =
        UdpEndpoint(localAddress.hostAddress, localPort)
}
