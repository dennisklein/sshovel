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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.data.AppSettings

/**
 * Machine values (hosts, IPs, CIDRs, fingerprints) are always monospace (DESIGN_BRIEF §8): Roboto
 * Mono, bundled (handoff §1.3). Derive styles with `copy(fontFamily = MonoFamily)`.
 */
val MonoFamily: FontFamily = FontFamily(
    Font(R.font.roboto_mono_regular, FontWeight.Normal),
    Font(R.font.roboto_mono_medium, FontWeight.Medium),
)

/**
 * sshovel's theme: wallpaper (dynamic) colors by default, the handoff's brand scheme otherwise.
 * State accents come from [LocalStateColors], never hardcoded in screens. The type scale is
 * Material 3's default, with no overrides (handoff §1.3).
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

/** [SshovelTheme] as the user set it up in Settings → Appearance. */
@Composable
fun SshovelTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        AppSettings.THEME_LIGHT -> false
        AppSettings.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }
    SshovelTheme(darkTheme = dark, dynamicColor = settings.wallpaperColors, content = content)
}
