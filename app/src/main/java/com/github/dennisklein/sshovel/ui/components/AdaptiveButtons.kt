// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A secondary and a primary button side by side (secondary at the start, primary at the end),
 * or, when they don't fit, e.g. at 200 % font, stacked full width with the primary on top
 * (handoff §5). [equalWidth] splits the row in two halves instead of using natural widths.
 */
@Composable
fun AdaptiveButtonRow(
    secondary: @Composable () -> Unit,
    primary: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    equalWidth: Boolean = false,
    gap: Dp = 8.dp,
) {
    Layout(contents = listOf(secondary, primary), modifier = modifier) { (sec, pri), c ->
        val gapPx = gap.roundToPx()
        val maxW = c.maxWidth
        val secW = sec.maxOfOrNull { it.maxIntrinsicWidth(c.maxHeight) } ?: 0
        val priW = pri.maxOfOrNull { it.maxIntrinsicWidth(c.maxHeight) } ?: 0
        val hasSecondary = sec.isNotEmpty()
        val needed = if (equalWidth) 2 * maxOf(secW, priW) + gapPx else secW + gapPx + priW
        val fits = !hasSecondary || needed <= maxW
        if (fits) {
            val half = (maxW - gapPx) / 2
            val each = if (equalWidth && hasSecondary) Constraints.fixedWidth(half) else c.copy(minWidth = 0)
            val s = sec.map { it.measure(each) }
            val p = pri.map { it.measure(each) }
            val h = (s + p).maxOfOrNull { it.height } ?: 0
            layout(maxW, h) {
                s.forEach { it.placeRelative(0, (h - it.height) / 2) }
                p.forEach { it.placeRelative(maxW - it.width, (h - it.height) / 2) }
            }
        } else {
            val full = Constraints.fixedWidth(maxW)
            val p = pri.map { it.measure(full) }
            val s = sec.map { it.measure(full) }
            val ph = p.maxOfOrNull { it.height } ?: 0
            val sh = s.maxOfOrNull { it.height } ?: 0
            layout(maxW, ph + gapPx + sh) {
                p.forEach { it.placeRelative(0, 0) }
                s.forEach { it.placeRelative(0, ph + gapPx) }
            }
        }
    }
}
