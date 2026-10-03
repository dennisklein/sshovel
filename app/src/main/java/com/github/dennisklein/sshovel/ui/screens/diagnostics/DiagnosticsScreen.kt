// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.diagnostics

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.diagnostics.ActiveFlow
import com.github.dennisklein.sshovel.diagnostics.Component
import com.github.dennisklein.sshovel.diagnostics.DnsEvent
import com.github.dennisklein.sshovel.diagnostics.FailedFlow
import com.github.dennisklein.sshovel.diagnostics.FlowOwner
import com.github.dennisklein.sshovel.diagnostics.Level
import com.github.dennisklein.sshovel.diagnostics.LogEvent
import com.github.dennisklein.sshovel.ui.components.ActiveConnectionRow
import com.github.dennisklein.sshovel.ui.components.DnsEntryRow
import com.github.dennisklein.sshovel.ui.components.FailedConnectionRow
import com.github.dennisklein.sshovel.ui.components.InfoNote
import com.github.dennisklein.sshovel.ui.components.LogLine
import com.github.dennisklein.sshovel.ui.components.SectionHeader
import com.github.dennisklein.sshovel.ui.components.SymbolIcon
import com.github.dennisklein.sshovel.ui.format.formatCount
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme
import kotlinx.coroutines.launch

class DiagnosticsActions(
    val onBack: () -> Unit = {},
    val onTab: (DiagTab) -> Unit = {},
    val onLevel: (Level) -> Unit = {},
    val onComponent: (Component) -> Unit = {},
    val onPause: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onCopyAll: () -> Unit = {},
    val onClear: () -> Unit = {},
    val onCopyName: (String) -> Unit = {},
    val onConnect: () -> Unit = {},
)

/** Diagnostics (DESIGN_BRIEF §5.9, handoff G1–G4): Events, DNS and Connections tabs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    ui: DiagnosticsUi,
    actions: DiagnosticsActions,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    pager: PagerState = rememberPagerState { DiagTab.entries.size },
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { actions.onTab(DiagTab.entries[it]) } }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics)) },
                navigationIcon = { IconButton(actions.onBack) { SymbolIcon(R.drawable.ic_arrow_back, stringResource(R.string.cd_back)) } },
                actions = { if (ui.hasData) BarActions(ui.paused, actions) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val tabs: @Composable () -> Unit = {
                DiagTab.entries.forEach { tab ->
                    val count = ui.badges[tab] ?: 0
                    Tab(
                        selected = pager.currentPage == tab.ordinal,
                        onClick = { scope.launch { pager.animateScrollToPage(tab.ordinal) } },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = {
                            // The badge follows the title, 6 dp apart, instead of covering it (handoff G1).
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(stringResource(TAB_TITLES[tab.ordinal]), maxLines = 1)
                                if (count > 0 && pager.currentPage != tab.ordinal) Badge { Text(count.toString()) }
                            }
                        },
                    )
                }
            }
            // Three equal tabs, or at large font sizes tabs as wide as their titles, scrolling
            // sideways, so "Connections" is never cut off (handoff §5, 200 %).
            if (LocalDensity.current.fontScale < 1.3f) {
                PrimaryTabRow(selectedTabIndex = pager.currentPage, tabs = tabs)
            } else {
                PrimaryScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 0.dp, tabs = tabs)
            }
            if (ui.paused) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.paused_new, ui.newWhilePaused),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 0) { page ->
                when {
                    ui.tunnelOff && !ui.hasData -> EmptyState(R.drawable.ic_vpn_key_off, R.string.diag_off_title, R.string.diag_off_body, actions.onConnect)
                    page == DiagTab.EVENTS.ordinal -> EventsPage(ui, actions)
                    page == DiagTab.DNS.ordinal -> DnsPage(ui, actions)
                    else -> ConnectionsPage(ui)
                }
            }
        }
    }
}

private val TAB_TITLES = listOf(R.string.tab_events, R.string.tab_dns, R.string.tab_connections)

@Composable
private fun BarActions(paused: Boolean, actions: DiagnosticsActions) {
    IconButton(actions.onPause) {
        if (paused) SymbolIcon(R.drawable.ic_play_arrow, stringResource(R.string.cd_resume))
        else SymbolIcon(R.drawable.ic_pause, stringResource(R.string.cd_pause))
    }
    IconButton(actions.onShare) { SymbolIcon(R.drawable.ic_share, stringResource(R.string.cd_share_file)) }
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton({ menu = true }) { SymbolIcon(R.drawable.ic_more_vert, stringResource(R.string.cd_more)) }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.copy_all)) },
                leadingIcon = { SymbolIcon(R.drawable.ic_content_copy) },
                onClick = { menu = false; actions.onCopyAll() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_text_file)) },
                leadingIcon = { SymbolIcon(R.drawable.ic_description) },
                onClick = { menu = false; actions.onShare() },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.clear)) },
                leadingIcon = { SymbolIcon(R.drawable.ic_delete_sweep) },
                onClick = { menu = false; actions.onClear() },
            )
        }
    }
}

private val LEVELS = listOf(
    Level.DEBUG to R.string.level_all,
    Level.INFO to R.string.level_info,
    Level.WARN to R.string.level_warn,
    Level.ERROR to R.string.level_error,
)

/** G1: level menu and component filters, then the log, newest at the bottom. */
@Composable
private fun EventsPage(ui: DiagnosticsUi, actions: DiagnosticsActions) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            var menu by remember { mutableStateOf(false) }
            val levelLabel = stringResource(LEVELS.first { it.first == ui.minLevel }.second)
            Box {
                FilterChip(
                    selected = false,
                    onClick = { menu = true },
                    label = { Text(levelLabel) },
                    trailingIcon = { SymbolIcon(R.drawable.ic_arrow_drop_down, size = FilterChipDefaults.IconSize) },
                )
                DropdownMenu(menu, { menu = false }) {
                    LEVELS.forEach { (level, label) ->
                        DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { menu = false; actions.onLevel(level) })
                    }
                }
            }
            Component.entries.forEach { c ->
                val on = c in ui.components
                FilterChip(
                    selected = on,
                    onClick = { actions.onComponent(c) },
                    label = { Text(c.label) },
                    leadingIcon = if (on) ({ SymbolIcon(R.drawable.ic_check, size = FilterChipDefaults.IconSize) }) else null,
                )
            }
        }
        if (!ui.anyEvents) {
            EmptyState(R.drawable.ic_receipt_long, R.string.diag_none_title, R.string.diag_none_body)
            return@Column
        }
        val list = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val atBottom by remember { derivedStateOf { !list.canScrollForward } }
        // Follow new events while the user is at the bottom (handoff G1).
        var follow by remember { mutableStateOf(true) }
        LaunchedEffect(atBottom, list.isScrollInProgress) { if (list.isScrollInProgress) follow = atBottom }
        LaunchedEffect(ui.events.size) { if (follow && ui.events.isNotEmpty()) list.scrollToItem(ui.events.lastIndex) }
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                items(ui.events) { LogLine(it) }
            }
            if (!atBottom && ui.events.isNotEmpty()) {
                SmallFloatingActionButton(
                    onClick = { follow = true; scope.launch { list.animateScrollToItem(ui.events.lastIndex) } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                ) { SymbolIcon(R.drawable.ic_arrow_downward, stringResource(R.string.jump_to_latest)) }
            }
        }
    }
}

/** G2: counts, then queries newest first. */
@Composable
private fun DnsPage(ui: DiagnosticsUi, actions: DiagnosticsActions) {
    // Strict Private DNS bypasses split DNS: say so first (ARCHITECTURE §5, "Known interference").
    val privateDns: @Composable () -> Unit = {
        ui.privateDnsHost?.let { host ->
            InfoNote(R.drawable.ic_info, stringResource(R.string.dns_private_strict, host), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
        }
    }
    if (ui.dns.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            privateDns()
            EmptyState(R.drawable.ic_dns, R.string.diag_no_dns)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { privateDns() }
        item {
            Text(
                stringResource(
                    R.string.dns_summary,
                    formatCount(ui.dnsCounts.tunneled.toLong()),
                    formatCount(ui.dnsCounts.direct.toLong()),
                    formatCount(ui.dnsCounts.failed.toLong()),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        items(ui.dns) { e ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DnsEntryRow(e, actions.onCopyName)
        }
    }
}

/** G3: failures first (they're why you came), then what's open now. */
@Composable
private fun ConnectionsPage(ui: DiagnosticsUi) {
    if (ui.active.isEmpty() && ui.failed.isEmpty()) return EmptyState(R.drawable.ic_swap_vert, R.string.diag_no_conn)
    LazyColumn(Modifier.fillMaxSize()) {
        if (ui.failed.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.conn_failed_header, ui.failed.size), Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
            items(ui.failed, key = { "f${it.id}" }) { FailedConnectionRow(it, ui.now) }
        }
        item { SectionHeader(stringResource(R.string.conn_active_header, ui.active.size), Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
        items(ui.active, key = { "a${it.id}" }) { ActiveConnectionRow(it, ui.now) }
    }
}

/** G4: tunnel off with nothing kept, or nothing yet. */
@Composable
private fun EmptyState(icon: Int, title: Int, body: Int? = null, onConnect: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        SymbolIcon(icon, size = 48.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        body?.let {
            Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        onConnect?.let {
            Button(it, Modifier.padding(top = 8.dp)) {
                SymbolIcon(R.drawable.ic_power_settings_new, size = 18.dp)
                Text(stringResource(R.string.action_connect), Modifier.padding(start = 8.dp))
            }
        }
    }
}

// --- Previews (storyboard 6: wiki.corp.example fails in the browser) ------------------------------

private const val T0 = 1790156292010L // 09:38:12.010 UTC

private val sampleEvents = listOf(
    LogEvent(T0, Level.INFO, Component.SSH, "connection lost: read timeout after 30 s"),
    LogEvent(T0 + 2300, Level.INFO, Component.SSH, "connecting to alex@jump.corp.example:22"),
    LogEvent(T0 + 2392, Level.INFO, Component.SSH, "authenticated; server SSH-2.0-OpenSSH_9.6"),
    LogEvent(T0 + 2405, Level.INFO, Component.TUNNEL, "TUN up 198.18.0.1/24 mtu 1500, 3 routes"),
    LogEvent(T0 + 2410, Level.INFO, Component.DNS, "corp.example, internal → 10.20.0.53"),
    LogEvent(T0 + 169867, Level.WARN, Component.DNS, "git.corp.example → 203.0.113.40 is outside routed subnets"),
    LogEvent(T0 + 170291, Level.ERROR, Component.TUNNEL, "server refused to forward to 10.20.7.15:443"),
)

private val sampleDns = listOf(
    DnsEvent("wiki.corp.example.", "A", DnsEvent.TUNNEL, "NOERROR", listOf("10.20.7.15"), 14, T0 + 170100),
    DnsEvent("git.corp.example.", "A", DnsEvent.TUNNEL, "NOERROR", listOf("203.0.113.40"), 22, T0 + 169860, resolvedOutsideRoutes = true),
    DnsEvent("printer.internal.", "A", DnsEvent.TUNNEL, "NXDOMAIN", emptyList(), 31, T0 + 152000),
    DnsEvent("www.youtube.com.", "A", DnsEvent.DIRECT, "NOERROR", listOf("142.250.185.78", "142.250.185.79", "142.250.185.80", "142.250.185.81"), 9, T0 + 148000),
    DnsEvent("15.7.20.10.in-addr.arpa.", "PTR", DnsEvent.TUNNEL, "NOERROR", listOf("PTR wiki.corp.example."), 11, T0 + 146000),
)

private val sampleUi = DiagnosticsUi(
    tunnelOff = false,
    hasData = true,
    events = sampleEvents,
    anyEvents = true,
    dns = sampleDns,
    dnsCounts = DnsCounts(214, 1902, 3),
    failed = listOf(FailedFlow(9, "10.20.7.15:443", FlowOwner("Chrome", "wiki.corp.example"), "FORWARDING_DENIED", T0 + 170291)),
    active = listOf(
        ActiveFlow(8, "172.16.8.12:8443", FlowOwner(), T0 + 134000, 12_288, 4_096),
        ActiveFlow(5, "10.20.1.40:443", FlowOwner("Slack"), T0 - 186000, 3_565_158, 215_040),
        ActiveFlow(2, "10.20.3.8:22", FlowOwner("Termux"), T0 - 906000, 1_258_291, 90_112),
    ),
    badges = mapOf(DiagTab.DNS to 1, DiagTab.CONNECTIONS to 1),
    now = T0 + 174291,
)

@Composable
private fun PreviewTab(ui: DiagnosticsUi, tab: DiagTab) = SshovelTheme(dynamicColor = false) {
    DiagnosticsScreen(ui, DiagnosticsActions(), pager = rememberPagerState(tab.ordinal) { DiagTab.entries.size })
}

@Preview(name = "G1 Events") @Preview(name = "G1 Events dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewEvents() = PreviewTab(sampleUi, DiagTab.EVENTS)

@Preview(name = "G1 Events paused") @Preview(name = "G1 Events paused dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewPaused() = PreviewTab(sampleUi.copy(paused = true, newWhilePaused = 14), DiagTab.EVENTS)

@Preview(name = "G2 DNS") @Preview(name = "G2 DNS dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewDns() = PreviewTab(sampleUi, DiagTab.DNS)

@Preview(name = "G3 Connections") @Preview(name = "G3 Connections dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewConnections() = PreviewTab(sampleUi, DiagTab.CONNECTIONS)

@Preview(name = "G4 Tunnel off") @Preview(name = "G4 Tunnel off dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewOff() = PreviewTab(DiagnosticsUi(), DiagTab.EVENTS)

@Preview(name = "G4 No events yet") @Preview(name = "G4 No events yet dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewNoEvents() = PreviewTab(DiagnosticsUi(tunnelOff = false), DiagTab.EVENTS)

@Preview(name = "G2 DNS with strict Private DNS") @Preview(name = "G2 DNS with strict Private DNS dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewPrivateDns() = PreviewTab(sampleUi.copy(privateDnsHost = "dns.example.net"), DiagTab.DNS)

@Preview(name = "G4 No DNS queries yet") @Preview(name = "G4 No DNS queries yet dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewNoDns() = PreviewTab(DiagnosticsUi(tunnelOff = false), DiagTab.DNS)
