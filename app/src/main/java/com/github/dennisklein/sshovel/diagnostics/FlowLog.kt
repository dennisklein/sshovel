// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A Platform.OnFlowEvent payload (ARCHITECTURE §4, "Flow events"). */
@Serializable
data class FlowEvent(
    val id: Long,
    /** [OPEN], [CLOSE] or [FAIL]. */
    val event: String,
    val src: String = "",
    val dst: String = "",
    /** [FAIL] only: FORWARDING_DENIED, DEST_UNREACHABLE, DEST_TIMEOUT, TUNNEL_DOWN, … */
    val reason: String? = null,
    val bytesIn: Long = 0,
    val bytesOut: Long = 0,
    val durationMs: Long = 0,
    val ts: Long = 0,
) {
    companion object {
        const val OPEN = "open"
        const val CLOSE = "close"
        const val FAIL = "fail"
    }
}

/** An entry of Engine.FlowsJSON (ARCHITECTURE §8): an open flow with its bytes so far. */
@Serializable
data class OpenFlow(val id: Long, val src: String = "", val dst: String = "", val bytesIn: Long = 0, val bytesOut: Long = 0, val startTs: Long = 0)

/** Who a flow belongs to, worked out when it opens or fails: app label and the name its IP came from. */
data class FlowOwner(val app: String? = null, val host: String? = null)

data class ActiveFlow(val id: Long, val dst: String, val owner: FlowOwner, val startTs: Long, val bytesIn: Long, val bytesOut: Long)

data class FailedFlow(val id: Long, val dst: String, val owner: FlowOwner, val reason: String, val ts: Long)

/**
 * The Connections tab (DESIGN_BRIEF §5.9): open flows, live while Diagnostics polls
 * Engine.FlowsJSON, and the last [failedCapacity] failures. In memory only (ARCHITECTURE §9).
 */
class FlowLog(private val failedCapacity: Int = FAILED_CAPACITY, private val clock: () -> Long = System::currentTimeMillis) {
    private val _active = MutableStateFlow<List<ActiveFlow>>(emptyList())
    private val _failed = MutableStateFlow<List<FailedFlow>>(emptyList())

    /** Oldest first. */
    val active: StateFlow<List<ActiveFlow>> = _active.asStateFlow()

    /** Oldest first. */
    val failed: StateFlow<List<FailedFlow>> = _failed.asStateFlow()

    fun onEvent(ev: FlowEvent, owner: FlowOwner) {
        val ts = ev.ts.takeIf { it > 0 } ?: clock()
        when (ev.event) {
            FlowEvent.OPEN -> _active.update { list -> list.filter { it.id != ev.id } + ActiveFlow(ev.id, ev.dst, owner, ts, 0, 0) }
            FlowEvent.CLOSE -> _active.update { list -> list.filter { it.id != ev.id } }
            FlowEvent.FAIL -> _failed.update { (it + FailedFlow(ev.id, ev.dst, owner, ev.reason.orEmpty(), ts)).takeLast(failedCapacity) }
        }
    }

    /** Engine.FlowsJSON is the truth for what is open; owners come from the open events. */
    fun onSnapshot(open: List<OpenFlow>) = _active.update { old ->
        val owners = old.associate { it.id to it.owner }
        open.map { ActiveFlow(it.id, it.dst, owners[it.id] ?: FlowOwner(), it.startTs, it.bytesIn, it.bytesOut) }
    }

    /** The tunnel stopped: nothing is open any more. Failures stay until cleared. */
    fun endSession() {
        _active.value = emptyList()
    }

    fun clear() {
        _failed.value = emptyList()
    }

    fun restore(old: List<FailedFlow>) = _failed.update { (old + it).takeLast(failedCapacity) }

    companion object {
        const val FAILED_CAPACITY = 200
        private val format = Json { ignoreUnknownKeys = true }

        /** Parses an OnFlowEvent payload; null if malformed. */
        fun parseEvent(json: String): FlowEvent? = try {
            format.decodeFromString(FlowEvent.serializer(), json)
        } catch (_: IllegalArgumentException) {
            null
        }

        fun parseSnapshot(json: String): List<OpenFlow> = try {
            format.decodeFromString(ListSerializer(OpenFlow.serializer()), json)
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }
}
