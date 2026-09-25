// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Release builds have no profiles until profile storage lands (M6). */
object VariantProfiles {
    fun create(@Suppress("UNUSED_PARAMETER") context: Context): ProfileRepository = NoProfiles
}

private object NoProfiles : ProfileRepository {
    override val profiles: StateFlow<List<Profile>> = MutableStateFlow(emptyList())
    override fun defaultProfile(): Profile? = null
    override fun importedKey(profile: Profile): ByteArray? = null
}
