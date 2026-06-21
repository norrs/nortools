package no.norrs.nortools.lib.zeroconf

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

enum class IpFamily {
    IPV4,
    IPV6,
    BOTH,
    ;

    fun allowsIpv4(): Boolean = this == IPV4 || this == BOTH

    fun allowsIpv6(): Boolean = this == IPV6 || this == BOTH

    companion object {
        fun fromCli(value: String): IpFamily =
            when (value.lowercase()) {
                "ipv4", "4" -> IPV4
                "ipv6", "6" -> IPV6
                "both", "dual" -> BOTH
                else -> throw IllegalArgumentException("Unknown IP family: $value")
            }
    }
}

data class ZeroconfInterface(
    val name: String,
    val displayName: String,
    val supportsMulticast: Boolean,
    val isLoopback: Boolean,
    val ipv4Addresses: List<String>,
    val ipv6Addresses: List<String>,
)

object ZeroconfInterfaces {
    fun list(ipFamily: IpFamily = IpFamily.BOTH, includeLoopback: Boolean = false): List<ZeroconfInterface> =
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { includeLoopback || !it.isLoopback }
            .map { iface ->
                val addresses = iface.inetAddresses.asSequence().toList()
                ZeroconfInterface(
                    name = iface.name,
                    displayName = iface.displayName ?: iface.name,
                    supportsMulticast = iface.supportsMulticast(),
                    isLoopback = iface.isLoopback,
                    ipv4Addresses = if (ipFamily.allowsIpv4()) {
                        addresses.filterIsInstance<Inet4Address>().map { it.hostAddress }
                    } else {
                        emptyList()
                    },
                    ipv6Addresses = if (ipFamily.allowsIpv6()) {
                        addresses.filterIsInstance<Inet6Address>().map { it.hostAddress }
                    } else {
                        emptyList()
                    },
                )
            }
            .filter { it.ipv4Addresses.isNotEmpty() || it.ipv6Addresses.isNotEmpty() }
            .toList()
}
