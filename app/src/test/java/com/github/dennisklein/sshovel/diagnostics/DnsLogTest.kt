// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsLogTest {
    @Test fun parsesCoreEvents() {
        val log = DnsLog()
        // As core/dnsproxy emits them (ARCHITECTURE §5).
        log.addJson("""{"name":"wiki.corp.test.","qtype":"A","route":"tunnel","rcode":"NOERROR","answers":["10.77.0.20"],"latencyMs":12,"ts":1790454371735}""")
        log.addJson("""{"name":"example.com.","qtype":"AAAA","route":"direct","rcode":"NOERROR","answers":[],"latencyMs":30,"ts":1790454371800,"resolvedOutsideRoutes":true,"error":"x","future":1}""")
        log.addJson("not json")
        val (a, b) = log.events.value
        assertEquals(DnsEvent("wiki.corp.test.", "A", DnsEvent.TUNNEL, "NOERROR", listOf("10.77.0.20"), 12, 1790454371735), a)
        assertEquals(DnsEvent.DIRECT, b.route)
        assertTrue(b.resolvedOutsideRoutes)
        assertEquals(2, log.events.value.size)
    }

    @Test fun keepsTheLast500() {
        val log = DnsLog()
        repeat(510) { log.add(DnsEvent("n$it", "A", DnsEvent.DIRECT, "NOERROR")) }
        assertEquals(500, log.events.value.size)
        assertEquals("n10", log.events.value.first().name)
        assertEquals("n509", log.events.value.last().name)
        log.clear()
        assertTrue(log.events.value.isEmpty())
    }
}
