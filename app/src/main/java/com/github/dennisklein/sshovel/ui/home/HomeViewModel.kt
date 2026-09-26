// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.dennisklein.sshovel.AppContainer
import com.github.dennisklein.sshovel.data.HostKeyAlreadyPinnedException
import com.github.dennisklein.sshovel.data.HostKeyInfo
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.tunnel.Codes
import com.github.dennisklein.sshovel.tunnel.HostKeyFetchException
import com.github.dennisklein.sshovel.tunnel.TunnelState
import com.github.dennisklein.sshovel.tunnel.TunnelStats
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The host key verification dialog (DESIGN_BRIEF §5.5, first use; handoff S3). */
sealed interface VerifyUi {
    val profile: Profile

    data class Loading(override val profile: Profile) : VerifyUi
    data class Ready(override val profile: Profile, val key: HostKeyInfo) : VerifyUi
    data class Failed(override val profile: Profile, val code: String) : VerifyUi
}

data class HomeUiState(
    val state: TunnelState = TunnelState.Off,
    /** The active profile while connected, else the default one. */
    val profile: Profile? = null,
    val stats: TunnelStats = TunnelStats(),
    /** Name of the profile's key, for error copy. */
    val keyName: String? = null,
    val verify: VerifyUi? = null,
)

/** One-off messages for the snackbar. */
enum class HomeMessage { SERVER_TRUSTED }

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val controller = container.tunnelController
    private val profiles = container.profiles
    private val verify = MutableStateFlow<VerifyUi?>(null)
    private val messageChannel = Channel<HomeMessage>(Channel.BUFFERED)
    val messages: Flow<HomeMessage> = messageChannel.receiveAsFlow()

    val ui: StateFlow<HomeUiState> = combine(
        controller.state, controller.activeProfile, controller.stats, profiles.profiles, verify,
    ) { state, active, stats, all, verify ->
        // The stored copy, so a pin made while the card is showing is reflected.
        val profile = active?.let { a -> all.firstOrNull { it.id == a.id } ?: a } ?: profiles.defaultProfileNow()
        val keyName = profile?.let { p -> container.keys.keys.value.firstOrNull { it.id == p.auth.alias }?.name }
        HomeUiState(state, profile, stats, keyName, verify)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Connects [profileId], or the shown profile. Call after VPN consent was granted. */
    fun connect(profileId: String? = null) {
        (profileId ?: ui.value.profile?.id ?: profiles.defaultProfileNow()?.id)?.let(controller::connect)
    }

    fun disconnect() = controller.disconnect()

    fun retryNow() = controller.retryNow()

    /** "Disconnect" on a stopping error such as HOST_KEY_MISMATCH: back to Off. */
    fun dismissError() = controller.acknowledgeError()

    /** "Verify server": fetch the host key the server presents now. */
    fun startVerify() {
        val profile = ui.value.profile ?: return
        verify.value = VerifyUi.Loading(profile)
        viewModelScope.launch {
            verify.value = try {
                VerifyUi.Ready(profile, container.hostKeys.fetch(profile))
            } catch (e: HostKeyFetchException) {
                VerifyUi.Failed(profile, e.code)
            }
        }
    }

    fun cancelVerify() {
        verify.value = null
    }

    /** "Trust this server": pins the shown key, then [onTrusted] reconnects. */
    fun trust(onTrusted: () -> Unit) {
        val ready = verify.value as? VerifyUi.Ready ?: return
        viewModelScope.launch {
            try {
                profiles.trustHostKey(ready.profile.id, ready.key)
            } catch (_: HostKeyAlreadyPinnedException) {
                // Pinned meanwhile (another path): never replace a pin from here.
                verify.value = VerifyUi.Failed(ready.profile, Codes.HOST_KEY_MISMATCH)
                return@launch
            }
            verify.value = null
            messageChannel.send(HomeMessage.SERVER_TRUSTED)
            onTrusted()
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory { initializer { HomeViewModel(container) } }
    }
}
