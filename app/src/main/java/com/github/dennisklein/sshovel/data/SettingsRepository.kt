// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** App settings, persisted in [AppStore] next to profiles and keys. */
class SettingsRepository(private val store: AppStore, scope: CoroutineScope) {
    val settings: StateFlow<AppSettings> = store.state.map { it?.settings ?: AppSettings() }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    /** Null until the store has been read, so the UI can wait instead of flashing defaults. */
    val loaded: StateFlow<AppSettings?> = store.state.map { it?.settings }
        .stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun current(): AppSettings = store.current().settings

    suspend fun setRequireUnlock(on: Boolean) = update { it.copy(requireUnlock = on) }

    suspend fun setTheme(theme: String) = update { it.copy(theme = theme) }

    suspend fun setWallpaperColors(on: Boolean) = update { it.copy(wallpaperColors = on) }

    suspend fun setOnboardingDone() = update { it.copy(onboardingDone = true) }

    private suspend fun update(change: (AppSettings) -> AppSettings) {
        store.update { it.copy(settings = change(it.settings)) }
    }
}
