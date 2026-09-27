// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.dennisklein.sshovel.SshovelApplication
import com.github.dennisklein.sshovel.ui.screens.profile.EditorNav
import com.github.dennisklein.sshovel.ui.screens.profile.Fields
import com.github.dennisklein.sshovel.ui.screens.profile.ProfileEditorRoute
import com.github.dennisklein.sshovel.ui.screens.profile.ProfileEditorViewModel
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The profile editor's validation (IMPLEMENTATION_PLAN M6): the real ViewModel and the Go core's
 * ValidateConfig, on a new profile that is never saved (it stays invalid).
 */
@RunWith(AndroidJUnit4::class)
class ProfileEditorValidationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var vm: ProfileEditorViewModel
    private var saved = false

    @Before fun setUp() {
        val container = (rule.activity.application as SshovelApplication).container
        vm = ProfileEditorViewModel(container, profileId = null)
        rule.setContent {
            SshovelTheme(dynamicColor = false) {
                ProfileEditorRoute(vm, EditorNav(onSaved = { saved = true }), focus = null)
            }
        }
        rule.waitUntil(5_000) { vm.ui.value.loaded }
    }

    private fun field(key: String) = rule.onNodeWithTag("field-$key")

    @Test fun saveWithMissingFieldsExplainsAndStays() {
        rule.onNodeWithTag("save").performClick()
        // "Fix n problems to save" with "Show first" (handoff P3), and inline "Required".
        rule.onNodeWithText("problems to save", substring = true).assertExists()
        rule.onNodeWithText("Show first").assertExists()
        assert(rule.onAllNodesWithText("Required").fetchSemanticsNodes().isNotEmpty())
        assertFalse(saved)
    }

    @Test fun invalidSubnetIsRefusedWithAnExample() {
        val input = rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("subnet-input")))
        input.performScrollTo()
        input.performTextInput("10.40.0.0/33")
        input.performImeAction()
        rule.onNodeWithText("Not a valid CIDR. Prefix length must be 0–32, e.g. 10.40.0.0/16").assertExists()
        assert(vm.ui.value.draft.routes.isEmpty())
    }

    @Test fun suffixesWithoutDnsServerAndTunnelOverlapAreErrors() {
        vm.edit(Fields.SUFFIXES) { it.copy(name = "Lab", host = "lab.example.net", user = "alex", routes = listOf("10.20.0.0/16"), suffixes = listOf("corp.example")) }
        vm.edit(Fields.TUN) { it.copy(tunCidr = "10.20.99.0/24") }
        rule.onNodeWithTag("save").performClick()
        rule.waitForIdle()
        field(Fields.DNS).performScrollTo()
        rule.onNodeWithText("Required when domain suffixes are set").assertExists()
        // Go reports TUN_OVERLAPS_ROUTE on the route; the editor shows it on the tunnel subnet (P4).
        if (!vm.ui.value.advancedExpanded) vm.toggleAdvanced()
        rule.waitForIdle()
        field(Fields.TUN).performScrollTo()
        rule.onNodeWithText("Overlaps routed subnet 10.20.0.0/16. Default is 198.18.0.0/24").assertExists()
        assertFalse(saved)
    }

    @Test fun coveredSubnetWarnsButDoesNotBlock() {
        vm.edit(Fields.ROUTES) { it.copy(routes = listOf("10.20.0.0/16", "10.20.4.0/24")) }
        rule.waitForIdle()
        rule.onNodeWithText("Already covered by 10.20.0.0/16").performScrollTo().assertExists()
        assert(vm.ui.value.errors.keys.none { it.startsWith("routes[") })
    }
}
