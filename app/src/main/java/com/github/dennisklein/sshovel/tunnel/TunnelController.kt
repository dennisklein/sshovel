// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.tunnel

import android.content.Context
import android.content.Intent
import com.github.dennisklein.sshovel.data.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What [TunnelController] asks the VPN service to do. */
interface TunnelCommands {
    fun connect(profileId: String)
    fun disconnect()
    fun retryNow()
}

/** Delivers commands to [SshovelVpnService] as intents. */
class ServiceTunnelCommands(private val context: Context) : TunnelCommands {
    override fun connect(profileId: String) {
        context.startForegroundService(SshovelVpnService.connectIntent(context, profileId))
    }

    override fun disconnect() = send(SshovelVpnService.ACTION_DISCONNECT)

    override fun retryNow() = send(SshovelVpnService.ACTION_RETRY)

    private fun send(action: String) {
        context.startService(Intent(context, SshovelVpnService::class.java).setAction(action))
    }
}

/**
 * Application-wide entry point to the tunnel. UI, tile, and notification read [state] and call
 * [connect] / [disconnect]; [SshovelVpnService] does the work and reports back.
 */
class TunnelController(
    private val commands: TunnelCommands,
    networkAvailable: StateFlow<Boolean>,
    scope: CoroutineScope,
) {
    private val engineState = MutableStateFlow<TunnelState>(TunnelState.Off)
    private val _activeProfile = MutableStateFlow<Profile?>(null)
    private val _stats = MutableStateFlow(TunnelStats())

    /** Engine state, with NETWORK_LOST shown while reconnecting without any network. */
    val state: StateFlow<TunnelState> = combine(engineState, networkAvailable) { s, net ->
        if (s is TunnelState.Reconnecting && !net) s.copy(code = Codes.NETWORK_LOST) else s
    }.stateIn(scope, SharingStarted.Eagerly, TunnelState.Off)

    val activeProfile: StateFlow<Profile?> = _activeProfile.asStateFlow()
    val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

    fun connect(profileId: String) = commands.connect(profileId)

    fun disconnect() = commands.disconnect()

    fun retryNow() = commands.retryNow()

    /** The user dismissed a stopping error (e.g. "Disconnect" on HOST_KEY_MISMATCH): show Off. */
    fun acknowledgeError() {
        if (engineState.value is TunnelState.NeedsAttention) {
            engineState.value = TunnelState.Off
            _activeProfile.value = null
        }
    }

    // Called by SshovelVpnService.
    internal fun onState(state: TunnelState) {
        engineState.value = state
        if (state == TunnelState.Off) _stats.value = TunnelStats()
    }

    internal fun onProfile(profile: Profile?) {
        _activeProfile.value = profile
    }

    internal fun onStats(stats: TunnelStats) {
        _stats.value = stats
    }
}
