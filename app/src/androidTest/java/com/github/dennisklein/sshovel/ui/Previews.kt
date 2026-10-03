// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import java.lang.reflect.Method

/**
 * The app's @Preview functions (the handoff's screens and states, light), for tests that run over
 * all of them. Each compiles to `static PreviewX(Composer, int)` in its file's class.
 */
object Previews {
    val components = listOf("FingerprintKt", "InputsKt", "RowsKt", "StateIndicatorKt", "StatusHeroKt").map { "ui.components.$it" }

    val screens = listOf(
        "consent.VpnConsentScreensKt", "diagnostics.DiagnosticsScreenKt", "home.HomeScreenKt", "hostkey.HostKeyViewsKt",
        "hostkey.MismatchScreenKt", "keys.KeyDetailScreenKt", "keys.KeySheetsKt", "keys.KeysScreenKt",
        "onboarding.OnboardingScreenKt", "profile.AppPickerScreenKt", "profile.DiscoverSheetKt", "profile.ProfileEditorScreenKt",
        "settings.AboutScreenKt", "settings.LicenseScreenKt", "settings.OpenSourceLicensesScreenKt", "settings.SettingsScreenKt",
    ).map { "ui.screens.$it" }

    /** JUnit parameters: display name and method. */
    fun of(files: List<String>): List<Array<Any>> = files.flatMap { f ->
        Class.forName("com.github.dennisklein.sshovel.$f").declaredMethods
            .filter { it.name.startsWith("Preview") && it.parameterTypes.contentEquals(arrayOf(Composer::class.java, Int::class.javaPrimitiveType)) }
            .sortedBy { it.name }
            .map { m -> m.isAccessible = true; arrayOf<Any>("${f.substringAfterLast('.').removeSuffix("Kt")}.${m.name}", m) }
    }

    /** Renders [preview], optionally at another font scale. */
    @Composable
    fun Show(preview: Method, fontScale: Float? = null) {
        if (fontScale == null) {
            preview.invoke(null, currentComposer, 0)
        } else {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) { preview.invoke(null, currentComposer, 0) }
        }
    }
}
