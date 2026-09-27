// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.dennisklein.sshovel.AppContainer
import com.github.dennisklein.sshovel.data.HostKeyAlreadyPinnedException
import com.github.dennisklein.sshovel.data.HostKeyInfo
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.keys.KeyEntry
import com.github.dennisklein.sshovel.tunnel.AlwaysOn
import com.github.dennisklein.sshovel.tunnel.Codes
import com.github.dennisklein.sshovel.tunnel.HostKeyFetchException
import com.github.dennisklein.sshovel.tunnel.NetworkChange
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
    /** False until the store was read: don't flash the empty state. */
    val loaded: Boolean = false,
    val state: TunnelState = TunnelState.Off,
    val profiles: List<Profile> = emptyList(),
    val defaultId: String? = null,
    /** The profile in the hero: the active one while connected, else the selected one. */
    val profile: Profile? = null,
    val stats: TunnelStats = TunnelStats(),
    val keyName: String? = null,
    val alwaysOn: AlwaysOn? = null,
    val networkChange: NetworkChange? = null,
    val verify: VerifyUi? = null,
    /** H7: asks before switching away from a running profile. */
    val switchTo: Profile? = null,
)

/** Things Home asks the navigation host to do. */
sealed interface HomeEvent {
    data class OpenKey(val keyId: String) : HomeEvent
    data class OpenProfile(val profileId: String, val focus: String? = null) : HomeEvent
    data object OpenMismatch : HomeEvent
    /** Connect through the explainer and Android's consent if needed. */
    data class Connect(val profileId: String) : HomeEvent
    data object ServerTrusted : HomeEvent
}

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val controller = container.tunnelController
    private val profiles = container.profiles
    private val verify = MutableStateFlow<VerifyUi?>(null)
    private val selectedId = MutableStateFlow<String?>(null)
    private val switchTo = MutableStateFlow<Profile?>(null)
    private val events = Channel<HomeEvent>(Channel.BUFFERED)
    val homeEvents: Flow<HomeEvent> = events.receiveAsFlow()

    private data class Tunnel(val state: TunnelState, val active: Profile?, val stats: TunnelStats, val alwaysOn: AlwaysOn?, val change: NetworkChange?)
    private data class Local(val selected: String?, val verify: VerifyUi?, val switchTo: Profile?)

    private val tunnel = combine(controller.state, controller.activeProfile, controller.stats, controller.alwaysOn, controller.networkChange, ::Tunnel)
    private val local = combine(selectedId, verify, switchTo, ::Local)

    val ui: StateFlow<HomeUiState> = combine(
        tunnel, local, container.store.state, container.keys.keys,
    ) { t, l, stored, keys ->
        val all = stored?.profiles.orEmpty()
        val defaultId = stored?.let { s -> s.defaultProfileId?.takeIf { id -> all.any { it.id == id } } ?: all.firstOrNull()?.id }
        // The stored copy of the active profile, so a pin made meanwhile shows.
        val active = t.active?.let { a -> all.firstOrNull { it.id == a.id } ?: a }
        val shown = active
            ?: l.selected?.let { id -> all.firstOrNull { it.id == id } }
            ?: all.firstOrNull { it.id == defaultId }
        HomeUiState(
            loaded = stored != null,
            state = t.state,
            profiles = all,
            defaultId = defaultId,
            profile = shown,
            stats = t.stats,
            keyName = shown?.let { keyName(keys, it) },
            alwaysOn = t.alwaysOn,
            networkChange = t.change,
            verify = l.verify,
            switchTo = l.switchTo,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        // S4 opens by itself the first time a host key mismatch shows up (handoff H6).
        viewModelScope.launch {
            var shown: TunnelState? = null
            controller.state.collect { s ->
                val mismatch = s is TunnelState.NeedsAttention && s.code == Codes.HOST_KEY_MISMATCH
                if (mismatch && s != shown) events.send(HomeEvent.OpenMismatch)
                shown = if (mismatch) s else null
            }
        }
    }

    /** Radio in a ProfileRow: select, or ask first while another profile is running (H7). */
    fun select(profile: Profile) {
        val s = ui.value
        if (s.state.isActive && s.profile != null && s.profile.id != profile.id) {
            switchTo.value = profile
        } else if (!s.state.isActive) {
            selectedId.value = profile.id
        }
    }

    fun confirmSwitch() {
        val target = switchTo.value ?: return
        switchTo.value = null
        selectedId.value = target.id
        // The service ends the running session before connecting the new one.
        viewModelScope.launch { events.send(HomeEvent.Connect(target.id)) }
    }

    fun cancelSwitch() {
        switchTo.value = null
    }

    /** Connect the shown profile (via consent if needed). */
    fun requestConnect() {
        val id = ui.value.profile?.id ?: return
        viewModelScope.launch { events.send(HomeEvent.Connect(id)) }
    }

    /** Called once consent is granted. */
    fun connect(profileId: String? = null) {
        (profileId ?: ui.value.profile?.id ?: profiles.defaultProfileNow()?.id)?.let(controller::connect)
    }

    fun disconnect() = controller.disconnect()

    fun retryNow() = controller.retryNow()

    /** "Disconnect" on S4: acknowledges the stopping error, back to Off. */
    fun dismissError() = controller.acknowledgeError()

    /** The primary fix action of a Needs attention error (DESIGN_BRIEF §8). */
    fun fix(code: String) {
        val p = ui.value.profile ?: return
        viewModelScope.launch {
            when (code) {
                Codes.AUTH_FAILED -> events.send(HomeEvent.OpenKey(p.auth.alias))
                Codes.HOST_KEY_UNVERIFIED -> startVerify()
                Codes.HOST_KEY_MISMATCH -> events.send(HomeEvent.OpenMismatch)
                Codes.KEY_UNAVAILABLE -> events.send(HomeEvent.OpenProfile(p.id))
                else -> events.send(HomeEvent.Connect(p.id))
            }
        }
    }

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

    /** "Trust this server": pins the shown key, then reconnects. */
    fun trust() {
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
            events.send(HomeEvent.ServerTrusted)
            events.send(HomeEvent.Connect(ready.profile.id))
        }
    }

    companion object {
        fun keyName(keys: List<KeyEntry>, p: Profile): String? = keys.firstOrNull { it.id == p.auth.alias }?.name

        fun factory(container: AppContainer) = viewModelFactory { initializer { HomeViewModel(container) } }
    }
}
