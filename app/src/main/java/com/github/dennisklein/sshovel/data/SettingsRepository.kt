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

    suspend fun current(): AppSettings = store.current().settings

    suspend fun setRequireUnlock(on: Boolean) {
        store.update { it.copy(settings = it.settings.copy(requireUnlock = on)) }
    }
}
