// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.HandlerThread
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

    /** Human-readable transport of the current network, for diagnostics ("Wi-Fi", "mobile"). */
    fun describe(network: Network?): String {
        val caps = network?.let { cm.getNetworkCapabilities(it) } ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "other"
        }
    }

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
                    if (_current.value == network) _current.value = null
                }
            },
            Handler(thread.looper),
        )
    }
}
