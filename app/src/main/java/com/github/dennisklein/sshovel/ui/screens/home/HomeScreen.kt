// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.home

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.data.Auth
import com.github.dennisklein.sshovel.data.Dns
import com.github.dennisklein.sshovel.data.HostKey
import com.github.dennisklein.sshovel.data.HostKeyInfo
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.data.Server
import com.github.dennisklein.sshovel.tunnel.AlwaysOn
import com.github.dennisklein.sshovel.tunnel.Codes
import com.github.dennisklein.sshovel.tunnel.TunnelState
import com.github.dennisklein.sshovel.tunnel.TunnelStats
import com.github.dennisklein.sshovel.ui.components.AlwaysOnRow
import com.github.dennisklein.sshovel.ui.components.ButtonIcon
import com.github.dennisklein.sshovel.ui.components.HeroActions
import com.github.dennisklein.sshovel.ui.components.HeroModel
import com.github.dennisklein.sshovel.ui.components.LogoIcon
import com.github.dennisklein.sshovel.ui.components.MaxWidth
import com.github.dennisklein.sshovel.ui.components.ProfileRow
import com.github.dennisklein.sshovel.ui.components.SectionHeader
import com.github.dennisklein.sshovel.ui.components.StatusHero
import com.github.dennisklein.sshovel.ui.components.SymbolIcon
import com.github.dennisklein.sshovel.ui.format.errorText
import com.github.dennisklein.sshovel.ui.screens.hostkey.HostKeyDialog
import com.github.dennisklein.sshovel.ui.theme.LocalStateColors
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme

/** What Home can ask for; the defaults keep previews short. */
data class HomeActions(
    val onConnect: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onRetryNow: () -> Unit = {},
    val onFix: (String) -> Unit = {},
    val onSelect: (Profile) -> Unit = {},
    val onOpenProfile: (Profile) -> Unit = {},
    val onAddProfile: () -> Unit = {},
    val onSetUp: () -> Unit = {},
    val onKeys: () -> Unit = {},
    val onSettings: () -> Unit = {},
    /** [tab]: 0 Events, 1 DNS, 2 Connections. */
    val onDiagnostics: (Int) -> Unit = {},
    val onConfirmSwitch: () -> Unit = {},
    val onCancelSwitch: () -> Unit = {},
    val onTrust: () -> Unit = {},
    val onCancelVerify: () -> Unit = {},
)

/**
 * Home (DESIGN_BRIEF §5.1, handoff H0–H7): the status hero, Always-on info, and the profile
 * list, with entries to Keys, Diagnostics and Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(ui: HomeUiState, actions: HomeActions, snackbar: SnackbarHostState = remember { SnackbarHostState() }) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SymbolIcon(LogoIcon, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.app_name), Modifier.padding(start = 12.dp))
                    }
                },
                actions = {
                    IconButton(actions.onKeys) { SymbolIcon(R.drawable.ic_key, stringResource(R.string.cd_keys)) }
                    if (ui.profiles.isNotEmpty()) {
                        IconButton({ actions.onDiagnostics(0) }) { SymbolIcon(R.drawable.ic_troubleshoot, stringResource(R.string.cd_diagnostics)) }
                    }
                    IconButton(actions.onSettings) { SymbolIcon(R.drawable.ic_settings, stringResource(R.string.cd_settings)) }
                },
            )
        },
        floatingActionButton = {
            // Hidden while not Off (handoff H2).
            if (ui.profiles.isNotEmpty() && ui.state == TunnelState.Off) {
                ExtendedFloatingActionButton(
                    onClick = actions.onAddProfile,
                    icon = { SymbolIcon(R.drawable.ic_add) },
                    text = { Text(stringResource(R.string.add_profile)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        MaxWidth(Modifier.padding(padding)) {
            when {
                !ui.loaded -> Box(Modifier.fillMaxSize())
                ui.profiles.isEmpty() || ui.profile == null -> EmptyState(actions.onSetUp)
                else -> ProfileList(ui, actions)
            }
        }
    }
    ui.verify?.let { HostKeyDialog(it, actions.onTrust, actions.onCancelVerify) }
    ui.switchTo?.let { target -> SwitchDialog(ui.profile, target, actions.onConfirmSwitch, actions.onCancelSwitch) }
}

@Composable
private fun ProfileList(ui: HomeUiState, actions: HomeActions) {
    val profile = ui.profile ?: return
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        item(key = "hero") {
            StatusHero(
                HeroModel(ui.state, profile, ui.stats, ui.keyName, ui.networkChange),
                HeroActions(actions.onConnect, actions.onDisconnect, actions.onRetryNow, actions.onFix),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
        val warnings = (ui.state as? TunnelState.On)?.warnings.orEmpty()
        items(warnings, key = { "warning-$it" }) { code ->
            WarningCard(code, profile) { actions.onDiagnostics(if (code == Codes.DNS_UNREACHABLE) 1 else 2) }
        }
        ui.alwaysOn?.let { info ->
            item(key = "always-on") { AlwaysOnRow(info.lockdown, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) }
        }
        item(key = "header") { SectionHeader(stringResource(R.string.profiles_header), Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)) }
        items(ui.profiles, key = { it.id }) { p ->
            ProfileRow(
                p,
                selected = p.id == profile.id,
                isDefault = p.id == ui.defaultId,
                // Switching mid-handshake would race the connect (handoff §2, ProfileRow).
                enabled = ui.state !is TunnelState.Connecting && ui.state != TunnelState.Disconnecting,
                onSelect = { actions.onSelect(p) },
                onOpen = { actions.onOpenProfile(p) },
            )
        }
    }
}

/** On-state warnings (FORWARDING_DENIED, DNS_UNREACHABLE): the tunnel keeps running (handoff §6). */
@Composable
private fun WarningCard(code: String, profile: Profile, onViewDiagnostics: () -> Unit) {
    val (title, body) = errorText(LocalContext.current, code, profile)
    val st = LocalStateColors.current
    Card(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = st.stateReconnectingContainer, contentColor = st.onStateReconnectingContainer),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SymbolIcon(R.drawable.ic_warning)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Announced when it appears: "Warning: {title}" (handoff §5).
                val announce = stringResource(R.string.a11y_warning, title)
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = announce
                    },
                )
                Text(body, style = MaterialTheme.typography.bodyMedium)
                TextButton(onViewDiagnostics, Modifier.offset(x = (-12).dp), colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)) {
                    Text(stringResource(R.string.err_forwarding_action))
                }
            }
        }
    }
}

/** H0: no profiles yet. "Set up sshovel" starts onboarding at step 1. */
@Composable
private fun EmptyState(onSetUp: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 40.dp, end = 40.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(Modifier.size(96.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            Box(contentAlignment = Alignment.Center) { SymbolIcon(LogoIcon, size = 48.dp) }
        }
        Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.home_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onSetUp, Modifier.padding(top = 8.dp)) {
            ButtonIcon(R.drawable.ic_arrow_forward)
            Text(stringResource(R.string.home_empty_action))
        }
    }
}

/** H7: switching profiles while one is running. */
@Composable
private fun SwitchDialog(current: Profile?, target: Profile, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { SymbolIcon(R.drawable.ic_swap_horiz, tint = MaterialTheme.colorScheme.secondary) },
        title = { Text(stringResource(R.string.switch_title, target.name)) },
        text = { Text(switchBody(current?.name.orEmpty(), target)) },
        confirmButton = { TextButton(onConfirm) { Text(stringResource(R.string.switch_confirm)) } },
        dismissButton = { TextButton(onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** switch_body with only the user@host argument in monospace (profile names aren't machine values). */
@Composable
private fun switchBody(current: String, target: Profile): androidx.compose.ui.text.AnnotatedString {
    val userHost = "${target.server.user}@${target.server.host}"
    val text = stringResource(R.string.switch_body, current, target.name, userHost)
    return androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        val i = text.lastIndexOf(userHost)
        if (i >= 0) addStyle(androidx.compose.ui.text.SpanStyle(fontFamily = com.github.dennisklein.sshovel.ui.theme.MonoFamily), i, i + userHost.length)
    }
}

// ---- Previews: every state, light and dark ---------------------------------------------

private val office = Profile(
    id = "office", name = "Office", server = Server("jump.corp.example", 22, "alex"),
    auth = Auth(Auth.KEYSTORE, "k1"), routes = listOf("10.20.0.0/16", "10.30.4.0/24", "172.16.8.0/22"),
    hostKey = HostKey("ecdsa-sha2-nistp256", "SHA256:nThbRk2mXJvF3e8pQz1LYw7cDh0KsVa4Tq9NoM6uGf5BwE+i2rY", "2026-03-12T10:00:00Z"),
    dns = Dns(server = "10.20.0.53", suffixes = listOf("corp.example")),
)
private val lab = Profile(
    id = "lab", name = "Lab", server = Server("lab.example.net", 22, "alex"),
    auth = Auth(Auth.KEYSTORE, "k1"), routes = listOf("10.40.0.0/16", "192.168.8.0/24"),
)
private val homeLab = Profile(
    id = "home", name = "Home lab", server = Server("home.example.org", 22, "pi"),
    auth = Auth(Auth.IMPORTED, "k2"), routes = listOf("192.168.1.0/24"),
)
private val previewStats = TunnelStats(uptimeSec = 5040, bytesIn = 50_540_000, bytesOut = 3_250_000, activeFlows = 12, dnsTunneled = 214, dnsDirect = 1902)

@Composable
private fun PreviewState(state: TunnelState, alwaysOn: AlwaysOn? = null, switchTo: Profile? = null) = SshovelTheme(dynamicColor = false) {
    HomeScreen(
        HomeUiState(
            loaded = true, state = state, profiles = listOf(office, lab, homeLab), defaultId = "office", profile = office,
            stats = previewStats, keyName = "Pixel StrongBox", alwaysOn = alwaysOn, switchTo = switchTo,
        ),
        HomeActions(),
    )
}

@Preview(name = "H0 Empty") @Preview(name = "H0 Empty dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewEmpty() = SshovelTheme(dynamicColor = false) { HomeScreen(HomeUiState(loaded = true), HomeActions()) }

@Preview(name = "H1 Off") @Preview(name = "H1 Off dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewOff() = PreviewState(TunnelState.Off)

@Preview(name = "H2 Connecting") @Preview(name = "H2 Connecting dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewConnecting() = PreviewState(TunnelState.Connecting("auth"))

@Preview(name = "H3 On") @Preview(name = "H3 On dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewOn() = PreviewState(TunnelState.On(), AlwaysOn(lockdown = true))

@Preview(name = "H3 On with warning") @Preview(name = "H3 On with warning dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewOnWarning() = PreviewState(TunnelState.On(listOf(Codes.DNS_UNREACHABLE)))

@Preview(name = "H4 Reconnecting") @Preview(name = "H4 Reconnecting dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewReconnecting() =
    PreviewState(TunnelState.Reconnecting("keepaliveTimeout", 2, System.currentTimeMillis() + 8_000, null))

@Preview(name = "H5 Auth failed") @Preview(name = "H5 Auth failed dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewAuthFailed() = PreviewState(TunnelState.NeedsAttention(Codes.AUTH_FAILED))

@Preview(name = "H6 Host key changed") @Preview(name = "H6 Host key changed dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewMismatch() = PreviewState(
    TunnelState.NeedsAttention(Codes.HOST_KEY_MISMATCH, receivedHostKey = HostKeyInfo("ssh-ed25519", "SHA256:p7VqZc0MhR3kWy8nUe2BtL6jXa9FdK1sGm4HrO5wNi7")),
)

@Preview(name = "H5b Host key unverified") @Preview(name = "H5b Host key unverified dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewUnverified() = PreviewState(TunnelState.NeedsAttention(Codes.HOST_KEY_UNVERIFIED))

@Preview(name = "H7 Switch") @Preview(name = "H7 Switch dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewSwitch() = PreviewState(TunnelState.On(), switchTo = lab)
