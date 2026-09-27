// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.components

import android.content.Context
import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.data.Auth
import com.github.dennisklein.sshovel.data.Dns
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.data.Server
import com.github.dennisklein.sshovel.tunnel.Codes
import com.github.dennisklein.sshovel.tunnel.NetworkChange
import com.github.dennisklein.sshovel.tunnel.NetworkMonitor
import com.github.dennisklein.sshovel.tunnel.TunnelState
import com.github.dennisklein.sshovel.tunnel.TunnelStats
import com.github.dennisklein.sshovel.ui.format.errorText
import com.github.dennisklein.sshovel.ui.format.formatBytes
import com.github.dennisklein.sshovel.ui.format.formatCount
import com.github.dennisklein.sshovel.ui.format.formatUptimeShort
import com.github.dennisklein.sshovel.ui.format.monoArg
import com.github.dennisklein.sshovel.ui.format.monoWords
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme
import kotlinx.coroutines.delay

/** What the hero shows (DESIGN_BRIEF §5.1). */
data class HeroModel(
    val state: TunnelState,
    val profile: Profile,
    val stats: TunnelStats = TunnelStats(),
    /** Name of the profile's key, for "Authenticating with key …" and error copy. */
    val keyName: String? = null,
    val networkChange: NetworkChange? = null,
)

/** Hero buttons. [onFix] is the primary action of a Needs attention error (DESIGN_BRIEF §8). */
data class HeroActions(
    val onConnect: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onRetryNow: () -> Unit = {},
    val onFix: (code: String) -> Unit = {},
)

/** The fix action of a stopping error (DESIGN_BRIEF §8), as label and icon. */
data class ErrorAction(val label: Int, val icon: Int)

fun errorAction(code: String): ErrorAction? = when (code) {
    Codes.AUTH_FAILED -> ErrorAction(R.string.err_auth_action, R.drawable.ic_key)
    Codes.HOST_UNREACHABLE -> ErrorAction(R.string.err_unreachable_action, R.drawable.ic_refresh)
    Codes.HOST_KEY_UNVERIFIED -> ErrorAction(R.string.err_unverified_action, R.drawable.ic_verified_user)
    Codes.HOST_KEY_MISMATCH -> ErrorAction(R.string.review_server_identity, R.drawable.ic_policy)
    Codes.VPN_REVOKED -> ErrorAction(R.string.err_revoked_action, R.drawable.ic_refresh)
    Codes.VPN_PERMISSION -> ErrorAction(R.string.err_permission_action, R.drawable.ic_vpn_lock)
    Codes.KEY_UNAVAILABLE -> ErrorAction(R.string.err_key_unavailable_action, R.drawable.ic_key)
    else -> null
}

/** Motion tokens (handoff §4): emphasized easing curves. */
private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/**
 * StatusHero (handoff §2, H1–H6): the state indicator, profile, and the one control that matters
 * in this state. Needs attention switches the card to errorContainer.
 */
@Composable
fun StatusHero(model: HeroModel, actions: HeroActions, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val attention = model.state is TunnelState.NeedsAttention
    val container by animateColorAsState(
        if (attention) cs.errorContainer else cs.surfaceContainer,
        tween(300, easing = Emphasized),
        label = "hero container",
    )
    val content = if (attention) cs.onErrorContainer else cs.onSurface
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        AnimatedContent(
            model,
            contentKey = { it.state::class },
            transitionSpec = {
                (fadeIn(tween(200, 100, EmphasizedDecelerate)) + slideInVertically(tween(200, 100, EmphasizedDecelerate)) { it / 20 })
                    .togetherWith(fadeOut(tween(100, easing = EmphasizedAccelerate)))
            },
            label = "hero state",
        ) { m ->
            Column(
                Modifier
                    .padding(20.dp)
                    .animateContentSize(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HeroBody(m, actions)
            }
        }
    }
}

@Composable
private fun HeroBody(m: HeroModel, actions: HeroActions) {
    val context = LocalContext.current
    val state = m.state
    val profile = m.profile
    val attention = state is TunnelState.NeedsAttention
    val error = (state as? TunnelState.NeedsAttention)?.let { errorText(context, it.code, profile, m.keyName) }
    StateIndicator(
        state.indicatorKind(),
        stateLabel(context, state, m.stats),
        modifier = Modifier.semantics {
            liveRegion = if (attention) LiveRegionMode.Assertive else LiveRegionMode.Polite
            contentDescription = announcement(context, m, error?.first)
        },
        onErrorSurface = attention,
        hostKeyError = state.isHostKeyError(),
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(profile.name, style = MaterialTheme.typography.headlineMedium)
        Text(
            "${profile.server.user}@${profile.server.host}:${profile.server.port}",
            style = MaterialTheme.typography.bodyMedium.mono(),
            color = if (attention) LocalContentColor.current else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    when (state) {
        TunnelState.Off -> Button(actions.onConnect, Modifier.fillMaxWidth()) {
            ButtonIcon(R.drawable.ic_power_settings_new)
            Text(stringResource(R.string.action_connect))
        }
        is TunnelState.Connecting -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    connectingStep(context, state.step, profile, m.keyName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(actions.onDisconnect, Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
        }
        is TunnelState.On -> {
            StatsGrid(m.stats)
            FilledTonalButton(actions.onDisconnect, Modifier.fillMaxWidth()) {
                ButtonIcon(R.drawable.ic_power_settings_new)
                Text(stringResource(R.string.action_disconnect))
            }
        }
        is TunnelState.Reconnecting -> {
            val offline = state.code == Codes.NETWORK_LOST
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (state.nextRetryAtMillis != null && !offline) {
                    RetryCountdown(state.nextRetryAtMillis)
                } else if (!offline) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 10.dp))
                }
                Text(
                    reconnectDetail(context, state, m.networkChange),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(actions.onRetryNow, Modifier.weight(1f), enabled = !offline) {
                    ButtonIcon(R.drawable.ic_refresh)
                    Text(stringResource(R.string.action_retry_now))
                }
                OutlinedButton(actions.onDisconnect, Modifier.weight(1f)) { Text(stringResource(R.string.action_disconnect)) }
            }
        }
        is TunnelState.NeedsAttention -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(error!!.first, style = MaterialTheme.typography.titleMedium)
                Text(errorBody(state.code, profile, m.keyName, error.second), style = MaterialTheme.typography.bodyMedium)
            }
            val fix = errorAction(state.code)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val cs = MaterialTheme.colorScheme
                if (fix != null) {
                    Button(
                        { actions.onFix(state.code) },
                        colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError),
                    ) {
                        ButtonIcon(fix.icon)
                        Text(stringResource(fix.label))
                    }
                }
                // Retrying can't fix a changed host key (handoff H6); "Retry" as the fix needs no twin.
                if (state.code != Codes.HOST_KEY_MISMATCH && state.code != Codes.HOST_UNREACHABLE) {
                    TextButton(actions.onConnect, colors = ButtonDefaults.textButtonColors(contentColor = cs.onErrorContainer)) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
        }
        TunnelState.Disconnecting -> LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
fun ButtonIcon(id: Int) {
    SymbolIcon(id, size = ButtonDefaults.IconSize)
    androidx.compose.foundation.layout.Spacer(Modifier.width(ButtonDefaults.IconSpacing))
}

/** Error body with machine values in monospace (DESIGN_BRIEF §8). */
@Composable
private fun errorBody(code: String, profile: Profile, keyName: String?, plain: String): AnnotatedString = when (code) {
    Codes.AUTH_FAILED -> "${profile.server.user}@${profile.server.host}".let { userHost ->
        monoWords(stringResource(R.string.err_auth_body, userHost, keyName ?: profile.auth.alias), userHost, "~/.ssh/authorized_keys")
    }
    Codes.HOST_UNREACHABLE -> monoArg(R.string.err_unreachable_body, "${profile.server.host}:${profile.server.port}")
    Codes.HOST_KEY_MISMATCH -> monoArg(R.string.err_mismatch_body, profile.server.host)
    Codes.DNS_UNREACHABLE -> monoArg(R.string.err_dns_body, profile.dns.server.orEmpty())
    else -> AnnotatedString(plain)
}

/** The four live numbers of an On tunnel; one column when narrow (handoff §5, 200 % font). */
@Composable
private fun StatsGrid(stats: TunnelStats) {
    val context = LocalContext.current
    val cells = listOf(
        stringResource(R.string.stat_connections) to androidx.compose.ui.res.pluralStringResource(
            R.plurals.stat_connections_value, stats.activeFlows.toInt(), stats.activeFlows.toInt(),
        ),
        stringResource(R.string.stat_uptime) to formatUptimeShort(context, stats.uptimeSec),
        stringResource(R.string.stat_data) to null,
        stringResource(R.string.stat_dns) to "${formatCount(stats.dnsTunneled)} / ${formatCount(stats.dnsDirect)}",
    )
    BoxWithConstraints {
        val columns = if (maxWidth < 360.dp) 1 else 2
        Column(
            Modifier
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.outlineVariant),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            cells.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    row.forEach { (label, value) ->
                        Surface(Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (value != null) {
                                    Text(value, style = MaterialTheme.typography.titleMedium)
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        SymbolIcon(R.drawable.ic_arrow_downward, size = 16.dp)
                                        Text(formatBytes(stats.bytesIn), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                        SymbolIcon(R.drawable.ic_arrow_upward, size = 16.dp, modifier = Modifier.padding(start = 6.dp))
                                        Text(formatBytes(stats.bytesOut), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Next retry in 8 s", ticking; the countdown itself is not announced (handoff §5). */
@Composable
private fun RetryCountdown(nextRetryAtMillis: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(nextRetryAtMillis) {
        while (now < nextRetryAtMillis) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val secs = ((nextRetryAtMillis - now + 999) / 1000).coerceAtLeast(0).toInt()
    Text(stringResource(R.string.reconnect_next, secs), style = MaterialTheme.typography.titleMedium)
}

fun stateLabel(context: Context, state: TunnelState, stats: TunnelStats): String = when (state) {
    TunnelState.Off -> context.getString(R.string.tunnel_state_off)
    is TunnelState.Connecting -> context.getString(R.string.tunnel_state_connecting)
    is TunnelState.On -> context.getString(R.string.tunnel_state_on_uptime, formatUptimeShort(context, stats.uptimeSec))
    is TunnelState.Reconnecting -> context.getString(R.string.tunnel_state_reconnecting)
    is TunnelState.NeedsAttention -> context.getString(R.string.tunnel_state_needs_attention)
    TunnelState.Disconnecting -> context.getString(R.string.tunnel_state_disconnecting)
}

fun connectingStep(context: Context, step: String, profile: Profile, keyName: String?): String = when (step) {
    "identity" -> context.getString(R.string.connecting_identity)
    "auth" -> context.getString(R.string.connecting_auth, keyName ?: profile.auth.alias)
    "tunnel" -> context.getString(R.string.connecting_tunnel)
    else -> context.getString(R.string.connecting_resolving, profile.server.host)
}

fun transportName(context: Context, t: NetworkMonitor.Transport?): String = context.getString(
    when (t) {
        NetworkMonitor.Transport.WIFI -> R.string.net_wifi
        NetworkMonitor.Transport.MOBILE -> R.string.net_mobile
        NetworkMonitor.Transport.ETHERNET -> R.string.net_ethernet
        else -> R.string.net_other
    },
)

fun reconnectDetail(context: Context, s: TunnelState.Reconnecting, change: NetworkChange?): String {
    val attempt = s.attempt.coerceAtLeast(1)
    return when {
        s.code == Codes.NETWORK_LOST ->
            context.getString(R.string.err_network_title) + ". " + context.getString(R.string.err_network_body)
        s.reason == "networkChanged" && change?.from != null && change.to != null && change.from != change.to ->
            context.getString(R.string.reconnect_detail_network_changed, transportName(context, change.from), transportName(context, change.to), attempt)
        s.reason == "keepaliveTimeout" -> context.getString(R.string.reconnect_detail_timeout, attempt)
        else -> context.getString(R.string.reconnect_detail_lost, attempt)
    }
}

/** What TalkBack announces for the hero's state (handoff §5). */
private fun announcement(context: Context, m: HeroModel, errorTitle: String?): String = when (val s = m.state) {
    TunnelState.Off -> context.getString(R.string.a11y_disconnected)
    is TunnelState.Connecting -> context.getString(R.string.a11y_connecting, m.profile.name)
    is TunnelState.On -> context.getString(R.string.a11y_connected, m.profile.name)
    is TunnelState.Reconnecting -> {
        val secs = s.nextRetryAtMillis?.let { ((it - System.currentTimeMillis()) / 1000).coerceAtLeast(0).toInt() } ?: 0
        context.getString(R.string.a11y_reconnecting, m.profile.name, secs)
    }
    is TunnelState.NeedsAttention -> context.getString(
        R.string.a11y_attention,
        errorTitle.orEmpty(),
        errorAction(s.code)?.let { context.getString(it.label) } ?: context.getString(R.string.action_retry),
    )
    TunnelState.Disconnecting -> context.getString(R.string.tunnel_state_disconnecting)
}

// ---- Previews ------------------------------------------------------------------------

internal val previewProfile = Profile(
    id = "office", name = "Office", server = Server("jump.corp.example", 22, "alex"),
    auth = Auth(Auth.KEYSTORE, "k1"), routes = listOf("10.20.0.0/16", "10.30.4.0/24", "172.16.8.0/22"),
    dns = Dns(server = "10.20.0.53", suffixes = listOf("corp.example", "internal")),
)

@Composable
private fun PreviewHero(state: TunnelState) = SshovelTheme(dynamicColor = false) {
    Surface {
        StatusHero(
            HeroModel(
                state, previewProfile,
                TunnelStats(uptimeSec = 5040, bytesIn = 50_540_000, bytesOut = 3_250_000, activeFlows = 12, dnsTunneled = 214, dnsDirect = 1902),
                keyName = "Pixel StrongBox",
                networkChange = NetworkChange(NetworkMonitor.Transport.WIFI, NetworkMonitor.Transport.MOBILE),
            ),
            HeroActions(),
            Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "Hero on") @Preview(name = "Hero on dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewHeroOn() = PreviewHero(TunnelState.On())

@Preview(name = "Hero reconnecting") @Preview(name = "Hero reconnecting dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewHeroReconnecting() =
    PreviewHero(TunnelState.Reconnecting("networkChanged", 2, System.currentTimeMillis() + 8_000, null))

@Preview(name = "Hero offline") @Preview(name = "Hero offline dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewHeroOffline() =
    PreviewHero(TunnelState.Reconnecting("networkChanged", 1, System.currentTimeMillis() + 8_000, Codes.NETWORK_LOST))

@Preview(name = "Hero key unavailable") @Preview(name = "Hero key unavailable dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewHeroKeyUnavailable() = PreviewHero(TunnelState.NeedsAttention(Codes.KEY_UNAVAILABLE))

@Preview(name = "Hero revoked") @Preview(name = "Hero revoked dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewHeroRevoked() = PreviewHero(TunnelState.NeedsAttention(Codes.VPN_REVOKED))

@Preview(name = "Hero narrow", widthDp = 320, fontScale = 2f)
@Composable private fun PreviewHeroNarrow() = PreviewHero(TunnelState.On())

