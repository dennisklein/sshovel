// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.dennisklein.sshovel.AppContainer
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.keys.KeyEntry
import com.github.dennisklein.sshovel.keys.KeyImportException
import com.github.dennisklein.sshovel.keys.KeyInUseException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An open sheet: create (K4), import (K5), or the QR code of [line] (K6). */
sealed interface KeySheet {
    data object Create : KeySheet
    data object Import : KeySheet
    data class Qr(val line: String) : KeySheet
}

sealed interface KeyDialog {
    data class Rename(val key: KeyEntry) : KeyDialog
    /** K3: delete refused while [profiles] use the key. */
    data class InUse(val key: KeyEntry, val profiles: List<Profile>) : KeyDialog
    data class Delete(val key: KeyEntry) : KeyDialog
}

data class KeysUi(
    val loaded: Boolean = false,
    val keys: List<KeyEntry> = emptyList(),
    /** Profiles by key id, for "· Office, Lab" and "Used by". */
    val usedBy: Map<String, List<Profile>> = emptyMap(),
    val sheet: KeySheet? = null,
    val dialog: KeyDialog? = null,
    val busy: Boolean = false,
    /** Import error code: KEY_PASSPHRASE, KEY_UNSUPPORTED, KEY_PUTTY. */
    val importError: String? = null,
    val hasStrongBox: Boolean = false,
    val hasHardwareKeystore: Boolean = true,
)

sealed interface KeysEvent {
    data object Created : KeysEvent
    data object Imported : KeysEvent
    data object Renamed : KeysEvent
    data object Deleted : KeysEvent
}

/** Keys list and detail (DESIGN_BRIEF §5.6). Private keys never pass through here as Strings kept in state. */
class KeysViewModel(private val container: AppContainer) : ViewModel() {
    private val local = MutableStateFlow(KeysUi(hasStrongBox = container.hasStrongBox, hasHardwareKeystore = container.hasHardwareKeystore))
    private val events = Channel<KeysEvent>(Channel.BUFFERED)
    val keysEvents: Flow<KeysEvent> = events.receiveAsFlow()

    val ui: StateFlow<KeysUi> = combine(local, container.store.state) { l, stored ->
        val profiles = stored?.profiles.orEmpty()
        l.copy(
            loaded = stored != null,
            keys = stored?.keys.orEmpty(),
            usedBy = profiles.groupBy { it.auth.alias },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KeysUi(hasStrongBox = container.hasStrongBox, hasHardwareKeystore = container.hasHardwareKeystore))

    fun open(sheet: KeySheet) = local.update { it.copy(sheet = sheet, importError = null) }

    fun closeSheet() = local.update { it.copy(sheet = null, busy = false, importError = null) }

    fun create(name: String) {
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { container.keys.create(name) }
                .onSuccess {
                    local.update { it.copy(busy = false, sheet = null) }
                    events.send(KeysEvent.Created)
                }
                .onFailure { local.update { it.copy(busy = false) } }
        }
    }

    /** Imports [key] (zeroed afterwards, as is [passphrase]). */
    fun import(name: String, key: ByteArray, passphrase: CharArray?) {
        local.update { it.copy(busy = true, importError = null) }
        viewModelScope.launch {
            try {
                container.keys.import(name, key, passphrase)
                local.update { it.copy(busy = false, sheet = null) }
                events.send(KeysEvent.Imported)
            } catch (e: KeyImportException) {
                local.update { it.copy(busy = false, importError = e.code) }
            } finally {
                key.fill(0)
                passphrase?.fill('\u0000')
            }
        }
    }

    fun clearImportError() = local.update { it.copy(importError = null) }

    fun requestRename(key: KeyEntry) = local.update { it.copy(dialog = KeyDialog.Rename(key)) }

    fun rename(key: KeyEntry, name: String) {
        dismissDialog()
        viewModelScope.launch {
            container.keys.rename(key.id, name)
            events.send(KeysEvent.Renamed)
        }
    }

    /** Delete, or K3 when a profile still uses the key. */
    fun requestDelete(key: KeyEntry) {
        val users = ui.value.usedBy[key.id].orEmpty()
        local.update { it.copy(dialog = if (users.isNotEmpty()) KeyDialog.InUse(key, users) else KeyDialog.Delete(key)) }
    }

    fun delete(key: KeyEntry) {
        dismissDialog()
        viewModelScope.launch {
            try {
                container.keys.delete(key.id)
                events.send(KeysEvent.Deleted)
            } catch (e: KeyInUseException) {
                local.update { it.copy(dialog = KeyDialog.InUse(key, ui.value.usedBy[key.id].orEmpty())) }
            }
        }
    }

    fun dismissDialog() = local.update { it.copy(dialog = null) }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory { initializer { KeysViewModel(container) } }
    }
}
