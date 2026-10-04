// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import com.github.dennisklein.sshovel.keys.KeyEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Everything sshovel persists, in one typed DataStore file so that profile and key changes are
 * atomic together (e.g. "key in use" checks). No private key material, ever (CLAUDE.md, Secrets).
 */
@kotlinx.serialization.Serializable
data class StoredData(
    val profiles: List<Profile> = emptyList(),
    val defaultProfileId: String? = null,
    val keys: List<KeyEntry> = emptyList(),
    val settings: AppSettings = AppSettings(),
)

/** App-wide settings (DESIGN_BRIEF §5.10). */
@kotlinx.serialization.Serializable
data class AppSettings(
    /** "Require unlock to use the tile": on the lock screen the tile asks to unlock first. */
    val requireUnlock: Boolean = false,
    /** [THEME_SYSTEM], [THEME_LIGHT], or [THEME_DARK]. */
    val theme: String = THEME_SYSTEM,
    /** "Use wallpaper colors"; off uses the brand scheme (handoff §1.1). */
    val wallpaperColors: Boolean = true,
    /** Onboarding finished or skipped once; it no longer opens by itself. */
    val onboardingDone: Boolean = false,
) {
    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }
}

internal object StoredDataSerializer : Serializer<StoredData> {
    override val defaultValue = StoredData()

    override suspend fun readFrom(input: InputStream): StoredData = try {
        ProfileJson.decodeFromString(StoredData.serializer(), input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("unreadable sshovel store", e)
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("unreadable sshovel store", e)
    }

    override suspend fun writeTo(t: StoredData, output: OutputStream) {
        output.write(ProfileJson.encodeToString(StoredData.serializer(), t).encodeToByteArray())
    }
}

/**
 * The DataStore plus a hot [state] for synchronous readers (UI). [produceFile] runs on [scope]'s
 * first read, not here: resolving the app's files directory touches the disk (StrictMode, M8).
 */
class AppStore(produceFile: () -> File, scope: CoroutineScope) {
    constructor(file: File, scope: CoroutineScope) : this({ file }, scope)

    private val store: DataStore<StoredData> = DataStoreFactory.create(StoredDataSerializer, scope = scope, produceFile = produceFile)

    /** Null until the file has been read once. */
    val state: StateFlow<StoredData?> = store.data.stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun current(): StoredData = store.data.first()

    suspend fun update(transform: (StoredData) -> StoredData): StoredData = store.updateData { transform(it) }
}
