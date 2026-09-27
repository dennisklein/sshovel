// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.profile

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.tunnel.Codes
import com.github.dennisklein.sshovel.tunnel.DiscoveredRoute
import com.github.dennisklein.sshovel.ui.components.ButtonIcon
import com.github.dennisklein.sshovel.ui.components.DiscoveredRouteRow
import com.github.dennisklein.sshovel.ui.components.SymbolIcon
import com.github.dennisklein.sshovel.ui.components.mono
import com.github.dennisklein.sshovel.ui.format.errorText
import com.github.dennisklein.sshovel.ui.format.monoWords
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme

/**
 * Discover subnets (DESIGN_BRIEF §5.8, handoff D1–D3): the server's routes as a checklist.
 * Default and link-local routes start unchecked and say why; already added ones are checked and
 * disabled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverSheet(
    ui: DiscoverUi,
    host: String,
    port: String,
    existing: List<String>,
    onToggle: (String) -> Unit,
    onAdd: () -> Unit,
    onRetry: () -> Unit,
    onAddByHand: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        DiscoverContent(ui, host, port, existing, onToggle, onAdd, onRetry, onAddByHand, onDismiss)
    }
}

@Composable
private fun DiscoverContent(
    ui: DiscoverUi,
    host: String,
    port: String,
    existing: List<String>,
    onToggle: (String) -> Unit,
    onAdd: () -> Unit,
    onRetry: () -> Unit,
    onAddByHand: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.discover_title), style = MaterialTheme.typography.headlineSmall)
            if (ui is DiscoverUi.Results) {
                Text(
                    monoWords(pluralStringResource(R.plurals.discover_count, ui.routes.size, ui.routes.size, host), host),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (ui) {
            DiscoverUi.Loading -> {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(40.dp))
                    Text(stringResource(R.string.discover_loading), style = MaterialTheme.typography.bodyLarge)
                    Text("ip -4 route show · $host", style = MaterialTheme.typography.bodyMedium.mono(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Actions(null) { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } }
            }
            is DiscoverUi.Results -> {
                ui.routes.forEach { r ->
                    val added = r.cidr in existing
                    DiscoveredRouteRow(
                        r.cidr,
                        detail = when {
                            r.isDefault -> stringResource(R.string.discover_default_route)
                            r.isLinkLocal -> stringResource(R.string.discover_link_local)
                            added -> listOf(r.dev, stringResource(R.string.discover_already)).filter { it.isNotEmpty() }.joinToString(" · ")
                            else -> r.dev
                        },
                        detailIcon = when {
                            r.isDefault -> R.drawable.ic_warning
                            r.isLinkLocal -> R.drawable.ic_info
                            else -> null
                        },
                        checked = added || r.cidr in ui.selected,
                        enabled = !added,
                        onToggle = { onToggle(r.cidr) },
                    )
                }
                val n = ui.selected.count { it !in existing }
                Actions({ TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } }) {
                    Button(onAdd, enabled = n > 0) { Text(stringResource(R.string.discover_add_selected, n)) }
                }
            }
            is DiscoverUi.Failed -> {
                val context = LocalContext.current
                val (icon, title, body) = when (ui.code) {
                    Codes.ROUTE_DISCOVERY_UNAVAILABLE -> Triple(
                        R.drawable.ic_terminal,
                        stringResource(R.string.discover_err_cmd_title),
                        monoWords(stringResource(R.string.discover_err_cmd_body), "ip route", "restrict"),
                    )
                    Codes.HOST_UNREACHABLE -> Triple(
                        R.drawable.ic_timer_off,
                        stringResource(R.string.discover_err_timeout_title),
                        monoWords(stringResource(R.string.discover_err_timeout_body, "$host:$port", ui.timeoutSec), "$host:$port"),
                    )
                    else -> errorText(context, ui.code, null).let { (t, b) -> Triple(R.drawable.ic_error, t, AnnotatedString(b)) }
                }
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SymbolIcon(icon)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Text(body, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Actions({ TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } }) {
                    if (ui.code == Codes.ROUTE_DISCOVERY_UNAVAILABLE) {
                        Button(onAddByHand) { Text(stringResource(R.string.discover_add_by_hand)) }
                    } else {
                        Button(onRetry) {
                            ButtonIcon(R.drawable.ic_refresh)
                            Text(stringResource(R.string.action_try_again))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Actions(secondary: (@Composable () -> Unit)?, primary: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        secondary?.invoke()
        primary()
    }
}

// Previews render the sheet's content on a surface (ModalBottomSheet needs a window).
@Composable
private fun PreviewSheet(ui: DiscoverUi) = SshovelTheme(dynamicColor = false) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        DiscoverContent(ui, "jump.corp.example", "22", listOf("10.20.0.0/16"), {}, {}, {}, {}, {})
    }
}

@Preview(name = "D1 Loading") @Preview(name = "D1 Loading dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewLoading() = PreviewSheet(DiscoverUi.Loading)

@Preview(name = "D2 Results") @Preview(name = "D2 Results dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewResults() = PreviewSheet(
    DiscoverUi.Results(
        listOf(
            DiscoveredRoute("10.20.0.0/16", "eth1"), DiscoveredRoute("10.40.0.0/16", "eth2"), DiscoveredRoute("172.16.8.0/22", "wg0"),
            DiscoveredRoute("192.168.122.0/24", "virbr0"), DiscoveredRoute("0.0.0.0/0", "eth0", isDefault = true),
            DiscoveredRoute("169.254.0.0/16", "eth0", isLinkLocal = true),
        ),
        setOf("10.40.0.0/16", "172.16.8.0/22"),
    ),
)

@Preview(name = "D3 Commands not allowed") @Preview(name = "D3 Commands not allowed dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewNoCommands() = PreviewSheet(DiscoverUi.Failed(Codes.ROUTE_DISCOVERY_UNAVAILABLE, 25))

@Preview(name = "D3 Timeout") @Preview(name = "D3 Timeout dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewTimeout() = PreviewSheet(DiscoverUi.Failed(Codes.HOST_UNREACHABLE, 25))
