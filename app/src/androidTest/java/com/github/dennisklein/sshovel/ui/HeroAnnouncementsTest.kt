// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.github.dennisklein.sshovel.data.Auth
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.data.Server
import com.github.dennisklein.sshovel.tunnel.Codes
import com.github.dennisklein.sshovel.tunnel.TunnelState
import com.github.dennisklein.sshovel.ui.components.HeroActions
import com.github.dennisklein.sshovel.ui.components.HeroModel
import com.github.dennisklein.sshovel.ui.components.StatusHero
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * M7: "TalkBack walkthrough of the connect flow announces every state change". The hero's live
 * region says what handoff §5 lists, once per change, politely, and assertively only for Needs
 * attention. (TalkBack itself is checked by hand on a device.)
 */
class HeroAnnouncementsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val office = Profile("office", "Office", Server("jump.corp.example", 22, "alex"), Auth(Auth.KEYSTORE, "k1"), routes = listOf("10.20.0.0/16"))

    /** The one live region in the hero: (announcement, mode). */
    private fun liveRegion(): Pair<String, LiveRegionMode> {
        val n = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion), useUnmergedTree = true).fetchSemanticsNode()
        return n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString() to n.config[SemanticsProperties.LiveRegion]
    }

    @Test fun connectFlowAnnouncesEveryChange() {
        var state by mutableStateOf<TunnelState>(TunnelState.Off)
        rule.setContent { SshovelTheme(dynamicColor = false) { StatusHero(HeroModel(state, office), HeroActions()) } }

        fun expect(s: TunnelState, text: String, mode: LiveRegionMode = LiveRegionMode.Polite) {
            state = s
            rule.waitForIdle()
            assertEquals(text to mode, liveRegion())
        }

        expect(TunnelState.Off, "Disconnected")
        expect(TunnelState.Connecting("resolving"), "Connecting to Office")
        expect(TunnelState.On(), "Connected to Office")
        val retryIn = System.currentTimeMillis() + 5_000
        state = TunnelState.Reconnecting("networkChanged", 1, retryIn, null)
        rule.waitForIdle()
        val (reconnecting, mode) = liveRegion()
        assertEquals(LiveRegionMode.Polite, mode)
        assert(reconnecting.startsWith("Connection lost. Reconnecting to Office. Next retry in ")) { reconnecting }
        // The countdown ticks, the announcement doesn't (handoff §5).
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        assertEquals(reconnecting, liveRegion().first)
        expect(TunnelState.On(), "Reconnected to Office")
        expect(
            TunnelState.NeedsAttention(Codes.AUTH_FAILED),
            "Needs attention. Key not accepted. Show public key button available.",
            LiveRegionMode.Assertive,
        )
        expect(TunnelState.Off, "Disconnected")
    }
}
