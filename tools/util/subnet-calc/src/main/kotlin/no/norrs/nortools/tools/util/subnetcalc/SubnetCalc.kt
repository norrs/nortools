package no.norrs.nortools.tools.util.subnetcalc

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import no.norrs.nortools.lib.cli.BaseCommand
import java.net.InetAddress

/**
 * Subnet Calculator tool — calculates IPv4 subnet details from CIDR notation.
 *
 * Supports CIDR notation (e.g., 192.168.1.0/24) and provides network address,
 * broadcast address, host range, and number of usable hosts.
 * Based on RFC 4632 (CIDR) and RFC 4291 (IPv6 Addressing).
 */
class SubnetCalcCommand : BaseCommand(
    name = "subnet-calc",
    helpText = "Calculate subnet details from CIDR notation (e.g., 192.168.1.0/24)",
) {
    private val cidr by argument(help = "CIDR notation (e.g., 192.168.1.0/24 or 10.0.0.0/8)")

    override fun run() {
        val formatter = createFormatter()

        val parts = cidr.split("/")
        if (parts.size != 2) {
            echo("Invalid CIDR notation. Use format: IP/prefix (e.g., 192.168.1.0/24)")
            return
        }

        val ipStr = parts[0]
        val prefix = parts[1].toIntOrNull()
        if (prefix == null) {
            echo("Invalid prefix length: ${parts[1]}")
            return
        }

        val addr = InetAddress.getByName(ipStr)
        val addrBytes = addr.address

        if (addrBytes.size == 4) {
            calculateIPv4(ipStr, prefix, addrBytes, formatter)
        } else {
            calculateIPv6(ipStr, prefix, addrBytes, formatter)
        }
    }

    private fun calculateIPv4(
        ipStr: String,
        prefix: Int,
        addrBytes: ByteArray,
        formatter: no.norrs.nortools.lib.output.OutputFormatter,
    ) {
        if (prefix < 0 || prefix > 32) {
            echo("Invalid IPv4 prefix length: $prefix (must be 0-32)")
            return
        }

        val ipInt = addrBytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val network = ipInt and mask
        val broadcast = network or mask.inv() and 0xFFFFFFFFL
        val firstHost = if (prefix < 31) network + 1 else network
        val lastHost = if (prefix < 31) broadcast - 1 else broadcast
        val totalHosts = if (prefix <= 30) (1L shl (32 - prefix)) - 2 else if (prefix == 31) 2L else 1L

        val details = linkedMapOf<String, Any?>(
            "CIDR" to "$ipStr/$prefix",
            "IP Address" to ipStr,
            "Network Address" to longToIpv4(network),
            "Broadcast Address" to longToIpv4(broadcast),
            "Subnet Mask" to longToIpv4(mask),
            "Wildcard Mask" to longToIpv4(mask.inv() and 0xFFFFFFFFL),
            "First Host" to longToIpv4(firstHost),
            "Last Host" to longToIpv4(lastHost),
            "Total Hosts" to totalHosts,
            "Prefix Length" to "/$prefix",
            "IP Class" to when {
                ipInt and 0x80000000L == 0L -> "A"
                ipInt and 0xC0000000L == 0x80000000L -> "B"
                ipInt and 0xE0000000L == 0xC0000000L -> "C"
                ipInt and 0xF0000000L == 0xE0000000L -> "D (Multicast)"
                else -> "E (Reserved)"
            },
            "Private" to when {
                network in 0x0A000000L..0x0AFFFFFFL -> "Yes (10.0.0.0/8)"
                network in 0xAC100000L..0xAC1FFFFFL -> "Yes (172.16.0.0/12)"
                network in 0xC0A80000L..0xC0A8FFFFL -> "Yes (192.168.0.0/16)"
                else -> "No"
            },
        )

        echo(formatter.formatDetail(details))
    }

    private fun calculateIPv6(
        ipStr: String,
        prefix: Int,
        addrBytes: ByteArray,
        formatter: no.norrs.nortools.lib.output.OutputFormatter,
    ) {
        if (prefix < 0 || prefix > 128) {
            echo("Invalid IPv6 prefix length: $prefix (must be 0-128)")
            return
        }

        val totalAddresses = if (prefix == 128) "1" else "2^${128 - prefix}"

        val details = linkedMapOf<String, Any?>(
            "CIDR" to "$ipStr/$prefix",
            "IP Address" to ipStr,
            "Full Address" to addrBytes.joinToString(":") { "%02x".format(it) }
                .chunked(5).joinToString(":"),
            "Prefix Length" to "/$prefix",
            "Total Addresses" to totalAddresses,
            "Type" to when {
                addrBytes[0] == 0xFE.toByte() && addrBytes[1].toInt() and 0xC0 == 0x80 -> "Link-Local"
                addrBytes[0] == 0xFD.toByte() || addrBytes[0] == 0xFC.toByte() -> "Unique Local"
                addrBytes[0] == 0xFF.toByte() -> "Multicast"
                addrBytes.all { it == 0.toByte() } -> "Unspecified"
                addrBytes.dropLast(1).all { it == 0.toByte() } && addrBytes.last() == 1.toByte() -> "Loopback"
                else -> "Global Unicast"
            },
        )

        echo(formatter.formatDetail(details))
    }

    private fun longToIpv4(value: Long): String {
        return "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"
    }
}

fun main(args: Array<String>) = SubnetCalcCommand().main(args)
