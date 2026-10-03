// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.dennisklein.sshovel.AppContainer
import com.github.dennisklein.sshovel.diagnostics.ActiveFlow
import com.github.dennisklein.sshovel.diagnostics.Component
import com.github.dennisklein.sshovel.diagnostics.Diagnostics
import com.github.dennisklein.sshovel.diagnostics.DiagnosticsReport
import com.github.dennisklein.sshovel.diagnostics.DnsEvent
import com.github.dennisklein.sshovel.diagnostics.FailedFlow
import com.github.dennisklein.sshovel.diagnostics.Level
import com.github.dennisklein.sshovel.diagnostics.LogEvent
import com.github.dennisklein.sshovel.tunnel.TunnelState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

enum class DiagTab { EVENTS, DNS, CONNECTIONS }

data class DnsCounts(val tunneled: Int = 0, val direct: Int = 0, val failed: Int = 0)

data class DiagnosticsUi(
    val tunnelOff: Boolean = true,
    /** Any buffer holds something: the bar's Pause, Share and overflow show (handoff G4). */
    val hasData: Boolean = false,
    /** Events after the level and component filters, oldest first. */
    val events: List<LogEvent> = emptyList(),
    val anyEvents: Boolean = false,
    val minLevel: Level = Level.DEBUG,
    val components: Set<Component> = Component.entries.toSet(),
    /** Newest first; direct queries collapse to the last [DIRECT_SHOWN]. */
    val dns: List<DnsEvent> = emptyList(),
    val dnsCounts: DnsCounts = DnsCounts(),
    /** Newest first. */
    val active: List<ActiveFlow> = emptyList(),
    /** Newest first. */
    val failed: List<FailedFlow> = emptyList(),
    val paused: Boolean = false,
    val newWhilePaused: Int = 0,
    val badges: Map<DiagTab, Int> = emptyMap(),
    /** For ages ("4 s ago"); ticks every second while on screen. */
    val now: Long = 0,
    /** Strict Private DNS hostname: apps resolve through it, not through the tunnel. */
    val privateDnsHost: String? = null,
) {
    companion object {
        const val DIRECT_SHOWN = 200
    }
}

/** Diagnostics (DESIGN_BRIEF §5.9, handoff G1–G4). */
class DiagnosticsViewModel(
    private val d: Diagnostics,
    tunnelState: StateFlow<TunnelState>,
    privateDnsHost: StateFlow<String?> = kotlinx.coroutines.flow.MutableStateFlow(null),
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private data class Data(val events: List<LogEvent>, val dns: List<DnsEvent>, val active: List<ActiveFlow>, val failed: List<FailedFlow>)
    private data class View(val minLevel: Level, val components: Set<Component>, val tab: DiagTab, val frozen: Data?, val pausedAt: Long)

    private val view = MutableStateFlow(View(Level.DEBUG, Component.entries.toSet(), DiagTab.EVENTS, null, 0))
    private val live: Flow<Data> = combine(d.events.events, d.dns.events, d.flows.active, d.flows.failed, ::Data)
    private val ticker = flow {
        while (true) {
            emit(clock())
            delay(1_000)
        }
    }
    private var watch: AutoCloseable? = null
    private var cleared: Diagnostics.Cleared? = null

    val ui: StateFlow<DiagnosticsUi> = combine(live, view, ticker, tunnelState, privateDnsHost) { live, v, now, state, privateDns ->
        val shown = v.frozen ?: live
        d.seenUntil.set(v.tab.ordinal, now) // the open tab is being read
        DiagnosticsUi(
            tunnelOff = state == TunnelState.Off || state is TunnelState.NeedsAttention,
            hasData = live.events.isNotEmpty() || live.dns.isNotEmpty() || live.active.isNotEmpty() || live.failed.isNotEmpty(),
            events = shown.events.filter { it.level >= v.minLevel && it.component in v.components },
            anyEvents = shown.events.isNotEmpty(),
            minLevel = v.minLevel,
            components = v.components,
            dns = dnsShown(shown.dns),
            dnsCounts = DnsCounts(
                tunneled = shown.dns.count { it.route == DnsEvent.TUNNEL },
                direct = shown.dns.count { it.route == DnsEvent.DIRECT },
                failed = shown.dns.count(::failed),
            ),
            active = shown.active.asReversed(),
            failed = shown.failed.asReversed(),
            paused = v.frozen != null,
            newWhilePaused = if (v.frozen == null) 0 else
                live.events.count { it.ts > v.pausedAt } + live.dns.count { it.ts > v.pausedAt } + live.failed.count { it.ts > v.pausedAt },
            badges = mapOf(
                DiagTab.EVENTS to live.events.count { it.level >= Level.WARN && it.ts > d.seenUntil[0] },
                DiagTab.DNS to live.dns.count { (failed(it) || it.resolvedOutsideRoutes) && it.ts > d.seenUntil[1] },
                DiagTab.CONNECTIONS to live.failed.count { it.ts > d.seenUntil[2] },
            ),
            now = now,
            privateDnsHost = privateDns,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUi())

    fun showTab(tab: DiagTab) {
        view.value = view.value.copy(tab = tab)
        d.seenUntil.set(tab.ordinal, clock())
    }

    /** Polls open flows (bytes, ages) while the Connections tab is on screen and the app visible. */
    fun watchFlows(on: Boolean) {
        if (on && watch == null) watch = d.watchFlows()
        if (!on) {
            watch?.close()
            watch = null
        }
    }

    fun setMinLevel(level: Level) {
        view.value = view.value.copy(minLevel = level)
    }

    fun toggle(component: Component) {
        val c = view.value.components
        view.value = view.value.copy(components = if (component in c) c - component else c + component)
    }

    fun togglePause() {
        val v = view.value
        view.value = if (v.frozen != null) v.copy(frozen = null) else {
            v.copy(frozen = Data(d.events.events.value, d.dns.events.value, d.flows.active.value, d.flows.failed.value), pausedAt = clock())
        }
    }

    /** Clears every buffer; [undoClear] puts it back ("Cleared · Undo", handoff G3). */
    fun clear() {
        cleared = d.clearAll()
        view.value = view.value.copy(frozen = null)
    }

    fun undoClear() {
        cleared?.let(d::restore)
        cleared = null
    }

    fun report(titles: DiagnosticsReport.Titles): String = DiagnosticsReport.build(d, titles)

    fun reportFileName(): String = DiagnosticsReport.fileName()

    override fun onCleared() = watchFlows(false)

    companion object {
        fun failed(e: DnsEvent) = e.rcode != "NOERROR" || e.error != null

        /** Newest first, with direct queries cut to the last [DiagnosticsUi.DIRECT_SHOWN]. */
        fun dnsShown(all: List<DnsEvent>): List<DnsEvent> {
            var direct = 0
            return all.asReversed().filter { it.route != DnsEvent.DIRECT || ++direct <= DiagnosticsUi.DIRECT_SHOWN }
        }

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { DiagnosticsViewModel(container.diagnostics, container.tunnelController.state, container.networkMonitor.privateDnsHost) }
        }
    }
}
