// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.lang.reflect.Method

/**
 * M7: "Accessibility Scanner reports no issues on main screens". Runs the Accessibility Test
 * Framework, the checks behind Accessibility Scanner (touch target size, contrast, labels,
 * duplicate descriptions, …), over every screen preview, dialogs and sheets included.
 */
@RunWith(Parameterized::class)
class AccessibilityChecksTest(private val name: String, private val preview: Method) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun passesAccessibilityChecks() {
        rule.enableAccessibilityChecks()
        rule.setContent { Previews.Show(preview) }
        rule.waitForIdle()
        val roots = rule.onAllNodes(isRoot())
        repeat(roots.fetchSemanticsNodes().size) { roots[it].tryPerformAccessibilityChecks() }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun previews(): List<Array<Any>> = Previews.of(Previews.screens)
    }
}
