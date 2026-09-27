// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.consent

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.data.Auth
import com.github.dennisklein.sshovel.data.Dns
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.data.Server
import com.github.dennisklein.sshovel.ui.format.monoArg
import com.github.dennisklein.sshovel.ui.theme.MonoFamily
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme

/**
 * Pre-permission explainer (DESIGN_BRIEF §5.4, handoff V1): what Android is about to ask, what
 * goes through the tunnel, and that nothing else leaves the phone. "Continue" opens Android's
 * dialog; close or back cancels and the tunnel stays Off.
 */
@Composable
fun VpnExplainerScreen(profile: Profile?, onContinue: () -> Unit, onClose: () -> Unit) {
    ConsentScaffold(
        onClose = onClose,
        actions = { Button(onContinue) { Text(stringResource(R.string.action_continue)) } },
    ) {
        Headline(R.drawable.ic_vpn_lock, stringResource(R.string.vpn_explainer_title), stringResource(R.string.vpn_explainer_body))
        if (profile != null) {
            val suffixes = profile.dns.suffixes
            val tunnel = if (suffixes.isEmpty()) {
                // No intranet names: just the subnets.
                AnnotatedString(profile.routes.joinToString(", "), SpanStyle(fontFamily = MonoFamily))
            } else {
                monoArg(R.string.vpn_explainer_tunnel_body, profile.routes.joinToString(", "), suffixes.joinToString(", "))
            }
            Point(R.drawable.ic_lan, stringResource(R.string.vpn_explainer_tunnel), tunnel)
        }
        Point(R.drawable.ic_public, stringResource(R.string.vpn_explainer_else), AnnotatedString(stringResource(R.string.vpn_explainer_else_body)))
        if (profile != null) {
            Point(R.drawable.ic_shield_lock, stringResource(R.string.vpn_explainer_nothing), monoArg(R.string.vpn_explainer_nothing_body, profile.server.host))
        }
    }
}

/**
 * Permission denied (handoff V2, VPN_PERMISSION): why, and a hint for the case where Android
 * didn't even show its dialog because another app is the Always-on VPN.
 */
@Composable
fun VpnDeniedScreen(onTryAgain: () -> Unit, onNotNow: () -> Unit, onOpenVpnSettings: () -> Unit, onClose: () -> Unit) {
    ConsentScaffold(
        onClose = onClose,
        actions = {
            TextButton(onNotNow) { Text(stringResource(R.string.onb_not_now)) }
            Spacer(Modifier.width(8.dp))
            Button(onTryAgain) {
                Icon(painterResource(R.drawable.ic_refresh), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_try_again))
            }
        },
    ) {
        Headline(R.drawable.ic_vpn_key_off, stringResource(R.string.err_permission_title), stringResource(R.string.err_permission_body_denied))
        Point(R.drawable.ic_info, null, AnnotatedString(stringResource(R.string.vpn_denied_note)))
        TextButton(onOpenVpnSettings) {
            Icon(painterResource(R.drawable.ic_open_in_new), null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.open_vpn_settings))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsentScaffold(onClose: () -> Unit, actions: @Composable () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClose) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.cd_close)) }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) { actions() }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) { content() }
    }
}

@Composable
private fun Headline(icon: Int, title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(painterResource(icon), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Point(icon: Int, title: String?, body: AnnotatedString) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- Previews ------------------------------------------------------------------

private val previewProfile = Profile(
    id = "p", name = "Office", server = Server("jump.corp.example", 22, "alice"),
    auth = Auth(Auth.KEYSTORE, "k"), routes = listOf("10.20.0.0/16", "10.30.4.0/24"),
    dns = Dns(server = "10.20.0.53", suffixes = listOf("corp.example", "internal")),
)

@Preview(name = "Explainer") @Preview(name = "Explainer dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewExplainer() = SshovelTheme(dynamicColor = false) { VpnExplainerScreen(previewProfile, {}, {}) }

@Preview(name = "Denied") @Preview(name = "Denied dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewDenied() = SshovelTheme(dynamicColor = false) { VpnDeniedScreen({}, {}, {}, {}) }
