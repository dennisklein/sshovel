// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.diagnostics.ActiveFlow
import com.github.dennisklein.sshovel.diagnostics.DnsEvent
import com.github.dennisklein.sshovel.diagnostics.FailedFlow
import com.github.dennisklein.sshovel.diagnostics.Level
import com.github.dennisklein.sshovel.diagnostics.LogEvent
import com.github.dennisklein.sshovel.ui.format.failureCopy
import com.github.dennisklein.sshovel.ui.format.formatBytes
import com.github.dennisklein.sshovel.ui.theme.LocalStateColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val millis = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
private val seconds = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun DateTimeFormatter.at(ms: Long) = format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/**
 * LogLine (handoff §2, G1): severity icon, "time · component" (plus the severity word for
 * warnings and errors, so it isn't told by color alone), and the message, in mono.
 */
@Composable
fun LogLine(event: LogEvent, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val st = LocalStateColors.current
    val (bg, fg) = when (event.level) {
        Level.WARN -> st.stateReconnectingContainer to st.onStateReconnectingContainer
        Level.ERROR -> cs.errorContainer to cs.onErrorContainer
        else -> Color.Transparent to cs.onSurface
    }
    val icon = when (event.level) {
        Level.WARN -> R.drawable.ic_warning
        Level.ERROR -> R.drawable.ic_error_filled
        else -> R.drawable.ic_info
    }
    val severity = when (event.level) {
        Level.WARN -> stringResource(R.string.sev_warning)
        Level.ERROR -> stringResource(R.string.sev_error)
        Level.DEBUG -> stringResource(R.string.sev_debug)
        Level.INFO -> null
    }
    val meta = listOfNotNull(millis.at(event.ts), event.component.label, severity).joinToString(" · ")
    val style = MaterialTheme.typography.bodySmall.mono()
    Surface(color = bg, contentColor = fg, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val quiet = event.level == Level.INFO || event.level == Level.DEBUG
            SymbolIcon(icon, size = 18.dp, tint = if (quiet) cs.onSurfaceVariant else Color.Unspecified)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(meta, style = style, color = if (quiet) cs.onSurfaceVariant else Color.Unspecified)
                Text(event.message, style = style)
            }
        }
    }
}

/** RouteBadge (handoff §2): Tunnel (vpn_lock, primaryContainer) or Direct (public, outlined). */
@Composable
fun RouteBadge(route: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val tunnel = route == DnsEvent.TUNNEL
    val shape = RoundedCornerShape(6.dp)
    Surface(
        shape = shape,
        color = if (tunnel) cs.primaryContainer else Color.Transparent,
        contentColor = if (tunnel) cs.onPrimaryContainer else cs.onSurfaceVariant,
        modifier = if (tunnel) modifier else modifier.border(1.dp, cs.outlineVariant, shape),
    ) {
        Row(
            Modifier.heightIn(min = 24.dp).padding(start = 6.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SymbolIcon(if (tunnel) R.drawable.ic_vpn_lock else R.drawable.ic_public, size = 16.dp)
            Text(stringResource(if (tunnel) R.string.route_tunnel else R.string.route_direct), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** What a DNS row shows as its result: rcode for errors, else the first answer (+N more). */
data class DnsResult(val text: String, val error: Boolean)

@Composable
fun dnsResult(e: DnsEvent): DnsResult {
    val timedOut = e.error != null && (e.error.contains("timeout", true) || e.error.contains("deadline", true))
    return when {
        timedOut -> DnsResult(stringResource(R.string.dns_timeout), true)
        e.rcode != "NOERROR" || e.error != null -> DnsResult(e.rcode, true)
        e.answers.isEmpty() -> DnsResult(stringResource(R.string.dns_no_records), false)
        else -> {
            val first = e.answers.first().let { if (' ' in it) it else "${e.qtype} $it" }
            DnsResult(first + if (e.answers.size > 1) " +${e.answers.size - 1}" else "", false)
        }
    }
}

/**
 * DnsEntryRow (handoff §2, G2): name, latency and time, route badge and result. Answers outside
 * the routed subnets get the warning container and the suggestion to add them. Long-press copies
 * the name.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DnsEntryRow(e: DnsEvent, onCopyName: (String) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val st = LocalStateColors.current
    val warn = e.resolvedOutsideRoutes
    val name = e.name.removeSuffix(".")
    val result = dnsResult(e)
    Surface(
        color = if (warn) st.stateReconnectingContainer else Color.Transparent,
        contentColor = if (warn) st.onStateReconnectingContainer else cs.onSurface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .combinedClickable(onClick = {}, onLongClick = { onCopyName(name) })
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, style = MaterialTheme.typography.bodyMedium.mono().copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
                Text(
                    "${e.latencyMs} ms · ${seconds.at(e.ts)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (warn) LocalContentColor.current else cs.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                RouteBadge(e.route)
                if (result.error) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SymbolIcon(R.drawable.ic_error, size = 16.dp, tint = cs.error)
                        Text(result.text, style = MaterialTheme.typography.bodyMedium.mono().copy(fontWeight = FontWeight.Medium), color = cs.error)
                    }
                } else {
                    Text(result.text, style = MaterialTheme.typography.bodyMedium.mono())
                }
            }
            if (warn) {
                val ip = e.answers.firstOrNull { ' ' !in it }.orEmpty()
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SymbolIcon(R.drawable.ic_warning, size = 16.dp)
                    Text(stringResource(R.string.dns_outside_warning, "$ip/32"), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** "4 s", "6 min", "1 h 2 min" (handoff G3). */
@Composable
fun age(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> stringResource(R.string.age_s, s.toInt())
        s < 3600 -> stringResource(R.string.uptime_min, (s / 60).toInt())
        else -> stringResource(R.string.uptime_h_min, (s / 3600).toInt(), ((s % 3600) / 60).toInt())
    }
}

/** ConnectionRow, active (handoff §2, G3): destination, age, app and bytes so far. */
@Composable
fun ActiveConnectionRow(f: ActiveFlow, now: Long, modifier: Modifier = Modifier) {
    val app = f.owner.app ?: stringResource(R.string.conn_unknown_app)
    ConnectionRowLayout(
        icon = R.drawable.ic_swap_vert,
        dst = f.dst,
        time = age(now - f.startTs),
        lines = listOf(app + " · " + stringResource(R.string.conn_bytes, formatBytes(f.bytesIn), formatBytes(f.bytesOut))),
        modifier = modifier,
    )
}

/** Title and next step for a failed flow's reason (Go's flow codes, ARCHITECTURE §4). */
@Composable
fun failureText(reason: String): Pair<String, String?> =
    failureCopy(reason)?.let { (title, body) -> stringResource(title) to body?.let { stringResource(it) } } ?: (reason to null)

/** ConnectionRow, failed (handoff §2, G3): errorContainer, reason title and next step. */
@Composable
fun FailedConnectionRow(f: FailedFlow, now: Long, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val (title, body) = failureText(f.reason)
    val who = listOfNotNull(f.owner.app ?: stringResource(R.string.conn_unknown_app), f.owner.host).joinToString(" · ")
    Surface(color = cs.errorContainer, contentColor = cs.onErrorContainer, modifier = modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalContentColor provides cs.onErrorContainer) {
            ConnectionRowLayout(
                icon = R.drawable.ic_block,
                dst = f.dst,
                time = stringResource(R.string.ago, age(now - f.ts)),
                lines = listOfNotNull(who, title, body),
                titleIndex = 1,
            )
        }
    }
}

@Composable
private fun ConnectionRowLayout(icon: Int, dst: String, time: String, lines: List<String>, modifier: Modifier = Modifier, titleIndex: Int = -1) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SymbolIcon(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(dst, style = MaterialTheme.typography.bodyLarge.mono(), modifier = Modifier.weight(1f))
                Text(time, style = MaterialTheme.typography.bodySmall)
            }
            lines.forEachIndexed { i, line ->
                Text(
                    line,
                    style = if (i == titleIndex) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
