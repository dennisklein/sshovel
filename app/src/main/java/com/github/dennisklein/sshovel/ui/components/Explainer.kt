// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The rounded icon tile of full-screen explainers (handoff V1, V2, S4, O1, O9). */
@Composable
fun IconTile(icon: Int, container: Color, content: Color, size: Dp = 72.dp, iconSize: Dp = 36.dp, corner: Dp = 20.dp) {
    Surface(Modifier.size(size), shape = RoundedCornerShape(corner), color = container, contentColor = content) {
        Box(contentAlignment = Alignment.Center) { SymbolIcon(icon, size = iconSize) }
    }
}

/** A grouped list on surfaceContainerLow with 16 dp corners (handoff O1, O6, O9, V1). */
@Composable
fun GroupedList(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(vertical = 8.dp), content = content)
    }
}

/** One row of a [GroupedList]: icon, optional title, body. */
@Composable
fun Point(icon: Int, title: String?, body: AnnotatedString?, titleStyleLarge: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = if (body == null) Alignment.CenterVertically else Alignment.Top,
    ) {
        SymbolIcon(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (title != null) {
                Text(title, style = if (titleStyleLarge) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleSmall)
            }
            if (body != null) {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** An informational note on surfaceContainerHigh (handoff V2, O8, K4). */
@Composable
fun InfoNote(icon: Int, text: CharSequence, modifier: Modifier = Modifier, corner: Dp = 16.dp) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(corner), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SymbolIcon(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            val style = MaterialTheme.typography.bodyMedium
            val color = MaterialTheme.colorScheme.onSurfaceVariant
            if (text is AnnotatedString) Text(text, Modifier.weight(1f), style = style, color = color)
            else Text(text.toString(), Modifier.weight(1f), style = style, color = color)
        }
    }
}
