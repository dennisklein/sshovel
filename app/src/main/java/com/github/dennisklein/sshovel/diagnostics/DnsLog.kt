// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One answered DNS query, as core/dnsproxy reports it via Platform.OnDnsEvent (ARCHITECTURE §5). */
@Serializable
data class DnsEvent(
    val name: String,
    val qtype: String,
    /** [TUNNEL] or [DIRECT]. */
    val route: String,
    val rcode: String,
    val answers: List<String> = emptyList(),
    val latencyMs: Long = 0,
    /** Unix ms. */
    val ts: Long = 0,
    /** A tunnel-bound answer pointed outside every routed subnet (flagged in Diagnostics). */
    val resolvedOutsideRoutes: Boolean = false,
    val error: String? = null,
) {
    companion object {
        const val TUNNEL = "tunnel"
        const val DIRECT = "direct"
    }
}

/**
 * The last [capacity] DNS queries for Diagnostics (ARCHITECTURE §5). Names live only here, in
 * memory: never in logcat or on disk unless the user exports them (ARCHITECTURE §9).
 */
class DnsLog(private val capacity: Int = CAPACITY) {
    private val _events = MutableStateFlow<List<DnsEvent>>(emptyList())

    /** Oldest first. */
    val events: StateFlow<List<DnsEvent>> = _events.asStateFlow()

    fun add(event: DnsEvent) = _events.update { (it + event).takeLast(capacity) }

    /** Parses and adds an OnDnsEvent payload; malformed payloads are ignored. */
    fun addJson(json: String) {
        try {
            add(format.decodeFromString(DnsEvent.serializer(), json))
        } catch (_: IllegalArgumentException) {
        }
    }

    fun clear() {
        _events.value = emptyList()
    }

    companion object {
        const val CAPACITY = 500
        private val format = Json { ignoreUnknownKeys = true }
    }
}
