// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.github.dennisklein.sshovel.ui.theme.MonoFamily

/** A string resource whose arguments (hosts, CIDRs, commands) are set in monospace (DESIGN_BRIEF §8). */
@Composable
fun monoArg(id: Int, vararg args: String): AnnotatedString {
    val marker = "\u0000"
    val template = stringResource(id, *Array(args.size) { "$marker$it$marker" })
    return buildAnnotatedString {
        template.split(marker).forEachIndexed { i, part ->
            val arg = if (i % 2 == 1) part.toIntOrNull()?.let { args.getOrNull(it) } else null
            if (arg != null) withStyle(SpanStyle(fontFamily = MonoFamily)) { append(arg) } else append(part)
        }
    }
}

/** [text] with every occurrence of [words] set in monospace, for copy that names machine values. */
fun monoWords(text: String, vararg words: String): AnnotatedString = buildAnnotatedString {
    append(text)
    for (w in words.filter { it.isNotEmpty() }) {
        var i = text.indexOf(w)
        while (i >= 0) {
            addStyle(SpanStyle(fontFamily = MonoFamily), i, i + w.length)
            i = text.indexOf(w, i + w.length)
        }
    }
}
