// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.data

import kotlinx.coroutines.flow.StateFlow

/** Profiles and the key material they need. M2: a debug-only hardcoded profile; M6 adds storage. */
interface ProfileRepository {
    val profiles: StateFlow<List<Profile>>

    /** The profile the tile and Always-on VPN use. */
    fun defaultProfile(): Profile?

    fun profile(id: String): Profile? = profiles.value.firstOrNull { it.id == id }

    /**
     * Decrypted private key bytes for an imported-key profile, or null. The caller passes them
     * to Go once and must not keep them; Go zeroes the array.
     */
    fun importedKey(profile: Profile): ByteArray?
}
