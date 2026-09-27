// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.consent

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import com.github.dennisklein.sshovel.ui.components.BottomActions
import com.github.dennisklein.sshovel.ui.components.ButtonIcon
import com.github.dennisklein.sshovel.ui.components.GroupedList
import com.github.dennisklein.sshovel.ui.components.Headline
import com.github.dennisklein.sshovel.ui.components.IconTile
import com.github.dennisklein.sshovel.ui.components.InfoNote
import com.github.dennisklein.sshovel.ui.components.MaxWidth
import com.github.dennisklein.sshovel.ui.components.Point
import com.github.dennisklein.sshovel.ui.components.SymbolIcon
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
        val cs = MaterialTheme.colorScheme
        IconTile(R.drawable.ic_vpn_lock, cs.secondaryContainer, cs.onSecondaryContainer)
        Headline(stringResource(R.string.vpn_explainer_title), stringResource(R.string.vpn_explainer_body))
        GroupedList {
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
}

/**
 * Permission denied (handoff V2, VPN_PERMISSION): why, and a hint for the case where Android
 * didn't even show its dialog because another app is the Always-on VPN.
 */
@Composable
fun VpnDeniedScreen(onTryAgain: () -> Unit, onNotNow: () -> Unit, onOpenVpnSettings: () -> Unit, onClose: () -> Unit) {
    ConsentScaffold(
        onClose = onClose,
        secondary = { TextButton(onNotNow) { Text(stringResource(R.string.onb_not_now)) } },
        actions = {
            Button(onTryAgain) {
                ButtonIcon(R.drawable.ic_refresh)
                Text(stringResource(R.string.action_try_again))
            }
        },
    ) {
        val cs = MaterialTheme.colorScheme
        IconTile(R.drawable.ic_vpn_key_off, cs.errorContainer, cs.onErrorContainer)
        Headline(stringResource(R.string.err_permission_title), stringResource(R.string.err_permission_body_denied))
        InfoNote(R.drawable.ic_info, stringResource(R.string.vpn_denied_note))
        TextButton(onOpenVpnSettings) {
            ButtonIcon(R.drawable.ic_open_in_new)
            Text(stringResource(R.string.open_vpn_settings))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsentScaffold(
    onClose: () -> Unit,
    actions: @Composable () -> Unit,
    secondary: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClose) { SymbolIcon(R.drawable.ic_close, stringResource(R.string.cd_close)) }
                },
            )
        },
        bottomBar = {
            Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) {
                MaxWidth { BottomActions(secondary, actions) }
            }
        },
    ) { padding ->
        MaxWidth(Modifier.padding(padding)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                content = content,
            )
        }
    }
}

// ---- Previews ------------------------------------------------------------------

private val previewProfile = Profile(
    id = "p", name = "Office", server = Server("jump.corp.example", 22, "alice"),
    auth = Auth(Auth.KEYSTORE, "k"), routes = listOf("10.20.0.0/16", "10.30.4.0/24"),
    dns = Dns(server = "10.20.0.53", suffixes = listOf("corp.example", "internal")),
)

@Preview(name = "V1 Explainer") @Preview(name = "V1 Explainer dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewExplainer() = SshovelTheme(dynamicColor = false) { VpnExplainerScreen(previewProfile, {}, {}) }

@Preview(name = "V2 Denied") @Preview(name = "V2 Denied dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewDenied() = SshovelTheme(dynamicColor = false) { VpnDeniedScreen({}, {}, {}, {}) }
