// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class DiagnosticsTest {
    @Test fun eventLogMapsCoreLevelsAndComponents() {
        val log = EventLog(clock = { 42 })
        log.addFromCore(2, "DNS", "intranet DNS server is not answering through the tunnel")
        log.addFromCore(1, "SSH", "host key verified")
        log.addFromCore(9, "Something", "x")
        assertEquals(
            listOf(
                LogEvent(42, Level.WARN, Component.DNS, "intranet DNS server is not answering through the tunnel"),
                LogEvent(42, Level.INFO, Component.SSH, "host key verified"),
                LogEvent(42, Level.INFO, Component.SYSTEM, "x"),
            ),
            log.events.value,
        )
    }

    @Test fun eventLogKeepsTheLast2000AndUndoesAClear() {
        val log = EventLog()
        repeat(2010) { log.add(Level.INFO, Component.TUNNEL, "e$it") }
        assertEquals(2000, log.events.value.size)
        assertEquals("e10", log.events.value.first().message)
        val old = log.events.value
        log.clear()
        log.add(Level.INFO, Component.SSH, "after")
        log.restore(old)
        assertEquals("after", log.events.value.last().message)
        assertEquals(2000, log.events.value.size)
    }

    @Test fun flowLogTracksOpenCloseFailAndSnapshots() {
        val flows = FlowLog()
        val open = FlowLog.parseEvent("""{"id":1,"event":"open","src":"198.18.0.1:40000","dst":"10.77.0.20:80","bytesIn":0,"bytesOut":0,"durationMs":0,"ts":1000}""")!!
        flows.onEvent(open, FlowOwner("Chrome", "wiki.corp.test"))
        flows.onSnapshot(FlowLog.parseSnapshot("""[{"id":1,"src":"198.18.0.1:40000","dst":"10.77.0.20:80","bytesIn":1200,"bytesOut":88,"startTs":1000}]"""))
        assertEquals(listOf(ActiveFlow(1, "10.77.0.20:80", FlowOwner("Chrome", "wiki.corp.test"), 1000, 1200, 88)), flows.active.value)

        flows.onEvent(FlowLog.parseEvent("""{"id":2,"event":"fail","src":"198.18.0.1:40001","dst":"10.77.0.99:443","reason":"FORWARDING_DENIED","ts":2000}""")!!, FlowOwner())
        assertEquals(listOf(FailedFlow(2, "10.77.0.99:443", FlowOwner(), "FORWARDING_DENIED", 2000)), flows.failed.value)

        flows.onEvent(FlowLog.parseEvent("""{"id":1,"event":"close","bytesIn":1300,"bytesOut":90,"durationMs":5,"ts":3000}""")!!, FlowOwner())
        assertTrue(flows.active.value.isEmpty())
        assertNull(FlowLog.parseEvent("nope"))
        assertTrue(FlowLog.parseSnapshot("nope").isEmpty())

        flows.endSession()
        assertEquals(1, flows.failed.value.size) // failures outlive the session
    }

    @Test fun clearAllAndRestore() {
        val d = Diagnostics()
        d.events.add(Level.INFO, Component.SSH, "a")
        d.dns.add(DnsEvent("wiki.corp.test.", "A", DnsEvent.TUNNEL, "NOERROR", listOf("10.77.0.20")))
        d.flows.onEvent(FlowEvent(1, FlowEvent.FAIL, dst = "10.77.0.99:443", reason = "DEST_TIMEOUT"), FlowOwner())
        val cleared = d.clearAll()
        assertTrue(d.events.events.value.isEmpty() && d.dns.events.value.isEmpty() && d.flows.failed.value.isEmpty())
        d.restore(cleared)
        assertEquals(1, d.events.events.value.size)
        assertEquals("wiki.corp.test", d.dns.hostFor("10.77.0.20"))
        assertEquals(1, d.flows.failed.value.size)
    }

    @Test fun watchingFlowsCountsHandles() {
        val d = Diagnostics()
        assertFalse(d.watchingFlows)
        val a = d.watchFlows()
        val b = d.watchFlows()
        a.close()
        a.close() // twice is harmless
        assertTrue(d.watchingFlows)
        b.close()
        assertFalse(d.watchingFlows)
    }

    @Test fun reportListsEverySection() {
        val d = Diagnostics(events = EventLog(clock = { 0 }))
        d.events.add(Level.WARN, Component.DNS, "intranet DNS server is not answering")
        d.dns.add(DnsEvent("git.corp.test.", "A", DnsEvent.TUNNEL, "NOERROR", listOf("203.0.113.40"), 22, 0, resolvedOutsideRoutes = true))
        d.dns.add(DnsEvent("printer.internal.", "A", DnsEvent.TUNNEL, "NXDOMAIN", latencyMs = 31))
        d.flows.onEvent(FlowEvent(7, FlowEvent.FAIL, dst = "10.77.0.99:443", reason = "FORWARDING_DENIED"), FlowOwner("Chrome", "wiki.corp.test"))
        val text = DiagnosticsReport.build(d, DiagnosticsReport.Titles("sshovel diagnostics", "Events", "DNS", "Active", "Failed"), ZoneOffset.UTC, now = 0)
        assertTrue(text, text.contains("1970-01-01 00:00:00.000 WARN DNS: intranet DNS server is not answering"))
        assertTrue(text, text.contains("tunnel A git.corp.test. → 203.0.113.40 (22 ms) [outside routed subnets]"))
        assertTrue(text, text.contains("printer.internal. → NXDOMAIN"))
        assertTrue(text, text.contains("10.77.0.99:443 FORWARDING_DENIED (Chrome · wiki.corp.test)"))
        assertEquals("sshovel-diagnostics-2026-09-23-0941.txt", DiagnosticsReport.fileName(1790156460000, ZoneOffset.UTC))
    }
}
