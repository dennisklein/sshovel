// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.dennisklein.sshovel.AppContainer
import com.github.dennisklein.sshovel.data.HostKeyAlreadyPinnedException
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.data.ProfileInvalidException
import com.github.dennisklein.sshovel.keys.KeyEntry
import com.github.dennisklein.sshovel.keys.KeyImportException
import com.github.dennisklein.sshovel.tunnel.Codes
import com.github.dennisklein.sshovel.tunnel.ConnectionCheck
import com.github.dennisklein.sshovel.tunnel.HostKeyFetchException
import com.github.dennisklein.sshovel.tunnel.ServerCheckException
import com.github.dennisklein.sshovel.ui.screens.home.VerifyUi
import com.github.dennisklein.sshovel.ui.screens.profile.Cidr
import com.github.dennisklein.sshovel.ui.screens.profile.FieldMsg
import com.github.dennisklein.sshovel.ui.screens.profile.Fields
import com.github.dennisklein.sshovel.ui.screens.profile.ProfileDraft
import com.github.dennisklein.sshovel.ui.screens.profile.checkNewCidr
import com.github.dennisklein.sshovel.ui.screens.profile.fieldMessages
import com.github.dennisklein.sshovel.ui.screens.profile.isValidDomain
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** "Test connection" (handoff O6): null checks while running. */
data class TestUi(val running: Boolean, val checks: List<ConnectionCheck> = emptyList()) {
    val failed: ConnectionCheck? get() = checks.firstOrNull { it.status == ConnectionCheck.FAILED }
}

enum class TileOutcome { ADDED, DECLINED }

data class OnboardingUi(
    val loaded: Boolean = false,
    val step: Int = 1,
    val hasStrongBox: Boolean = false,
    // Step 2
    val keyName: String = "",
    val key: KeyEntry? = null,
    val creating: Boolean = false,
    val importOpen: Boolean = false,
    val importBusy: Boolean = false,
    val importError: String? = null,
    /** Step 2 created or imported a key in this run (vs. reusing the default profile's). */
    val keyIsNew: Boolean = false,
    // Step 3
    val qr: String? = null,
    // Step 4
    val draft: ProfileDraft = ProfileDraft(),
    val messages: Map<String, FieldMsg> = emptyMap(),
    val shown: Set<String> = emptySet(),
    val saving: Boolean = false,
    val profile: Profile? = null,
    // Step 5
    val verify: VerifyUi? = null,
    val test: TestUi? = null,
    // Step 6
    val tile: TileOutcome? = null,
    val declines: Int = 0,
) {
    /** O4: "Next" needs name, host, user, and one valid subnet. */
    val serverReady: Boolean
        get() = draft.name.isNotBlank() && draft.host.isNotBlank() && draft.user.isNotBlank() && draft.routes.isNotEmpty() &&
            messages.filterValues { !it.warning }.keys.none { it in SERVER_FIELDS || it.startsWith("routes") }

    fun messageFor(key: String): FieldMsg? = messages[key]?.takeIf { key in shown && !it.warning }

    companion object {
        const val STEPS = 7
        val SERVER_FIELDS = setOf(Fields.NAME, Fields.HOST, Fields.PORT, Fields.USER, Fields.DNS, Fields.SUFFIXES, Fields.ROUTES)
    }
}

sealed interface OnboardingEvent {
    data object Finished : OnboardingEvent
    data class ConnectNow(val profileId: String) : OnboardingEvent
    data object ServerTrusted : OnboardingEvent
    data object Copied : OnboardingEvent
}

/** The first-run flow (DESIGN_BRIEF §5.2): one key, one server, trusted and tested, the tile. */
class OnboardingViewModel(private val container: AppContainer, startStep: Int) : ViewModel() {
    private val _ui = MutableStateFlow(OnboardingUi(hasStrongBox = container.hasStrongBox))
    val ui: StateFlow<OnboardingUi> = _ui.asStateFlow()
    private val events = Channel<OnboardingEvent>(Channel.BUFFERED)
    val onboardingEvents: Flow<OnboardingEvent> = events.receiveAsFlow()

    init {
        viewModelScope.launch {
            // Reopened from Settings: continue with the default profile and its key.
            val stored = container.store.state.filterNotNull().first()
            val profile = container.profiles.defaultProfileNow()
            val key = profile?.let { p -> stored.keys.firstOrNull { it.id == p.auth.alias } } ?: stored.keys.lastOrNull()
            val step = startStep.coerceIn(1, OnboardingUi.STEPS).let { s ->
                // Steps that need a key or a server fall back to where those are made.
                when {
                    s >= 3 && key == null -> 2
                    s >= 5 && profile == null -> 4
                    else -> s
                }
            }
            _ui.update {
                it.copy(
                    loaded = true, step = step, key = key, profile = profile.takeIf { step >= 5 },
                    draft = ProfileDraft(keyId = key?.id, isDefault = stored.profiles.isEmpty()),
                )
            }
            enter(step)
        }
    }

    fun back(): Boolean {
        val s = _ui.value.step
        if (s <= 1) return false
        go(s - 1)
        return true
    }

    fun next() = go(_ui.value.step + 1)

    private fun go(step: Int) {
        if (step > OnboardingUi.STEPS) return
        _ui.update { it.copy(step = step, verify = null) }
        enter(step)
    }

    /** What a step does on entry: step 5 fetches the host key, or tests a pinned profile. */
    private fun enter(step: Int) {
        if (step == 5) {
            val p = currentProfile() ?: return
            if (p.hostKey == null) startVerify(p) else runTest()
        }
    }

    // ---- Step 2: key ---------------------------------------------------------------------

    fun setKeyName(v: String) = _ui.update { it.copy(keyName = v) }

    fun createKey(defaultName: String) {
        val name = _ui.value.keyName.ifBlank { defaultName }
        _ui.update { it.copy(creating = true) }
        viewModelScope.launch {
            val key = runCatching { container.keys.create(name) }.getOrNull()
            _ui.update { it.copy(creating = false, key = key ?: it.key, keyIsNew = key != null || it.keyIsNew, draft = it.draft.copy(keyId = key?.id ?: it.draft.keyId)) }
        }
    }

    fun openImport(open: Boolean) = _ui.update { it.copy(importOpen = open, importError = null) }

    fun clearImportError() = _ui.update { it.copy(importError = null) }

    fun importKey(name: String, bytes: ByteArray, passphrase: CharArray?) {
        _ui.update { it.copy(importBusy = true, importError = null) }
        viewModelScope.launch {
            try {
                val key = container.keys.import(name, bytes, passphrase)
                _ui.update { it.copy(importBusy = false, importOpen = false, key = key, keyIsNew = true, draft = it.draft.copy(keyId = key.id)) }
            } catch (e: KeyImportException) {
                _ui.update { it.copy(importBusy = false, importError = e.code) }
            } finally {
                bytes.fill(0)
                passphrase?.fill('\u0000')
            }
        }
    }

    fun showQr(line: String?) = _ui.update { it.copy(qr = line) }

    fun copied() {
        viewModelScope.launch { events.send(OnboardingEvent.Copied) }
    }

    // ---- Step 4: server --------------------------------------------------------------------

    fun edit(change: (ProfileDraft) -> ProfileDraft) {
        _ui.update { it.copy(draft = change(it.draft)) }
        revalidate()
    }

    fun touched(field: String) = _ui.update { it.copy(shown = it.shown + field) }

    /** A subnet chip; returns an error, or null when added. */
    fun addRoute(v: String): FieldMsg? {
        val err = checkNewCidr(v, _ui.value.draft.routes)
        if (err != null) return err
        edit { it.copy(routes = it.routes + Cidr.parse(v).toString()) }
        return null
    }

    fun removeRoute(v: String) = edit { it.copy(routes = it.routes - v) }

    fun addSuffix(v: String): FieldMsg? {
        val s = v.trim().trim('.').lowercase()
        if (!isValidDomain(s)) return FieldMsg(FieldMsg.Kind.DOMAIN_INVALID)
        if (s in _ui.value.draft.suffixes) return FieldMsg(FieldMsg.Kind.DUPLICATE)
        edit { it.copy(suffixes = it.suffixes + s) }
        return null
    }

    fun removeSuffix(v: String) = edit { it.copy(suffixes = it.suffixes - v) }

    private fun revalidate() {
        val u = _ui.value
        val issues = runCatching { container.profiles.validate(u.draft.toProfile(container.keys.keys.value)) }.getOrDefault(emptyList())
        _ui.update { it.copy(messages = fieldMessages(it.draft, issues)) }
    }

    /** "Next" on O4: saves the profile (the first one becomes the default). */
    fun saveServer() {
        val u = _ui.value
        _ui.update { it.copy(shown = it.shown + OnboardingUi.SERVER_FIELDS, saving = true) }
        if (!u.serverReady) {
            _ui.update { it.copy(saving = false) }
            return
        }
        viewModelScope.launch {
            try {
                // Going back to O4 and on again updates the same profile (draft.id is set after the first save).
                val saved = container.profiles.save(u.draft.toProfile(container.keys.keys.value))
                val draft = u.draft.copy(id = saved.id)
                _ui.update { it.copy(saving = false, profile = saved, draft = draft) }
                next()
            } catch (e: ProfileInvalidException) {
                _ui.update { it.copy(saving = false, messages = fieldMessages(it.draft, e.issues)) }
            }
        }
    }

    // ---- Step 5: verify and test -----------------------------------------------------------

    private fun startVerify(p: Profile) {
        _ui.update { it.copy(verify = VerifyUi.Loading(p), test = null) }
        viewModelScope.launch {
            val v = try {
                VerifyUi.Ready(p, container.hostKeys.fetch(p))
            } catch (e: HostKeyFetchException) {
                VerifyUi.Failed(p, e.code)
            }
            _ui.update { it.copy(verify = v) }
        }
    }

    fun retryVerify() = currentProfile()?.let(::startVerify)

    /** "Trust this server": pin, then the test runs by itself (handoff O6a). */
    fun trust() {
        val ready = _ui.value.verify as? VerifyUi.Ready ?: return
        viewModelScope.launch {
            try {
                val pinned = container.profiles.trustHostKey(ready.profile.id, ready.key)
                _ui.update { it.copy(profile = pinned, verify = null) }
                events.send(OnboardingEvent.ServerTrusted)
                // The pinned profile itself: the store's hot copy may not have caught up yet, and
                // testing that one fails with "Verify server first" (M8 acceptance run).
                runTest(pinned)
            } catch (_: HostKeyAlreadyPinnedException) {
                _ui.update { it.copy(verify = VerifyUi.Failed(ready.profile, Codes.HOST_KEY_MISMATCH)) }
            }
        }
    }

    fun runTest() = currentProfile()?.let(::runTest)

    private fun runTest(p: Profile) {
        _ui.update { it.copy(test = TestUi(running = true)) }
        viewModelScope.launch {
            val checks = try {
                container.serverChecks.testConnection(p)
            } catch (e: ServerCheckException) {
                // The key couldn't even be read, or the profile is invalid: the key check fails.
                listOf(ConnectionCheck.REACHABLE, ConnectionCheck.IDENTITY, ConnectionCheck.AUTH, ConnectionCheck.FORWARDING, ConnectionCheck.DNS).map { id ->
                    ConnectionCheck(id, if (id == ConnectionCheck.AUTH) ConnectionCheck.FAILED else ConnectionCheck.NOT_RUN, code = if (id == ConnectionCheck.AUTH) e.code else "")
                }
            }
            _ui.update { it.copy(test = TestUi(running = false, checks = checks)) }
        }
    }

    /** "Show public key" on a failed key check: back to step 3. */
    fun showPublicKey() = go(3)

    // ---- Step 6: tile ----------------------------------------------------------------------

    /** The result of StatusBarManager.requestAddTileService. */
    fun tileResult(added: Boolean) = _ui.update {
        it.copy(tile = if (added) TileOutcome.ADDED else TileOutcome.DECLINED, declines = if (added) it.declines else it.declines + 1)
    }

    fun retryTile() = _ui.update { it.copy(tile = null) }

    // ---- Done ----------------------------------------------------------------------------------

    fun finish(connect: Boolean) {
        viewModelScope.launch {
            container.settings.setOnboardingDone()
            val id = currentProfile()?.id
            events.send(if (connect && id != null) OnboardingEvent.ConnectNow(id) else OnboardingEvent.Finished)
        }
    }

    /** Skip: leave setup; it doesn't open by itself again. */
    fun skip() = finish(connect = false)

    fun defaultProfileName(): String? = container.profiles.defaultProfileNow()?.name

    private fun currentProfile(): Profile? = _ui.value.profile?.let { p -> container.store.state.value?.profiles?.firstOrNull { it.id == p.id } ?: p }

    companion object {
        fun factory(container: AppContainer, step: Int) = viewModelFactory { initializer { OnboardingViewModel(container, step) } }
    }
}
