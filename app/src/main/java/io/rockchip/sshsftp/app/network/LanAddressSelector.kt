package io.rockchip.sshsftp.app.network

import java.net.Inet4Address
import java.net.NetworkInterface

object LanAddressSelector {
    fun select(addresses: Iterable<String>): String? = addresses.firstOrNull { address ->
        address.count { it == '.' } == 3 && address != "127.0.0.1" && !address.startsWith("0.")
    }

    fun current(): String? {
        val addresses = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
        return select(addresses)
    }
}
