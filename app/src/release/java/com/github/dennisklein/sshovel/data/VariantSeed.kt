// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.data

import android.content.Context
import com.github.dennisklein.sshovel.keys.KeyRepository

/** Release builds start empty; debug builds seed a test-env profile. */
object VariantSeed {
    @Suppress("UNUSED_PARAMETER")
    suspend fun seed(context: Context, profiles: ProfileRepository, keys: KeyRepository) = Unit
}
