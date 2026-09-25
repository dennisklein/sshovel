// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

/** Machine values (hosts, IPs, CIDRs, fingerprints) are always monospace (DESIGN_BRIEF §8). */
val MonoFamily: FontFamily = FontFamily.Monospace

/**
 * sshovel's theme: wallpaper (dynamic) colors by default, the handoff's brand scheme otherwise.
 * State accents come from [LocalStateColors], never hardcoded in screens.
 */
@Composable
fun SshovelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> SshovelDarkScheme
        else -> SshovelLightScheme
    }
    CompositionLocalProvider(LocalStateColors provides if (darkTheme) DarkStateColors else LightStateColors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
