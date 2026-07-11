package io.pocketssh.server.ssh

import java.net.NetworkInterface

internal fun networkInterfaceFallback(): String = runCatching {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .flatMap { network ->
            network.inetAddresses.toList().map { address ->
                "${network.name} ${address.hostAddress.orEmpty()}"
            }
        }
        .filter { it.substringAfter(' ').isNotBlank() }
        .joinToString("\n")
}.getOrDefault("")

internal fun unavailableAcceleratorRows(type: String, vendor: String? = null): List<List<String>> = buildList {
    add(listOf("type", type))
    vendor?.takeIf { it.isNotBlank() }?.let { add(listOf("vendor", it)) }
    add(listOf("status", "details unavailable on this Android device"))
}
