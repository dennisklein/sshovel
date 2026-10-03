// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import java.lang.reflect.Method
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * M7: "200 % font scale shows no clipping on any screen". Renders every @Preview (the screens and
 * states of the handoff, light) at font scale 2 and fails on text that is cut off: given less
 * height than it needs, a line cut without an ellipsis, or sticking out of the window sideways.
 * Intentional ellipses (e.g. the collapsed public key) are allowed.
 */
@RunWith(Parameterized::class)
class FontScaleTest(private val name: String, private val preview: Method) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var measurer: TextMeasurer

    @Test fun noClippingAtTwiceTheFontSize() {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 2f)) {
                measurer = rememberTextMeasurer()
                preview.invoke(null, currentComposer, 0)
            }
        }
        rule.waitForIdle()
        val texts = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
        val problems = texts.flatMap(::problems)
        assertTrue("$name at 200 %:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    /**
     * Measures each text again, on its own, at the node's width and the same 2× density, and
     * compares that with the size the node got: shorter than its text needs means a parent cut it
     * off; a line cut without an ellipsis is clipping too. (The layout result a text node reports
     * through GetTextLayoutResult isn't used for the verdict: under a density override it reports
     * overflow for every text.)
     */
    private fun problems(n: SemanticsNode): List<String> {
        val results = mutableListOf<TextLayoutResult>()
        n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val input = results.firstOrNull()?.layoutInput ?: return emptyList()
        val text = input.text.text.take(60)
        val out = mutableListOf<String>()
        val editable = n.config.getOrNull(SemanticsActions.SetText) != null // fields scroll sideways by design
        val needed = measurer.measure(
            input.text,
            style = input.style,
            overflow = input.overflow,
            softWrap = input.softWrap,
            maxLines = input.maxLines,
            constraints = Constraints(maxWidth = n.size.width.coerceAtLeast(1)),
        )
        if (needed.size.height > n.size.height + 2) out += "squeezed: \"$text\" needs ${needed.size.height} px, has ${n.size.height}"
        if (!editable && needed.hasVisualOverflow && input.overflow != TextOverflow.Ellipsis) out += "cut off: \"$text\""
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
