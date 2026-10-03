// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.nav

import kotlinx.serialization.Serializable

/** Type-safe navigation routes (navigation-compose). Screen ids refer to the design handoff. */
object Routes {
    /** H0–H7. */
    @Serializable data object Home

    /** O1–O9; [step] 1..7 to reopen a single step from Settings ("Run setup again"). */
    @Serializable data class Onboarding(val step: Int = 1, val single: Boolean = false)

    /** P1–P8. [id] null: new profile. [focus]: [FOCUS_IDENTITY] or [FOCUS_SUBNET]. */
    @Serializable data class Profile(val id: String? = null, val focus: String? = null)

    /** A1, editing the profile editor's draft. */
    @Serializable data object AppPicker

    /** K1. [import]: open the import sheet right away. */
    @Serializable data class Keys(val import: Boolean = false, val create: Boolean = false)

    /** K2–K3. */
    @Serializable data class KeyDetail(val id: String)

    /** S4, full screen. */
    @Serializable data object Mismatch

    /** G1–G4; [tab] 0 Events, 1 DNS, 2 Connections. */
    @Serializable data class Diagnostics(val tab: Int = 0)

    /** G5. */
    @Serializable data object Settings

    @Serializable data object About

    /** The bundled GPL text. */
    @Serializable data object License

    @Serializable data object OpenSourceLicenses

    const val FOCUS_IDENTITY = "identity"
    const val FOCUS_SUBNET = "subnet"
}
