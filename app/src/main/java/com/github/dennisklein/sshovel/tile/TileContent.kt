// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.tile

import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.tunnel.TunnelState

/** What the Quick Settings tile shows (DESIGN_BRIEF §7). The label is always "sshovel". */
data class TileContent(
    /** A string resource, unless [subtitleText] is set. */
    val subtitleRes: Int,
    /** A literal subtitle: the profile name while On. */
    val subtitleText: String? = null,
    val active: Boolean,
    /** Use the Needs attention icon variant. */
    val attention: Boolean = false,
) {
    companion object {
        /**
         * [active] is the connected profile, [default] the one a tap would connect. Without a
         * default profile an idle tile reads "Set up".
         */
        fun of(state: TunnelState, active: Profile?, default: Profile?): TileContent = when (state) {
            TunnelState.Off ->
                if (default == null) TileContent(R.string.tile_sub_setup, active = false)
                else TileContent(R.string.tile_sub_off, active = false)
            is TunnelState.Connecting -> TileContent(R.string.tile_sub_connecting, active = true)
            is TunnelState.On -> TileContent(0, (active ?: default)?.name ?: "", active = true)
            is TunnelState.Reconnecting -> TileContent(R.string.tile_sub_reconnecting, active = true)
            is TunnelState.NeedsAttention -> TileContent(R.string.tile_sub_attention, active = false, attention = true)
            TunnelState.Disconnecting -> TileContent(R.string.tunnel_state_disconnecting, active = true)
        }
    }
}

/** What a tile tap does (ARCHITECTURE §7, TunnelTileService.onClick). */
enum class TileAction { CONNECT, DISCONNECT, ASK_CONSENT, OPEN_APP }

/** Decides the tap's effect; the "require unlock" check happens before this. */
fun tileAction(state: TunnelState, default: Profile?, hasVpnConsent: Boolean): TileAction = when {
    state is TunnelState.Connecting || state is TunnelState.On || state is TunnelState.Reconnecting -> TileAction.DISCONNECT
    state == TunnelState.Disconnecting -> TileAction.OPEN_APP
    // "Tap to fix": the fix lives in the app.
    state is TunnelState.NeedsAttention -> TileAction.OPEN_APP
    default == null -> TileAction.OPEN_APP
    !hasVpnConsent -> TileAction.ASK_CONSENT
    else -> TileAction.CONNECT
}
