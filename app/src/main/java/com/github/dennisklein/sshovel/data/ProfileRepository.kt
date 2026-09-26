// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.util.UUID

/** The server's host key as FetchHostKey reports it (ARCHITECTURE §6). */
@kotlinx.serialization.Serializable
data class HostKeyInfo(val type: String, val fingerprint: String, val line: String = "")

/** A pin already exists; it must be forgotten in the profile editor first (CLAUDE.md, Host keys). */
class HostKeyAlreadyPinnedException : IllegalStateException("host key already pinned; forget it first")

/**
 * Connection profiles, persisted in [AppStore]. Host key pins change only through
 * [trustHostKey] (no pin yet) and [forgetHostKey] (explicit user action): [save] never
 * touches them, so no code path can swap one pinned key for another.
 */
class ProfileRepository(
    private val store: AppStore,
    scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
) {
    val profiles: StateFlow<List<Profile>> = store.state.map { it?.profiles.orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val defaultProfileId: StateFlow<String?> = store.state.map { it?.let(::defaultOf)?.id }
        .stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun profile(id: String): Profile? = store.current().profiles.firstOrNull { it.id == id }

    /** The profile the tile and Always-on VPN use. */
    suspend fun defaultProfile(): Profile? = defaultOf(store.current())

    /** [defaultProfile] from the last loaded state, for UI code that can't suspend. */
    fun defaultProfileNow(): Profile? = store.state.value?.let(::defaultOf)

    /**
     * Adds or replaces a profile. The stored host key pin is kept as is (a new profile starts
     * unpinned). The first profile becomes the default.
     */
    suspend fun save(profile: Profile): Profile {
        var saved = profile
        store.update { d ->
            val existing = d.profiles.firstOrNull { it.id == profile.id }
            saved = profile.copy(id = profile.id.ifEmpty { UUID.randomUUID().toString() }, hostKey = existing?.hostKey)
            val profiles = if (existing != null) d.profiles.map { if (it.id == saved.id) saved else it } else d.profiles + saved
            d.copy(profiles = profiles, defaultProfileId = d.defaultProfileId ?: saved.id)
        }
        return saved
    }

    /** Deletes a profile and its pin; the next profile becomes the default if needed. */
    suspend fun delete(id: String) {
        store.update { d ->
            val profiles = d.profiles.filterNot { it.id == id }
            d.copy(profiles = profiles, defaultProfileId = d.defaultProfileId.takeIf { it != id } ?: profiles.firstOrNull()?.id)
        }
    }

    suspend fun setDefault(id: String) {
        store.update { d -> if (d.profiles.any { it.id == id }) d.copy(defaultProfileId = id) else d }
    }

    /**
     * Pins [key] after the user tapped "Trust this server" (DESIGN_BRIEF §5.5, first use). Throws
     * [HostKeyAlreadyPinnedException] if the profile has a pin: a changed key is never accepted
     * here, only after [forgetHostKey].
     */
    suspend fun trustHostKey(profileId: String, key: HostKeyInfo): Profile {
        var pinned: Profile? = null
        store.update { d ->
            val p = d.profiles.firstOrNull { it.id == profileId } ?: throw NoSuchElementException("no profile $profileId")
            if (p.hostKey != null) throw HostKeyAlreadyPinnedException()
            val updated = p.copy(hostKey = HostKey(key.type, key.fingerprint, now().toString()))
            pinned = updated
            d.copy(profiles = d.profiles.map { if (it.id == profileId) updated else it })
        }
        return pinned!!
    }

    /** "Forget pinned key" in the profile editor, after the user confirmed (handoff P7). */
    suspend fun forgetHostKey(profileId: String) {
        store.update { d -> d.copy(profiles = d.profiles.map { if (it.id == profileId) it.copy(hostKey = null) else it }) }
    }

    private fun defaultOf(d: StoredData): Profile? =
        d.profiles.firstOrNull { it.id == d.defaultProfileId } ?: d.profiles.firstOrNull()
}
