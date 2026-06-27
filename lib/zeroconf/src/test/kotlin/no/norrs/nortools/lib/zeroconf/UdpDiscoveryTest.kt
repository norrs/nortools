package no.norrs.nortools.lib.zeroconf

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class UdpDiscoveryTest {
    @Test
    fun `sendAndReceive records sent packet and response`() {
        val server = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            server.use {
                val buffer = ByteArray(128)
                val request = DatagramPacket(buffer, buffer.size)
                it.receive(request)
                val response = "pong".toByteArray()
                it.send(DatagramPacket(response, response.size, request.address, request.port))
            }
        }

        val observations = BoundedUdpDiscovery(
            protocol = "test",
            timeout = Duration.ofSeconds(2),
        ).sendAndReceive(
            payload = "ping".toByteArray(),
            targetAddress = "127.0.0.1",
            targetPort = server.localPort,
            maxPackets = 1,
        )

        executor.shutdown()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        assertEquals(2, observations.size)
        assertEquals(UdpDirection.SENT, observations[0].direction)
        assertEquals(UdpDirection.RECEIVED, observations[1].direction)
        assertArrayEquals("pong".toByteArray(), observations[1].payload)
        assertEquals("70 6f 6e 67", observations[1].payloadPreviewHex)
    }

    @Test
    fun `listen records bounded inbound datagrams`() {
        val bindPort = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit<List<UdpObservation>> {
            BoundedUdpDiscovery(
                protocol = "test-listen",
                timeout = Duration.ofSeconds(2),
            ).listen(
                bindAddress = "127.0.0.1",
                bindPort = bindPort,
                maxPackets = 1,
            )
        }

        Thread.sleep(150)
        DatagramSocket().use { sender ->
            val payload = "hello".toByteArray()
            sender.send(DatagramPacket(payload, payload.size, InetAddress.getByName("127.0.0.1"), bindPort))
        }

        val observations = future.get(3, TimeUnit.SECONDS)
        executor.shutdown()
        assertEquals(1, observations.size)
        assertEquals(UdpDirection.RECEIVED, observations.single().direction)
        assertEquals("test-listen", observations.single().protocol)
        assertArrayEquals("hello".toByteArray(), observations.single().payload)
    }
}
