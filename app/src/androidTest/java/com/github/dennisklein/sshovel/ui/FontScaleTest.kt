// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.lang.reflect.Method

/**
 * M7: "200 % font scale shows no clipping on any screen". Renders every @Preview (the screens and
 * states of the handoff, light) at font scale 2 and fails on text that is cut off: laid out past
 * its bounds without an ellipsis, or sticking out of the window sideways. Intentional ellipses
 * (e.g. the collapsed public key) are allowed.
 */
@RunWith(Parameterized::class)
class FontScaleTest(private val name: String, private val preview: Method) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun noClippingAtTwiceTheFontSize() {
        rule.setContent { Previews.Show(preview, fontScale = 2f) }
        rule.waitForIdle()
        val texts = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
        val problems = texts.flatMap(::problems)
        assertTrue("$name at 200 %:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    private fun problems(n: SemanticsNode): List<String> {
        val results = mutableListOf<TextLayoutResult>()
        n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val text = results.joinToString(" ") { it.layoutInput.text.text }.take(60)
        val out = mutableListOf<String>()
        for (r in results) {
            if (r.hasVisualOverflow && r.layoutInput.overflow != TextOverflow.Ellipsis) out += "cut off: \"$text\""
        }
        // Sideways out of its window, unless it sits in something that scrolls sideways.
        val root = generateSequence(n) { it.parent }.last()
        val inHorizontalScroll = generateSequence(n.parent) { it.parent }
            .any { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null }
        if (!inHorizontalScroll && n.boundsInRoot.right > root.boundsInRoot.right + 1f) out += "past the right edge: \"$text\""
        return out
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun previews(): List<Array<Any>> = Previews.of(Previews.components + Previews.screens)
    }
}
