// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.home

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.data.Auth
import com.github.dennisklein.sshovel.data.Dns
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.data.Server
import com.github.dennisklein.sshovel.tunnel.Codes
import com.github.dennisklein.sshovel.tunnel.TunnelState
import com.github.dennisklein.sshovel.tunnel.TunnelStats
import com.github.dennisklein.sshovel.ui.format.errorText
import com.github.dennisklein.sshovel.ui.format.formatBytes
import com.github.dennisklein.sshovel.ui.format.formatUptime
import com.github.dennisklein.sshovel.ui.theme.LocalStateColors
import com.github.dennisklein.sshovel.ui.theme.MonoFamily
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme
import kotlinx.coroutines.delay

/**
 * M2 skeleton: state, profile, and the connect control. The full home screen from the design
 * handoff (status hero, profile list) replaces this in M6.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    ui: HomeUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRetryNow: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (ui.profile == null) {
                Text(stringResource(R.string.home_no_profile), style = MaterialTheme.typography.bodyLarge)
            } else {
                StatusCard(ui, onConnect, onDisconnect, onRetryNow)
            }
        }
    }
}

@Composable
private fun StatusCard(ui: HomeUiState, onConnect: () -> Unit, onDisconnect: () -> Unit, onRetryNow: () -> Unit) {
    val profile = ui.profile ?: return
    val stateColors = LocalStateColors.current
    val container = when (ui.state) {
        is TunnelState.On -> stateColors.stateOnContainer
        is TunnelState.Reconnecting -> stateColors.stateReconnectingContainer
        is TunnelState.NeedsAttention -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                stateLabel(ui.state),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Column {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${profile.server.user}@${profile.server.host}:${profile.server.port}",
                    fontFamily = MonoFamily,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            when (val s = ui.state) {
                TunnelState.Off -> Button(onConnect) { Text(stringResource(R.string.action_connect)) }
                is TunnelState.Connecting -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(connectingStep(s.step, profile), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onDisconnect) { Text(stringResource(R.string.action_cancel)) }
                }
                is TunnelState.On -> {
                    Stats(ui.stats)
                    FilledTonalButton(onDisconnect) { Text(stringResource(R.string.action_disconnect)) }
                }
                is TunnelState.Reconnecting -> {
                    RetryCountdown(s.nextRetryAtMillis)
                    if (s.code == Codes.NETWORK_LOST) Text(stringResource(R.string.err_network_body))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onRetryNow) { Text(stringResource(R.string.action_retry_now)) }
                        OutlinedButton(onDisconnect) { Text(stringResource(R.string.action_disconnect)) }
                    }
                }
                is TunnelState.NeedsAttention -> {
                    val (title, body) = errorText(LocalContext.current, s.code, profile)
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(body, style = MaterialTheme.typography.bodyMedium)
                    Button(onConnect) { Text(stringResource(R.string.action_retry)) }
                }
                TunnelState.Disconnecting -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Stats(stats: TunnelStats) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatRow(stringResource(R.string.stat_uptime), formatUptime(stats.uptimeSec))
        StatRow(stringResource(R.string.stat_connections), pluralStringResource(R.plurals.stat_connections_value, stats.activeFlows.toInt(), stats.activeFlows.toInt()))
        StatRow(stringResource(R.string.stat_data), "${formatBytes(stats.bytesIn)} / ${formatBytes(stats.bytesOut)}")
        StatRow(stringResource(R.string.stat_dns), "${stats.dnsTunneled} / ${stats.dnsDirect}")
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RetryCountdown(nextRetryAtMillis: Long?) {
    if (nextRetryAtMillis == null) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(nextRetryAtMillis) {
        while (now < nextRetryAtMillis) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val secs = ((nextRetryAtMillis - now + 999) / 1000).coerceAtLeast(0).toInt()
    Text(stringResource(R.string.reconnect_next, secs), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun stateLabel(state: TunnelState): String = stringResource(
    when (state) {
        TunnelState.Off -> R.string.tunnel_state_off
        is TunnelState.Connecting -> R.string.tunnel_state_connecting
        is TunnelState.On -> R.string.tunnel_state_on
        is TunnelState.Reconnecting -> R.string.tunnel_state_reconnecting
        is TunnelState.NeedsAttention -> R.string.tunnel_state_needs_attention
        TunnelState.Disconnecting -> R.string.tunnel_state_disconnecting
    },
)

@Composable
private fun connectingStep(step: String, profile: Profile): String = when (step) {
    "identity" -> stringResource(R.string.connecting_identity)
    "auth" -> stringResource(R.string.connecting_auth, profile.auth.alias)
    "tunnel" -> stringResource(R.string.connecting_tunnel)
    else -> stringResource(R.string.connecting_resolving, profile.server.host)
}

// ---- Previews: every state, light and dark -------------------------------------

private val previewProfile = Profile(
    id = "p", name = "Office", server = Server("jump.example.com", 22, "alice"),
    auth = Auth(Auth.KEYSTORE, "sshovel-key-1"), routes = listOf("10.0.0.0/8", "172.16.0.0/12"),
    dns = Dns(server = "10.1.0.53", suffixes = listOf("corp.example")),
)
private val previewStats = TunnelStats(uptimeSec = 3725, bytesIn = 12_400_000, bytesOut = 850_000, activeFlows = 12, dnsTunneled = 41, dnsDirect = 230)

@Composable
private fun PreviewState(state: TunnelState) = SshovelTheme(dynamicColor = false) {
    HomeScreen(HomeUiState(state, previewProfile, previewStats), {}, {}, {})
}

@Preview(name = "Off") @Preview(name = "Off dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewOff() = PreviewState(TunnelState.Off)

@Preview(name = "Connecting") @Preview(name = "Connecting dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewConnecting() = PreviewState(TunnelState.Connecting("auth"))

@Preview(name = "On") @Preview(name = "On dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewOn() = PreviewState(TunnelState.On())

@Preview(name = "Reconnecting") @Preview(name = "Reconnecting dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewReconnecting() =
    PreviewState(TunnelState.Reconnecting("networkChanged", 2, System.currentTimeMillis() + 4_000, null))

@Preview(name = "Auth failed") @Preview(name = "Auth failed dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewAuthFailed() = PreviewState(TunnelState.NeedsAttention(Codes.AUTH_FAILED))

@Preview(name = "Host key changed") @Preview(name = "Host key changed dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewMismatch() = PreviewState(TunnelState.NeedsAttention(Codes.HOST_KEY_MISMATCH))

@Preview(name = "No profile") @Preview(name = "No profile dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewNoProfile() = SshovelTheme(dynamicColor = false) { HomeScreen(HomeUiState(), {}, {}, {}) }
