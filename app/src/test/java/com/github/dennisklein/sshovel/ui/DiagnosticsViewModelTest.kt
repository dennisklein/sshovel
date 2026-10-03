// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui

import com.github.dennisklein.sshovel.diagnostics.Component
import com.github.dennisklein.sshovel.diagnostics.Diagnostics
import com.github.dennisklein.sshovel.diagnostics.DnsEvent
import com.github.dennisklein.sshovel.diagnostics.FlowEvent
import com.github.dennisklein.sshovel.diagnostics.FlowOwner
import com.github.dennisklein.sshovel.diagnostics.Level
import com.github.dennisklein.sshovel.diagnostics.LogEvent
import com.github.dennisklein.sshovel.tunnel.TunnelState
import com.github.dennisklein.sshovel.ui.screens.diagnostics.DiagTab
import com.github.dennisklein.sshovel.ui.screens.diagnostics.DiagnosticsUi
import com.github.dennisklein.sshovel.ui.screens.diagnostics.DiagnosticsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {
    private var now = 1_000L
    private val d = Diagnostics()
    private val state = MutableStateFlow<TunnelState>(TunnelState.On())

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = DiagnosticsViewModel(d, state) { now }

    private suspend fun DiagnosticsViewModel.current(ok: (DiagnosticsUi) -> Boolean = { true }) = ui.first { it.now > 0 && ok(it) }

    @Test fun filtersByLevelAndComponent() = runTest {
        d.events.add(LogEvent(1, Level.INFO, Component.SSH, "a"))
        d.events.add(LogEvent(2, Level.WARN, Component.DNS, "b"))
        d.events.add(LogEvent(3, Level.ERROR, Component.TUNNEL, "c"))
        val vm = vm()
        backgroundScope.launch { vm.ui.collect {} }
        assertEquals(listOf("a", "b", "c"), vm.current().events.map { it.message })
        vm.setMinLevel(Level.WARN)
        assertEquals(listOf("b", "c"), vm.current { it.minLevel == Level.WARN }.events.map { it.message })
        vm.toggle(Component.DNS)
        assertEquals(listOf("c"), vm.current { Component.DNS !in it.components }.events.map { it.message })
    }

    @Test fun dnsIsNewestFirstWithDirectCollapsed() = runTest {
        repeat(250) { d.dns.add(DnsEvent("d$it.", "A", DnsEvent.DIRECT, "NOERROR", ts = it.toLong())) }
        d.dns.add(DnsEvent("wiki.corp.test.", "A", DnsEvent.TUNNEL, "NXDOMAIN", ts = 300))
        val vm = vm()
        backgroundScope.launch { vm.ui.collect {} }
        val ui = vm.current()
        assertEquals("wiki.corp.test.", ui.dns.first().name)
        assertEquals(1 + DiagnosticsUi.DIRECT_SHOWN, ui.dns.size)
        assertEquals("d50.", ui.dns.last().name)
        assertEquals(1, ui.dnsCounts.tunneled)
        assertEquals(250, ui.dnsCounts.direct)
        assertEquals(1, ui.dnsCounts.failed)
    }

    @Test fun pauseFreezesAndCountsNew() = runTest {
        d.events.add(LogEvent(1, Level.INFO, Component.SSH, "before"))
        val vm = vm()
        backgroundScope.launch { vm.ui.collect {} }
        vm.togglePause()
        now = 2_000
        d.events.add(LogEvent(2_500, Level.INFO, Component.SSH, "after"))
        val paused = vm.current { it.newWhilePaused == 1 }
        assertTrue(paused.paused)
        assertEquals(listOf("before"), paused.events.map { it.message })
        vm.togglePause()
        assertEquals(listOf("before", "after"), vm.current { !it.paused }.events.map { it.message })
    }

    @Test fun badgesCountUnseenProblemsOnOtherTabs() = runTest {
        d.flows.onEvent(FlowEvent(1, FlowEvent.FAIL, dst = "10.0.0.1:443", reason = "DEST_TIMEOUT", ts = 500), FlowOwner())
        d.dns.add(DnsEvent("x.", "A", DnsEvent.TUNNEL, "SERVFAIL", ts = 500))
        d.events.add(LogEvent(500, Level.WARN, Component.DNS, "w"))
        val vm = vm()
        backgroundScope.launch { vm.ui.collect {} }
        val ui = vm.current { it.badges[DiagTab.CONNECTIONS] == 1 }
        assertEquals(1, ui.badges[DiagTab.DNS])
        assertEquals(0, ui.badges[DiagTab.EVENTS]) // the open tab is being read
        vm.showTab(DiagTab.CONNECTIONS)
        assertEquals(0, vm.current { it.badges[DiagTab.CONNECTIONS] == 0 }.badges[DiagTab.CONNECTIONS])
    }

    @Test fun clearAndUndo() = runTest {
        d.events.add(LogEvent(1, Level.INFO, Component.SSH, "a"))
        val vm = vm()
        backgroundScope.launch { vm.ui.collect {} }
        vm.clear()
        assertFalse(vm.current { !it.hasData }.hasData)
        vm.undoClear()
        assertTrue(vm.current { it.hasData }.anyEvents)
    }

    @Test fun watchingFlowsFollowsTheTab() {
        val vm = vm()
        vm.watchFlows(true)
        vm.watchFlows(true)
        assertTrue(d.watchingFlows)
        vm.watchFlows(false)
        assertFalse(d.watchingFlows)
    }

    @Test fun offWithNothingKeptIsTheEmptyState() = runTest {
        state.value = TunnelState.Off
        val vm = vm()
        backgroundScope.launch { vm.ui.collect {} }
        val ui = vm.current()
        assertTrue(ui.tunnelOff)
        assertFalse(ui.hasData)
    }
}
