// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.dennisklein.sshovel.data.Auth
import com.github.dennisklein.sshovel.data.HostKey
import com.github.dennisklein.sshovel.data.HostKeyInfo
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.data.Server
import com.github.dennisklein.sshovel.ui.screens.hostkey.MismatchScreen
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The host key mismatch screen (handoff S4) can't be dismissed and offers no way to accept the
 * new key (CLAUDE.md, Host keys): only "Disconnect" and "Review in profile", and back means
 * Disconnect.
 */
@RunWith(AndroidJUnit4::class)
class MismatchScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var disconnects = 0
    private var reviews = 0

    private val profile = Profile(
        id = "office", name = "Office", server = Server("jump.corp.example", 22, "alex"),
        auth = Auth(Auth.KEYSTORE, "k"), routes = listOf("10.20.0.0/16"),
        hostKey = HostKey("ecdsa-sha2-nistp256", "SHA256:nThbRk2mXJvF3e8pQz1LYw7cDh0KsVa4Tq9NoM6uGf5BwE+i2rY", "2026-03-12T10:00:00Z"),
    )

    @Before fun setUp() {
        rule.setContent {
            SshovelTheme(dynamicColor = false) {
                MismatchScreen(profile, HostKeyInfo("ssh-ed25519", "SHA256:p7VqZc0MhR3kWy8nUe2BtL6jXa9FdK1sGm4HrO5wNi7CbQ3zE0v"), { disconnects++ }, { reviews++ })
            }
        }
    }

    @Test fun onlyDisconnectAndReview() {
        rule.onNodeWithText("Server identity changed").assertExists()
        val clickable = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertEquals(2, clickable.size)
        rule.onNodeWithText("Disconnect").assertExists()
        rule.onNodeWithText("Review in profile").assertExists()
        for (word in listOf("Trust", "Accept", "Continue", "Connect anyway")) {
            assertEquals(word, 0, rule.onAllNodesWithText(word, substring = true, ignoreCase = true).fetchSemanticsNodes().size)
        }
    }

    @Test fun tappingAnywhereElseDoesNothing() {
        rule.onNodeWithTag("mismatch").performTouchInput { click(topLeft) }
        rule.onNodeWithTag("mismatch").performTouchInput { click(center) }
        rule.onNodeWithTag("mismatch").assertExists()
        assertEquals(0, disconnects)
        assertEquals(0, reviews)
    }

    @Test fun backIsDisconnect() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(1, disconnects)
        assertEquals(0, reviews)
    }

    @Test fun buttons() {
        rule.onNodeWithText("Review in profile").performClick()
        rule.onNodeWithText("Disconnect").performClick()
        assertEquals(1, reviews)
        assertEquals(1, disconnects)
    }
}
