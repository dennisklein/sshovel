// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Severity of a diagnostics event; the Go core's levels are the ordinals (engine.LevelDebug…). */
enum class Level { DEBUG, INFO, WARN, ERROR }

/** Where an event comes from: the Go core's components, or Android ([SYSTEM]). */
enum class Component(val label: String) {
    SSH("SSH"), DNS("DNS"), TUNNEL("Tunnel"), SYSTEM("System");

    companion object {
        fun of(name: String) = entries.firstOrNull { it.label == name } ?: SYSTEM
    }
}

/** One line of the Events tab (DESIGN_BRIEF §5.9). */
data class LogEvent(val ts: Long, val level: Level, val component: Component, val message: String)

/**
 * The last [capacity] events for Diagnostics (ARCHITECTURE §7), fed by Platform.Log and by the
 * service's own Android-side events. Messages name hosts and destinations, so they live only
 * here, in memory: never on disk unless the user shares them (ARCHITECTURE §9).
 */
class EventLog(private val capacity: Int = CAPACITY, private val clock: () -> Long = System::currentTimeMillis) {
    private val _events = MutableStateFlow<List<LogEvent>>(emptyList())

    /** Oldest first. */
    val events: StateFlow<List<LogEvent>> = _events.asStateFlow()

    fun add(level: Level, component: Component, message: String) = add(LogEvent(clock(), level, component, message))

    fun add(event: LogEvent) = _events.update { (it + event).takeLast(capacity) }

    /** A Platform.Log call: [level] is the Go level, [component] its component name. */
    fun addFromCore(level: Int, component: String, message: String) =
        add(Level.entries.getOrElse(level) { Level.INFO }, Component.of(component), message)

    fun clear() {
        _events.value = emptyList()
    }

    /** Puts back what [clear] removed (the "Undo" of "Cleared"); newer events stay after them. */
    fun restore(old: List<LogEvent>) = _events.update { (old + it).takeLast(capacity) }

    companion object {
        const val CAPACITY = 2000
    }
}
