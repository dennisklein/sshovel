// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.diagnostics

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

/**
 * The three in-memory buffers behind Diagnostics (DESIGN_BRIEF §5.9): events, DNS queries and
 * connections. One per process, so a session's events stay readable after it ends until the
 * user clears them. Nothing here is written to disk unless the user shares it (ARCHITECTURE §9).
 */
class Diagnostics(
    val events: EventLog = EventLog(),
    val dns: DnsLog = DnsLog(),
    val flows: FlowLog = FlowLog(),
) {
    private val watchers = AtomicInteger()

    /** True while a Connections tab is on screen: the service then polls Engine.FlowsJSON. */
    val watchingFlows: Boolean get() = watchers.get() > 0

    /** Starts polling open flows until the returned handle is closed. */
    fun watchFlows(): AutoCloseable {
        watchers.incrementAndGet()
        var closed = false
        return AutoCloseable {
            if (!closed) {
                closed = true
                watchers.decrementAndGet()
            }
        }
    }

    /** What [clearAll] removed, for "Undo". Open flows aren't history and aren't cleared. */
    data class Cleared(val events: List<LogEvent>, val dns: List<DnsEvent>, val failed: List<FailedFlow>)

    fun clearAll(): Cleared {
        val c = Cleared(events.events.value, dns.events.value, flows.failed.value)
        events.clear()
        dns.clear()
        flows.clear()
        return c
    }

    fun restore(c: Cleared) {
        events.restore(c.events)
        dns.restore(c.dns)
        flows.restore(c.failed)
    }
}

/**
 * The plain-text export behind "Copy all" and "Share as text file": every buffer, oldest first,
 * one line per entry. [titles] are the (translated) section headings: Events, DNS, Connections.
 */
object DiagnosticsReport {
    data class Titles(val header: String, val events: String, val dns: String, val active: String, val failed: String)

    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    fun build(d: Diagnostics, titles: Titles, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): String =
        buildString {
            fun ts(ms: Long) = time.format(Instant.ofEpochMilli(ms).atZone(zone))
            appendLine(titles.header)
            appendLine(ts(now))
            appendLine()
            appendLine("## ${titles.events}")
            d.events.events.value.forEach { appendLine("${ts(it.ts)} ${it.level} ${it.component.label}: ${it.message}") }
            appendLine()
            appendLine("## ${titles.dns}")
            d.dns.events.value.forEach { e ->
                val result = e.error ?: if (e.rcode != "NOERROR") e.rcode else e.answers.joinToString(" ")
                val flag = if (e.resolvedOutsideRoutes) " [outside routed subnets]" else ""
                appendLine("${ts(e.ts)} ${e.route} ${e.qtype} ${e.name} → $result (${e.latencyMs} ms)$flag")
            }
            appendLine()
            appendLine("## ${titles.failed}")
            d.flows.failed.value.forEach { appendLine("${ts(it.ts)} ${it.dst} ${it.reason}${owner(it.owner)}") }
            appendLine()
            appendLine("## ${titles.active}")
            d.flows.active.value.forEach { appendLine("${ts(it.startTs)} ${it.dst} in=${it.bytesIn} out=${it.bytesOut}${owner(it.owner)}") }
        }

    private fun owner(o: FlowOwner) = listOfNotNull(o.app, o.host).joinToString(" · ").let { if (it.isEmpty()) "" else " ($it)" }

    /** sshovel-diagnostics-2026-09-23-0941.txt (handoff G3). */
    fun fileName(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String =
        "sshovel-diagnostics-" + DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").format(Instant.ofEpochMilli(now).atZone(zone)) + ".txt"
}
