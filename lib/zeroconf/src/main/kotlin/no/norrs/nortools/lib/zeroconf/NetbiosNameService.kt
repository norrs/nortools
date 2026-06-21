package no.norrs.nortools.lib.zeroconf

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.Locale
import kotlin.random.Random

enum class NetbiosQuestionType(val code: Int) {
    NB(0x0020),
    NBSTAT(0x0021),
}

data class NetbiosName(
    val name: String,
    val suffix: Int,
    val group: Boolean,
    val flags: Int,
)

data class NetbiosAddressRecord(
    val name: String,
    val suffix: Int,
    val address: String,
    val flags: Int,
    val group: Boolean,
)

data class NetbiosResponse(
    val transactionId: Int,
    val sourceAddress: String,
    val sourcePort: Int,
    val response: Boolean,
    val opcode: Int,
    val authoritative: Boolean,
    val truncated: Boolean,
    val recursionDesired: Boolean,
    val recursionAvailable: Boolean,
    val resultCode: Int,
    val questionCount: Int,
    val answerCount: Int,
    val authorityCount: Int,
    val additionalCount: Int,
    val names: List<NetbiosName> = emptyList(),
    val addresses: List<NetbiosAddressRecord> = emptyList(),
    val rawLength: Int,
    val error: String? = null,
)

class NetbiosNameServiceClient(
    private val timeout: Duration = Duration.ofSeconds(5),
) {
    fun queryName(
        name: String,
        suffix: Int = 0x00,
        target: String = "255.255.255.255",
    ): List<NetbiosResponse> =
        sendQuestion(name = name, suffix = suffix, type = NetbiosQuestionType.NB, target = target)

    fun nodeStatus(host: String): List<NetbiosResponse> =
        sendQuestion(name = "*", suffix = 0x00, type = NetbiosQuestionType.NBSTAT, target = host)

    fun listen(bindAddress: String = "0.0.0.0", maxPackets: Int = 25): List<NetbiosResponse> {
        val socket = DatagramSocket(137, InetAddress.getByName(bindAddress))
        socket.soTimeout = timeout.toMillis().toInt()
        return socket.use { receiveResponses(it, transactionId = null, maxPackets = maxPackets) }
    }

    private fun sendQuestion(
        name: String,
        suffix: Int,
        type: NetbiosQuestionType,
        target: String,
    ): List<NetbiosResponse> {
        val targetAddress = InetAddress.getByName(target)
        require(targetAddress is Inet4Address) { "NetBIOS Name Service supports IPv4 targets only" }

        val transactionId = Random.nextInt(0, 0xffff)
        val payload = NetbiosCodec.buildQuestion(transactionId, name, suffix, type)
        val packet = DatagramPacket(payload, payload.size, targetAddress, 137)

        val socket = DatagramSocket()
        socket.broadcast = targetAddress.hostAddress == "255.255.255.255"
        socket.soTimeout = timeout.toMillis().toInt()

        return socket.use {
            it.send(packet)
            receiveResponses(it, transactionId = transactionId, maxPackets = 50)
        }
    }

    private fun receiveResponses(socket: DatagramSocket, transactionId: Int?, maxPackets: Int): List<NetbiosResponse> {
        val responses = mutableListOf<NetbiosResponse>()
        val buffer = ByteArray(1500)

        while (responses.size < maxPackets) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val bytes = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                val response = NetbiosCodec.parseResponse(bytes, packet.address.hostAddress, packet.port)
                if (transactionId == null || response.transactionId == transactionId) {
                    responses += response
                }
            } catch (_: SocketTimeoutException) {
                break
            } catch (e: Exception) {
                responses += NetbiosResponse(
                    transactionId = transactionId ?: 0,
                    sourceAddress = "",
                    sourcePort = 0,
                    response = false,
                    opcode = 0,
                    authoritative = false,
                    truncated = false,
                    recursionDesired = false,
                    recursionAvailable = false,
                    resultCode = 0,
                    questionCount = 0,
                    answerCount = 0,
                    authorityCount = 0,
                    additionalCount = 0,
                    rawLength = 0,
                    error = e.message,
                )
                break
            }
        }

        return responses
    }
}

object NetbiosCodec {
    fun buildQuestion(transactionId: Int, name: String, suffix: Int, type: NetbiosQuestionType): ByteArray {
        val out = ByteArrayOutputStream()
        writeU16(out, transactionId)
        writeU16(out, 0x0000)
        writeU16(out, 1)
        writeU16(out, 0)
        writeU16(out, 0)
        writeU16(out, 0)
        out.write(encodeName(name, suffix))
        writeU16(out, type.code)
        writeU16(out, 0x0001)
        return out.toByteArray()
    }

    fun encodeName(name: String, suffix: Int): ByteArray {
        require(suffix in 0..255) { "NetBIOS suffix must fit in one byte" }
        val rawName = if (name == "*") {
            "*".padEnd(15, ' ')
        } else {
            name.uppercase(Locale.ROOT).take(15).padEnd(15, ' ')
        }
        val bytes = (rawName.map { it.code.toByte() } + suffix.toByte()).toByteArray()

        val out = ByteArrayOutputStream()
        out.write(32)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            out.write('A'.code + ((value ushr 4) and 0x0f))
            out.write('A'.code + (value and 0x0f))
        }
        out.write(0)
        return out.toByteArray()
    }

    fun decodeEncodedName(packet: ByteArray, start: Int): Pair<String, Int> {
        var offset = start
        val first = packet.getU8(offset)
        if ((first and 0xc0) == 0xc0) {
            require(offset + 1 < packet.size) { "Truncated compressed name pointer" }
            val pointer = ((first and 0x3f) shl 8) or packet.getU8(offset + 1)
            return decodeEncodedName(packet, pointer).first to offset + 2
        }
        require(first == 32) { "Unsupported NetBIOS name length: $first" }
        offset++
        require(offset + 32 <= packet.size) { "Truncated NetBIOS name" }

        val decoded = ByteArray(16)
        for (i in 0 until 16) {
            val high = packet.getU8(offset++) - 'A'.code
            val low = packet.getU8(offset++) - 'A'.code
            require(high in 0..15 && low in 0..15) { "Invalid NetBIOS name encoding" }
            decoded[i] = ((high shl 4) or low).toByte()
        }
        require(offset < packet.size && packet.getU8(offset) == 0) { "NetBIOS name is not terminated" }
        offset++

        val name = decoded.copyOfRange(0, 15).toString(Charsets.US_ASCII).trimEnd()
        val suffix = decoded[15].toInt() and 0xff
        return "$name<${suffix.toString(16).padStart(2, '0').uppercase(Locale.ROOT)}>" to offset
    }

    fun parseResponse(packet: ByteArray, sourceAddress: String = "", sourcePort: Int = 0): NetbiosResponse {
        require(packet.size >= 12) { "Truncated NetBIOS header" }
        val transactionId = packet.getU16(0)
        val flags = packet.getU16(2)
        val questionCount = packet.getU16(4)
        val answerCount = packet.getU16(6)
        val authorityCount = packet.getU16(8)
        val additionalCount = packet.getU16(10)

        var offset = 12
        repeat(questionCount) {
            offset = skipQuestion(packet, offset)
        }

        val names = mutableListOf<NetbiosName>()
        val addresses = mutableListOf<NetbiosAddressRecord>()
        repeat(answerCount + authorityCount + additionalCount) {
            val parsed = parseResourceRecord(packet, offset)
            offset = parsed.nextOffset
            names += parsed.names
            addresses += parsed.addresses
        }

        return NetbiosResponse(
            transactionId = transactionId,
            sourceAddress = sourceAddress,
            sourcePort = sourcePort,
            response = (flags and 0x8000) != 0,
            opcode = (flags ushr 11) and 0x0f,
            authoritative = (flags and 0x0400) != 0,
            truncated = (flags and 0x0200) != 0,
            recursionDesired = (flags and 0x0100) != 0,
            recursionAvailable = (flags and 0x0080) != 0,
            resultCode = flags and 0x000f,
            questionCount = questionCount,
            answerCount = answerCount,
            authorityCount = authorityCount,
            additionalCount = additionalCount,
            names = names,
            addresses = addresses,
            rawLength = packet.size,
        )
    }

    private fun skipQuestion(packet: ByteArray, start: Int): Int {
        val (_, afterName) = decodeEncodedName(packet, start)
        require(afterName + 4 <= packet.size) { "Truncated NetBIOS question" }
        return afterName + 4
    }

    private fun parseResourceRecord(packet: ByteArray, start: Int): ParsedResourceRecord {
        val (owner, afterName) = decodeEncodedName(packet, start)
        require(afterName + 10 <= packet.size) { "Truncated NetBIOS resource record" }
        val type = packet.getU16(afterName)
        val rdLength = packet.getU16(afterName + 8)
        val rdataOffset = afterName + 10
        require(rdataOffset + rdLength <= packet.size) { "Truncated NetBIOS rdata" }

        val names = mutableListOf<NetbiosName>()
        val addresses = mutableListOf<NetbiosAddressRecord>()

        when (type) {
            NetbiosQuestionType.NB.code -> {
                var cursor = rdataOffset
                while (cursor + 6 <= rdataOffset + rdLength) {
                    val rrFlags = packet.getU16(cursor)
                    val address = "${packet.getU8(cursor + 2)}.${packet.getU8(cursor + 3)}.${packet.getU8(cursor + 4)}.${packet.getU8(cursor + 5)}"
                    val suffix = owner.substringAfter('<', "00").substringBefore('>').toIntOrNull(16) ?: 0
                    addresses += NetbiosAddressRecord(
                        name = owner.substringBefore('<'),
                        suffix = suffix,
                        address = address,
                        flags = rrFlags,
                        group = (rrFlags and 0x8000) != 0,
                    )
                    cursor += 6
                }
            }
            NetbiosQuestionType.NBSTAT.code -> {
                if (rdLength > 0) {
                    val count = packet.getU8(rdataOffset)
                    var cursor = rdataOffset + 1
                    repeat(count) {
                        if (cursor + 18 <= rdataOffset + rdLength) {
                            val name = packet.copyOfRange(cursor, cursor + 15).toString(Charsets.US_ASCII).trimEnd()
                            val suffix = packet.getU8(cursor + 15)
                            val nameFlags = packet.getU16(cursor + 16)
                            names += NetbiosName(
                                name = name,
                                suffix = suffix,
                                group = (nameFlags and 0x8000) != 0,
                                flags = nameFlags,
                            )
                            cursor += 18
                        }
                    }
                }
            }
        }

        return ParsedResourceRecord(afterName + 10 + rdLength, names, addresses)
    }

    private data class ParsedResourceRecord(
        val nextOffset: Int,
        val names: List<NetbiosName>,
        val addresses: List<NetbiosAddressRecord>,
    )

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xff)
        out.write(value and 0xff)
    }

    private fun ByteArray.getU8(offset: Int): Int {
        require(offset in indices) { "Offset out of packet bounds" }
        return this[offset].toInt() and 0xff
    }

    private fun ByteArray.getU16(offset: Int): Int {
        require(offset + 1 < size) { "Truncated unsigned short" }
        return (getU8(offset) shl 8) or getU8(offset + 1)
    }
}
