// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui

import android.app.Activity
import android.content.Intent

/** Release builds ignore debug commands. */
object DebugCommands {
    @Suppress("UNUSED_PARAMETER")
    fun handle(activity: Activity, intent: Intent?, connect: () -> Unit) = Unit
}
