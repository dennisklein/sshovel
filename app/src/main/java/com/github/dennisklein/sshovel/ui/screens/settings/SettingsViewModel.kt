// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.dennisklein.sshovel.AppContainer
import com.github.dennisklein.sshovel.data.AppSettings
import com.github.dennisklein.sshovel.data.Profile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUi(
    val settings: AppSettings = AppSettings(),
    val profiles: List<Profile> = emptyList(),
    val defaultProfile: Profile? = null,
)

/** Settings (DESIGN_BRIEF §5.10, handoff G5). */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val ui: StateFlow<SettingsUi> = container.store.state.map { s ->
        val profiles = s?.profiles.orEmpty()
        SettingsUi(
            settings = s?.settings ?: AppSettings(),
            profiles = profiles,
            defaultProfile = profiles.firstOrNull { it.id == s?.defaultProfileId } ?: profiles.firstOrNull(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUi())

    fun setDefault(id: String) = viewModelScope.launch { container.profiles.setDefault(id) }

    fun setRequireUnlock(on: Boolean) = viewModelScope.launch { container.settings.setRequireUnlock(on) }

    fun setTheme(theme: String) = viewModelScope.launch { container.settings.setTheme(theme) }

    fun setWallpaperColors(on: Boolean) = viewModelScope.launch { container.settings.setWallpaperColors(on) }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory { initializer { SettingsViewModel(container) } }
    }
}
