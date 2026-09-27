// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.tile

import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.data.Auth
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.data.Server
import com.github.dennisklein.sshovel.tunnel.Codes
import com.github.dennisklein.sshovel.tunnel.TunnelState
import org.junit.Assert.assertEquals
import org.junit.Test

/** The tile per DESIGN_BRIEF §7, and what a tap does (ARCHITECTURE §7). */
class TileContentTest {
    private val office = Profile(id = "o", name = "Office", server = Server("h", 22, "u"), auth = Auth(Auth.IMPORTED, "k"), routes = listOf("10.0.0.0/8"))
    private val lab = office.copy(id = "l", name = "Lab")

    @Test fun contentPerState() {
        assertEquals(TileContent(R.string.tile_sub_setup, active = false), TileContent.of(TunnelState.Off, null, null))
        assertEquals(TileContent(R.string.tile_sub_off, active = false), TileContent.of(TunnelState.Off, null, office))
        assertEquals(TileContent(R.string.tile_sub_connecting, active = true), TileContent.of(TunnelState.Connecting("auth"), office, office))
        // On: the subtitle is the connected profile's name, not the default's.
        assertEquals(TileContent(0, "Lab", active = true), TileContent.of(TunnelState.On(), lab, office))
        assertEquals(
            TileContent(R.string.tile_sub_reconnecting, active = true),
            TileContent.of(TunnelState.Reconnecting("networkChanged", 1, null, null), office, office),
        )
        assertEquals(
            TileContent(R.string.tile_sub_attention, active = false, attention = true),
            TileContent.of(TunnelState.NeedsAttention(Codes.AUTH_FAILED), office, office),
        )
    }

    @Test fun tapActions() {
        assertEquals(TileAction.CONNECT, tileAction(TunnelState.Off, office, hasVpnConsent = true))
        assertEquals(TileAction.ASK_CONSENT, tileAction(TunnelState.Off, office, hasVpnConsent = false))
        assertEquals(TileAction.OPEN_APP, tileAction(TunnelState.Off, null, hasVpnConsent = true))
        for (s in listOf(TunnelState.Connecting("identity"), TunnelState.On(), TunnelState.Reconnecting("dialFailed", 2, 1L, null))) {
            assertEquals(TileAction.DISCONNECT, tileAction(s, office, hasVpnConsent = true))
        }
        // "Tap to fix" opens the app, even without consent (e.g. VPN_REVOKED).
        assertEquals(TileAction.OPEN_APP, tileAction(TunnelState.NeedsAttention(Codes.VPN_REVOKED), office, hasVpnConsent = false))
    }
}
