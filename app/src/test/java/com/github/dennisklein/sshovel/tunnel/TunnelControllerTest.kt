// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.tunnel

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TunnelControllerTest {
    private class Recorder : TunnelCommands {
        val calls = mutableListOf<String>()
        override fun connect(profileId: String) { calls += "connect $profileId" }
        override fun disconnect() { calls += "disconnect" }
        override fun retryNow() { calls += "retry" }
    }

    @Test fun networkLostOverlaysReconnecting() = runTest(UnconfinedTestDispatcher()) {
        val network = MutableStateFlow(true)
        val c = TunnelController(Recorder(), network, backgroundScope)
        c.state.test {
            assertEquals(TunnelState.Off, awaitItem())
            val r = TunnelState.Reconnecting("connectionClosed", 1, 5_000, "HOST_UNREACHABLE")
            c.onState(r)
            assertEquals(r, awaitItem())
            network.value = false
            assertEquals(r.copy(code = Codes.NETWORK_LOST), awaitItem())
            network.value = true
            assertEquals(r, awaitItem())
            c.onState(TunnelState.On())
            assertEquals(TunnelState.On(), awaitItem())
            network.value = false // no overlay while On
            expectNoEvents()
        }
    }

    @Test fun commandsGoToTheService() = runTest(UnconfinedTestDispatcher()) {
        val r = Recorder()
        val c = TunnelController(r, MutableStateFlow(true), backgroundScope)
        c.connect("p1")
        c.retryNow()
        c.disconnect()
        assertEquals(listOf("connect p1", "retry", "disconnect"), r.calls)
    }

    @Test fun statsResetWhenOff() = runTest(UnconfinedTestDispatcher()) {
        val c = TunnelController(Recorder(), MutableStateFlow(true), backgroundScope)
        c.onStats(TunnelStats(bytesIn = 5))
        c.onState(TunnelState.Off)
        assertEquals(TunnelStats(), c.stats.value)
    }
}
