// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.dennisklein.sshovel.AppContainer
import com.github.dennisklein.sshovel.data.AppInfo
import com.github.dennisklein.sshovel.data.Apps
import com.github.dennisklein.sshovel.data.HostKey
import com.github.dennisklein.sshovel.data.HostKeyAlreadyPinnedException
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.data.ProfileInvalidException
import com.github.dennisklein.sshovel.keys.KeyEntry
import com.github.dennisklein.sshovel.tunnel.Codes
import com.github.dennisklein.sshovel.tunnel.DiscoveredRoute
import com.github.dennisklein.sshovel.tunnel.HostKeyFetchException
import com.github.dennisklein.sshovel.tunnel.ServerCheckException
import com.github.dennisklein.sshovel.tunnel.TunnelState
import com.github.dennisklein.sshovel.ui.screens.home.VerifyUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The "Discover from server" sheet (handoff D1–D3). */
sealed interface DiscoverUi {
    data object Loading : DiscoverUi
    data class Results(val routes: List<DiscoveredRoute>, val selected: Set<String>) : DiscoverUi
    data class Failed(val code: String, val timeoutSec: Int) : DiscoverUi
}

sealed interface EditorDialog {
    data class Discard(val changes: Changes) : EditorDialog
    data object Forget : EditorDialog
    /** [newDefault]: the profile that becomes the default, if this one is it. */
    data class Delete(val newDefault: String?) : EditorDialog
}

data class EditorUi(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val draft: ProfileDraft = ProfileDraft(),
    val saved: ProfileDraft = ProfileDraft(),
    /** The stored pin; only changed by trust (unpinned) or forget. */
    val hostKey: HostKey? = null,
    val keys: List<KeyEntry> = emptyList(),
    val messages: Map<String, FieldMsg> = emptyMap(),
    /** Fields whose messages are shown: touched ones, or all after a save attempt. */
    val shown: Set<String> = emptySet(),
    val showAll: Boolean = false,
    /** P6: this profile is running; fields are disabled until it stops. */
    val readOnly: Boolean = false,
    val subnetInput: String = "",
    val subnetError: FieldMsg? = null,
    val excludedInput: String = "",
    val excludedError: FieldMsg? = null,
    val excludedExpanded: Boolean = false,
    val advancedExpanded: Boolean = false,
    val dialog: EditorDialog? = null,
    val verify: VerifyUi? = null,
    val discover: DiscoverUi? = null,
    val appLabels: List<String> = emptyList(),
    val wasDefault: Boolean = false,
) {
    val dirty: Boolean get() = draft != saved
    val errors: Map<String, FieldMsg> get() = messages.filterValues { !it.warning }

    /** A field's message if it should be shown now. */
    fun messageFor(key: String): FieldMsg? = messages[key]?.takeIf { showAll || key in shown || it.warning }
}

sealed interface EditorEvent {
    data object Saved : EditorEvent
    data object Deleted : EditorEvent
    data class FixProblems(val count: Int, val firstField: String) : EditorEvent
    data class Focus(val field: String) : EditorEvent
    data class SubnetRemoved(val cidr: String, val excluded: Boolean) : EditorEvent
    data object Forgotten : EditorEvent
    data object Trusted : EditorEvent
    data object IdentityMatches : EditorEvent
}

class ProfileEditorViewModel(private val container: AppContainer, private val profileId: String?) : ViewModel() {
    private val _ui = MutableStateFlow(EditorUi())
    val ui: StateFlow<EditorUi> = _ui.asStateFlow()
    private val events = Channel<EditorEvent>(Channel.BUFFERED)
    val editorEvents: Flow<EditorEvent> = events.receiveAsFlow()
    private var discoverJob: Job? = null

    /** Launchable apps, loaded for the app picker. */
    val apps = MutableStateFlow<List<AppInfo>?>(null)

    init {
        // Keys and the pin can change under us (Keys screen, verify); the tunnel sets read-only.
        viewModelScope.launch {
            combine(container.store.state.filterNotNull(), container.tunnelController.state, container.tunnelController.activeProfile) { s, t, a ->
                Triple(s, t, a)
            }.collect { (stored, state, active) ->
                val p = profileId?.let { id -> stored.profiles.firstOrNull { it.id == id } }
                val readOnly = profileId != null && active?.id == profileId && state !is TunnelState.Off && state !is TunnelState.NeedsAttention
                _ui.update { u ->
                    if (!u.loaded) {
                        val isDefault = p != null && (stored.defaultProfileId ?: stored.profiles.firstOrNull()?.id) == p.id
                        val draft = if (p != null) {
                            ProfileDraft.of(p, isDefault)
                        } else {
                            // P1: the newest key preselected, port 22; the first profile becomes the default.
                            ProfileDraft(keyId = stored.keys.lastOrNull()?.id, isDefault = stored.profiles.isEmpty())
                        }
                        EditorUi(
                            loaded = true, isNew = p == null, draft = draft, saved = draft, hostKey = p?.hostKey,
                            keys = stored.keys, wasDefault = isDefault, readOnly = readOnly,
                        )
                    } else {
                        // A key created or imported from the editor shows up selected (P1).
                        val newKey = stored.keys.lastOrNull()?.takeIf { k -> u.keys.none { it.id == k.id } }
                        val draft = if (newKey != null && !readOnly) u.draft.copy(keyId = newKey.id) else u.draft
                        u.copy(keys = stored.keys, hostKey = p?.hostKey, draft = draft, readOnly = readOnly)
                    }
                }
                revalidate()
                refreshAppLabels()
            }
        }
    }

    // ---- Editing -------------------------------------------------------------------

    fun edit(field: String, change: (ProfileDraft) -> ProfileDraft) {
        if (_ui.value.readOnly) return
        _ui.update { it.copy(draft = change(it.draft)) }
        revalidate()
        if (field == Fields.APPS) refreshAppLabels()
    }

    /** The field lost focus: show its message from now on (P3: "fires on blur and on add"). */
    fun touched(field: String) = _ui.update { it.copy(shown = it.shown + field) }

    fun setSubnetInput(v: String) = _ui.update { it.copy(subnetInput = v, subnetError = null) }

    fun setExcludedInput(v: String) = _ui.update { it.copy(excludedInput = v, excludedError = null) }

    fun addSubnet() {
        val u = _ui.value
        val v = u.subnetInput.trim()
        if (v.isEmpty()) return
        val err = checkNewCidr(v, u.draft.routes)
        if (err != null) {
            _ui.update { it.copy(subnetError = err) }
            return
        }
        edit(Fields.ROUTES) { it.copy(routes = it.routes + Cidr.parse(v).toString()) }
        _ui.update { it.copy(subnetInput = "", shown = it.shown + Fields.ROUTES) }
    }

    fun addExcluded() {
        val u = _ui.value
        val v = u.excludedInput.trim()
        if (v.isEmpty()) return
        val err = checkNewCidr(v, u.draft.excluded)
        if (err != null) {
            _ui.update { it.copy(excludedError = err) }
            return
        }
        edit(Fields.ROUTES) { it.copy(excluded = it.excluded + Cidr.parse(v).toString()) }
        _ui.update { it.copy(excludedInput = "") }
    }

    fun removeSubnet(cidr: String, excluded: Boolean) {
        edit(Fields.ROUTES) { if (excluded) it.copy(excluded = it.excluded - cidr) else it.copy(routes = it.routes - cidr) }
        viewModelScope.launch { events.send(EditorEvent.SubnetRemoved(cidr, excluded)) }
    }

    fun undoRemove(cidr: String, excluded: Boolean) =
        edit(Fields.ROUTES) { if (excluded) it.copy(excluded = it.excluded + cidr) else it.copy(routes = it.routes + cidr) }

    /** A suffix or search domain chip. Returns an error message kind, or null when added. */
    fun addDomain(suffix: Boolean, value: String): FieldMsg? {
        val v = value.trim().trim('.').lowercase()
        val list = if (suffix) _ui.value.draft.suffixes else _ui.value.draft.searchDomains
        if (!isValidDomain(v)) return FieldMsg(FieldMsg.Kind.DOMAIN_INVALID)
        if (v in list) return FieldMsg(FieldMsg.Kind.DUPLICATE)
        edit(if (suffix) Fields.SUFFIXES else Fields.SEARCH) { if (suffix) it.copy(suffixes = it.suffixes + v) else it.copy(searchDomains = it.searchDomains + v) }
        return null
    }

    fun removeDomain(suffix: Boolean, value: String) =
        edit(if (suffix) Fields.SUFFIXES else Fields.SEARCH) { if (suffix) it.copy(suffixes = it.suffixes - value) else it.copy(searchDomains = it.searchDomains - value) }

    fun toggleExcluded() = _ui.update { it.copy(excludedExpanded = !it.excludedExpanded) }

    fun toggleAdvanced() = _ui.update { it.copy(advancedExpanded = !it.advancedExpanded) }

    fun setAppsMode(mode: String) = edit(Fields.APPS) { it.copy(appsMode = mode) }

    fun setPackages(packages: List<String>) = edit(Fields.APPS) { it.copy(packages = packages) }

    fun loadApps() {
        if (apps.value != null) return
        viewModelScope.launch { apps.value = container.installedApps.launchable() }
    }

    private fun refreshAppLabels() {
        val pkgs = _ui.value.draft.packages
        viewModelScope.launch {
            val labels = container.installedApps.labels(pkgs)
            _ui.update { it.copy(appLabels = labels) }
        }
    }

    private fun revalidate() {
        val u = _ui.value
        if (!u.loaded) return
        val profile = u.draft.toProfile(u.keys)
        val issues = runCatching { container.profiles.validate(profile) }.getOrDefault(emptyList())
        _ui.update { it.copy(messages = fieldMessages(it.draft, issues)) }
    }

    // ---- Save, back, delete ----------------------------------------------------------

    fun save() {
        val u = _ui.value
        if (u.readOnly) return
        val errors = u.errors
        if (errors.isNotEmpty()) {
            _ui.update { it.copy(showAll = true) }
            val first = errors.keys.minBy(Fields::order)
            viewModelScope.launch { events.send(EditorEvent.FixProblems(errors.size, first)) }
            return
        }
        viewModelScope.launch {
            try {
                val saved = container.profiles.save(u.draft.toProfile(u.keys))
                if (u.draft.isDefault && !u.wasDefault) container.profiles.setDefault(saved.id)
                val draft = u.draft.copy(id = saved.id)
                _ui.update { it.copy(draft = draft, saved = draft, isNew = false) }
                events.send(EditorEvent.Saved)
            } catch (e: ProfileInvalidException) {
                _ui.update { it.copy(showAll = true, messages = fieldMessages(it.draft, e.issues)) }
            }
        }
    }

    /** Back or close: asks first when there are unsaved changes (P5). Returns true if it may leave. */
    fun requestLeave(): Boolean {
        val u = _ui.value
        if (!u.dirty || u.readOnly) return true
        _ui.update { it.copy(dialog = EditorDialog.Discard(changesBetween(u.saved, u.draft))) }
        return false
    }

    fun requestDelete() {
        viewModelScope.launch {
            val stored = container.store.current()
            val isDefault = (stored.defaultProfileId ?: stored.profiles.firstOrNull()?.id) == profileId
            val next = if (isDefault) stored.profiles.firstOrNull { it.id != profileId }?.name else null
            _ui.update { it.copy(dialog = EditorDialog.Delete(next)) }
        }
    }

    fun delete() {
        val id = profileId ?: return
        dismissDialog()
        viewModelScope.launch {
            container.profiles.delete(id)
            events.send(EditorEvent.Deleted)
        }
    }

    fun requestForget() = _ui.update { it.copy(dialog = EditorDialog.Forget) }

    /** "Forget key" (P7): an explicit user action, the only way to drop a pin (CLAUDE.md, Host keys). */
    fun forget() {
        val id = profileId ?: return
        dismissDialog()
        viewModelScope.launch {
            container.profiles.forgetHostKey(id)
            events.send(EditorEvent.Forgotten)
        }
    }

    fun dismissDialog() = _ui.update { it.copy(dialog = null) }

    // ---- Server identity ---------------------------------------------------------------

    /** "Verify now": fetch the key the server presents; with a pin, only compare (never replace). */
    fun startVerify() {
        val p = storedProfile() ?: return
        _ui.update { it.copy(verify = VerifyUi.Loading(p)) }
        viewModelScope.launch {
            val result = try {
                val key = container.hostKeys.fetch(p)
                val pin = p.hostKey
                when {
                    pin == null -> VerifyUi.Ready(p, key)
                    pin.type == key.type && pin.fingerprint == key.fingerprint -> {
                        events.send(EditorEvent.IdentityMatches)
                        null
                    }
                    else -> VerifyUi.Failed(p, Codes.HOST_KEY_MISMATCH)
                }
            } catch (e: HostKeyFetchException) {
                VerifyUi.Failed(p, e.code)
            }
            _ui.update { it.copy(verify = result) }
        }
    }

    fun cancelVerify() = _ui.update { it.copy(verify = null) }

    fun trust() {
        val ready = _ui.value.verify as? VerifyUi.Ready ?: return
        viewModelScope.launch {
            try {
                container.profiles.trustHostKey(ready.profile.id, ready.key)
                _ui.update { it.copy(verify = null) }
                events.send(EditorEvent.Trusted)
            } catch (_: HostKeyAlreadyPinnedException) {
                _ui.update { it.copy(verify = VerifyUi.Failed(ready.profile, Codes.HOST_KEY_MISMATCH)) }
            }
        }
    }

    // ---- Discover subnets -----------------------------------------------------------------

    fun discover() {
        val p = storedProfile() ?: return
        _ui.update { it.copy(discover = DiscoverUi.Loading) }
        discoverJob?.cancel()
        discoverJob = viewModelScope.launch {
            val result = try {
                val routes = withContext(Dispatchers.IO) { container.serverChecks.discoverRoutes(p) }
                val existing = _ui.value.draft.routes.toSet()
                DiscoverUi.Results(routes, routes.filter { !it.isDefault && !it.isLinkLocal && it.cidr !in existing }.map { it.cidr }.toSet())
            } catch (e: ServerCheckException) {
                DiscoverUi.Failed(e.code, p.connectTimeoutSec + 15)
            }
            _ui.update { it.copy(discover = result) }
        }
    }

    fun toggleDiscovered(cidr: String) = _ui.update { u ->
        val r = u.discover as? DiscoverUi.Results ?: return@update u
        u.copy(discover = r.copy(selected = if (cidr in r.selected) r.selected - cidr else r.selected + cidr))
    }

    fun addDiscovered() {
        val r = _ui.value.discover as? DiscoverUi.Results ?: return
        val add = r.routes.map { it.cidr }.filter { it in r.selected && it !in _ui.value.draft.routes }
        edit(Fields.ROUTES) { it.copy(routes = it.routes + add) }
        closeDiscover()
    }

    fun closeDiscover() {
        discoverJob?.cancel()
        _ui.update { it.copy(discover = null) }
    }

    /** "Add by hand" (D3): close the sheet and focus the subnet field. */
    fun addByHand() {
        closeDiscover()
        viewModelScope.launch { events.send(EditorEvent.Focus(Fields.ROUTES)) }
    }

    fun disconnect() = container.tunnelController.disconnect()

    private fun storedProfile(): Profile? = profileId?.let { id -> container.store.state.value?.profiles?.firstOrNull { it.id == id } }

    companion object {
        fun factory(container: AppContainer, profileId: String?) = viewModelFactory {
            initializer { ProfileEditorViewModel(container, profileId) }
        }

        const val MODE_ALL = Apps.ALL
    }
}
