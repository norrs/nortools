package no.norrs.nortools.lib.zeroconf

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
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

data class MulticastSession(
    val observations: List<UdpObservation>,
    val warnings: List<String> = emptyList(),
)

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

    fun listenMulticast(
        groupAddress: String,
        bindPort: Int,
        bindAddress: String? = null,
        maxPackets: Int,
        ipFamily: IpFamily = IpFamily.IPV4,
    ): MulticastSession {
        require(bindPort in 1..65535) { "UDP bind port must be between 1 and 65535" }
        require(maxPackets > 0) { "maxPackets must be positive" }

        val group = InetAddress.getByName(groupAddress)
        val interfaces = resolveMulticastInterfaces(bindAddress, ipFamily)
        require(interfaces.isNotEmpty()) {
            "No multicast-capable interfaces available for ${ipFamily.name.lowercase()} and bindAddress=${bindAddress ?: "0.0.0.0"}"
        }

        val socket = MulticastSocket(null)
        socket.reuseAddress = true
        socket.soTimeout = timeout.toMillis().toInt()
        socket.bind(InetSocketAddress(bindPort))
        val warnings = mutableListOf<String>()
        val joinedInterfaces = mutableListOf<NetworkInterface>()
        for (iface in interfaces) {
            runCatching {
                socket.joinGroup(InetSocketAddress(group, bindPort), iface)
            }.onSuccess {
                joinedInterfaces += iface
                warnings += "Joined multicast group ${group.hostAddress}:$bindPort on interface ${iface.name}."
            }.onFailure { error ->
                warnings += "Failed to join multicast group ${group.hostAddress}:$bindPort on interface ${iface.name}: ${error.message ?: error::class.java.simpleName}"
            }
        }
        require(joinedInterfaces.isNotEmpty()) {
            "Could not join multicast group ${group.hostAddress}:$bindPort on any eligible interface."
        }

        return try {
            MulticastSession(
                observations = receive(socket, maxPackets, multicastGroup = group.hostAddress),
                warnings = warnings,
            )
        } finally {
            joinedInterfaces.forEach { iface ->
                runCatching { socket.leaveGroup(InetSocketAddress(group, bindPort), iface) }
            }
            socket.close()
        }
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

    fun sendAndReceiveMulticast(
        payload: ByteArray,
        groupAddress: String,
        groupPort: Int,
        bindAddress: String? = null,
        maxPackets: Int = 25,
        ipFamily: IpFamily = IpFamily.IPV4,
    ): MulticastSession {
        require(groupPort in 1..65535) { "UDP target port must be between 1 and 65535" }
        require(maxPackets > 0) { "maxPackets must be positive" }

        val group = InetAddress.getByName(groupAddress)
        val interfaces = resolveMulticastInterfaces(bindAddress, ipFamily)
        require(interfaces.isNotEmpty()) {
            "No multicast-capable interfaces available for ${ipFamily.name.lowercase()} and bindAddress=${bindAddress ?: "0.0.0.0"}"
        }

        val socket = MulticastSocket(null)
        socket.reuseAddress = true
        socket.soTimeout = timeout.toMillis().toInt()
        socket.bind(InetSocketAddress(groupPort))
        val warnings = mutableListOf<String>()
        val joinedInterfaces = mutableListOf<NetworkInterface>()
        for (iface in interfaces) {
            runCatching {
                socket.joinGroup(InetSocketAddress(group, groupPort), iface)
            }.onSuccess {
                joinedInterfaces += iface
                warnings += "Joined multicast group ${group.hostAddress}:$groupPort on interface ${iface.name}."
            }.onFailure { error ->
                warnings += "Failed to join multicast group ${group.hostAddress}:$groupPort on interface ${iface.name}: ${error.message ?: error::class.java.simpleName}"
            }
        }
        require(joinedInterfaces.isNotEmpty()) {
            "Could not join multicast group ${group.hostAddress}:$groupPort on any eligible interface."
        }
        socket.networkInterface = joinedInterfaces.first()

        return try {
            socket.send(DatagramPacket(payload, payload.size, group, groupPort))
            val sent = UdpObservation(
                protocol = protocol,
                direction = UdpDirection.SENT,
                local = socket.localEndpoint(),
                remote = UdpEndpoint(group.hostAddress, groupPort),
                interfaceName = joinedInterfaces.first().name,
                multicastGroup = group.hostAddress,
                rawLength = payload.size,
                payload = payload.copyOf(),
            )
            MulticastSession(
                observations = listOf(sent) + receive(socket, maxPackets, multicastGroup = group.hostAddress),
                warnings = warnings,
            )
        } finally {
            joinedInterfaces.forEach { iface ->
                runCatching { socket.leaveGroup(InetSocketAddress(group, groupPort), iface) }
            }
            socket.close()
        }
    }

    private fun receive(
        socket: DatagramSocket,
        maxPackets: Int,
        multicastGroup: String? = null,
    ): List<UdpObservation> {
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
                    multicastGroup = multicastGroup,
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

    internal fun resolveMulticastInterfaces(
        bindAddress: String?,
        ipFamily: IpFamily,
    ): List<NetworkInterface> {
        val requested = bindAddress?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
        if (requested != null) {
            val address = InetAddress.getByName(requested)
            require(address is Inet4Address || ipFamily.allowsIpv6()) {
                "Requested bindAddress '$bindAddress' does not match the selected IP family."
            }
            val iface = NetworkInterface.getByInetAddress(address)
                ?: throw IllegalArgumentException("No network interface found for bindAddress '$bindAddress'")
            require(iface.supportsMulticast()) { "Interface '${iface.name}' does not support multicast" }
            return listOf(iface)
        }

        return NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp }
            .filter { !it.isLoopback }
            .filter { it.supportsMulticast() }
            .filter { iface ->
                val addresses = iface.inetAddresses.asSequence().toList()
                (ipFamily.allowsIpv4() && addresses.any { it is Inet4Address }) ||
                    (ipFamily.allowsIpv6() && addresses.any { it !is Inet4Address })
            }
            .toList()
    }
}
