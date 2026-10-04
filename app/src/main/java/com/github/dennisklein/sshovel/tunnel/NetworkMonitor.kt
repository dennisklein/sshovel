// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.HandlerThread
import java.net.Inet4Address
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope

/**
 * Tracks the best non-VPN network with internet (ARCHITECTURE §7). SSH sockets are protected
 * onto it implicitly (the default non-VPN route), direct DNS goes to it explicitly, and the VPN
 * declares it as its underlying network.
 */
class NetworkMonitor(context: Context, scope: CoroutineScope) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _current = MutableStateFlow<Network?>(null)

    /** The current underlying network, or null while there is none. */
    val current: StateFlow<Network?> = _current.asStateFlow()
    val available: StateFlow<Boolean> = current.map { it != null }.stateIn(scope, SharingStarted.Eagerly, false)

    private val _privateDnsHost = MutableStateFlow<String?>(null)

    /**
     * The Private DNS hostname when the underlying network uses Private DNS in strict mode: apps
     * then resolve through it and never reach sshovel's resolver (ARCHITECTURE §5, "Known
     * interference"). Null in Automatic or Off mode.
     */
    val privateDnsHost: StateFlow<String?> = _privateDnsHost.asStateFlow()

    private val _localSubnets = MutableStateFlow<List<String>>(emptyList())

    /**
     * The IPv4 subnets of the current network when it's Wi-Fi or Ethernet, e.g. "192.168.1.0/24":
     * devices there become unreachable for tunneled apps if a route covers them (ARCHITECTURE §10),
     * so the profile editor warns. Empty on mobile data or without a network.
     */
    val localSubnets: StateFlow<List<String>> = _localSubnets.asStateFlow()

    /** Human-readable transport of the current network, for diagnostics ("Wi-Fi", "mobile"). */
    fun describe(network: Network?): String = network?.let { transportOf(it)?.label } ?: "none"

    /** The transport of [network], or null if it's gone. */
    fun transportOf(network: Network): Transport? {
        val caps = cm.getNetworkCapabilities(network) ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.MOBILE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
            else -> Transport.OTHER
        }
    }

    /** Kinds of underlying network, for the Reconnecting copy ("from Wi-Fi to mobile data"). */
    enum class Transport(val label: String) { WIFI("Wi-Fi"), MOBILE("mobile"), ETHERNET("Ethernet"), OTHER("other") }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val thread = HandlerThread("sshovel-network").apply { start() }
        cm.registerBestMatchingNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _current.value = network
                }

                override fun onLost(network: Network) {
                    if (_current.value == network) {
                        _current.value = null
                        _privateDnsHost.value = null
                        _localSubnets.value = emptyList()
                    }
                }

                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                    if (_current.value != network) return
                    _privateDnsHost.value = lp.privateDnsServerName
                    val local = transportOf(network) in setOf(Transport.WIFI, Transport.ETHERNET)
                    _localSubnets.value = if (!local) emptyList() else lp.linkAddresses
                        .filter { it.address is Inet4Address && it.prefixLength in 1..31 }
                        .map { subnetOf(it.address.hostAddress.orEmpty(), it.prefixLength) }
                        .distinct()
                }
            },
            Handler(thread.looper),
        )
    }

    companion object {
        /** "192.168.1.23", 24 → "192.168.1.0/24". */
        fun subnetOf(address: String, prefix: Int): String {
            val a = address.split('.').fold(0L) { acc, o -> (acc shl 8) or o.toLong() }
            val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
            val n = a and mask
            return "${(n shr 24) and 255}.${(n shr 16) and 255}.${(n shr 8) and 255}.${n and 255}/$prefix"
        }
    }
}
