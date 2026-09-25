// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.dennisklein.sshovel.AppContainer
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.tunnel.TunnelState
import com.github.dennisklein.sshovel.tunnel.TunnelStats
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val state: TunnelState = TunnelState.Off,
    /** The active profile while connected, else the default one. */
    val profile: Profile? = null,
    val stats: TunnelStats = TunnelStats(),
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val controller = container.tunnelController

    val ui: StateFlow<HomeUiState> = combine(
        controller.state, controller.activeProfile, controller.stats, container.profiles.profiles,
    ) { state, active, stats, _ ->
        HomeUiState(state, active ?: container.profiles.defaultProfile(), stats)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Call after VPN consent was granted. */
    fun connect() {
        container.profiles.defaultProfile()?.let { controller.connect(it.id) }
    }

    fun disconnect() = controller.disconnect()

    fun retryNow() = controller.retryNow()

    companion object {
        fun factory(container: AppContainer) = viewModelFactory { initializer { HomeViewModel(container) } }
    }
}
