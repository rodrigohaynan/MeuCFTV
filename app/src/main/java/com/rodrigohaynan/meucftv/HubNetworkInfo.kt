package com.rodrigohaynan.meucftv

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class HubAddress(
    val interfaceName: String,
    val address: String,
    val isTailscale: Boolean
)

object HubNetworkInfo {

    fun addresses(): List<HubAddress> {
        val result =
            ArrayList<HubAddress>()

        val interfaces = runCatching {
            Collections.list(
                NetworkInterface
                    .getNetworkInterfaces()
            )
        }.getOrDefault(
            emptyList()
        )

        for (network in interfaces) {
            if (
                !runCatching {
                    network.isUp
                }.getOrDefault(false) ||
                runCatching {
                    network.isLoopback
                }.getOrDefault(true)
            ) {
                continue
            }

            val name =
                network.name.orEmpty()

            for (
                address in
                Collections.list(
                    network.inetAddresses
                )
            ) {
                if (
                    address !is Inet4Address ||
                    address.isLoopbackAddress ||
                    address.isLinkLocalAddress
                ) {
                    continue
                }

                val host =
                    address.hostAddress
                        ?: continue

                result += HubAddress(
                    interfaceName = name,
                    address = host,
                    isTailscale =
                        isTailscaleAddress(
                            name,
                            host
                        )
                )
            }
        }

        return result
            .distinctBy {
                it.address
            }
            .sortedWith(
                compareByDescending<HubAddress> {
                    it.isTailscale
                }.thenBy {
                    it.interfaceName
                }
            )
    }

    fun bestRemoteAddress(): String? =
        addresses()
            .firstOrNull {
                it.isTailscale
            }
            ?.address

    private fun isTailscaleAddress(
        interfaceName: String,
        address: String
    ): Boolean {
        val lower =
            interfaceName.lowercase()

        if (
            lower.contains("tailscale") ||
            lower == "tun0"
        ) {
            return true
        }

        val parts =
            address.split('.')
                .mapNotNull {
                    it.toIntOrNull()
                }

        if (parts.size != 4) {
            return false
        }

        return parts[0] == 100 &&
            parts[1] in 64..127
    }
}
