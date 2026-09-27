// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.tunnel.Codes
import com.github.dennisklein.sshovel.tunnel.TunnelState
import com.github.dennisklein.sshovel.ui.theme.LocalStateColors
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme

/** The five indicator looks (handoff §1.2); Disconnecting shares Connecting's progress look. */
enum class IndicatorKind { OFF, CONNECTING, ON, RECONNECTING, ATTENTION }

fun TunnelState.indicatorKind(): IndicatorKind = when (this) {
    TunnelState.Off -> IndicatorKind.OFF
    is TunnelState.Connecting, TunnelState.Disconnecting -> IndicatorKind.CONNECTING
    is TunnelState.On -> IndicatorKind.ON
    is TunnelState.Reconnecting -> IndicatorKind.RECONNECTING
    is TunnelState.NeedsAttention -> IndicatorKind.ATTENTION
}

/** Host key errors show `gpp_bad` instead of `error` (handoff §1.2). */
fun TunnelState.isHostKeyError(): Boolean =
    this is TunnelState.NeedsAttention && (code == Codes.HOST_KEY_MISMATCH || code == Codes.HOST_KEY_UNVERIFIED)

enum class IndicatorSize { LARGE, SMALL }

/**
 * StateIndicator (handoff §2): icon + label on a tonal surface, never color alone. Large is the
 * hero's (40 dp), Small is for lists (32 dp). [onErrorSurface] inverts Needs attention for the
 * errorContainer hero.
 */
@Composable
fun StateIndicator(
    kind: IndicatorKind,
    label: String,
    modifier: Modifier = Modifier,
    size: IndicatorSize = IndicatorSize.LARGE,
    onErrorSurface: Boolean = false,
    hostKeyError: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val st = LocalStateColors.current
    val (container, content) = when (kind) {
        IndicatorKind.OFF -> cs.surfaceContainerHighest to cs.onSurfaceVariant
        IndicatorKind.CONNECTING -> cs.primaryContainer to cs.onPrimaryContainer
        IndicatorKind.ON -> st.stateOnContainer to st.onStateOnContainer
        IndicatorKind.RECONNECTING -> st.stateReconnectingContainer to st.onStateReconnectingContainer
        IndicatorKind.ATTENTION -> if (onErrorSurface) cs.error to cs.onError else cs.errorContainer to cs.onErrorContainer
    }
    val large = size == IndicatorSize.LARGE
    val iconSize = if (large) 20.dp else 18.dp
    Surface(modifier, shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
        Row(
            Modifier
                .heightIn(min = if (large) 40.dp else 32.dp)
                .padding(start = if (large) 12.dp else 8.dp, end = if (large) 16.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (kind == IndicatorKind.CONNECTING) {
                CircularProgressIndicator(Modifier.size(if (large) 18.dp else 16.dp), color = content, strokeWidth = 2.dp, trackColor = Color.Transparent)
            } else {
                SymbolIcon(
                    when (kind) {
                        IndicatorKind.OFF -> R.drawable.ic_vpn_key_off
                        IndicatorKind.ON -> R.drawable.ic_vpn_lock_filled
                        IndicatorKind.RECONNECTING -> R.drawable.ic_sync_problem
                        else -> if (hostKeyError) R.drawable.ic_gpp_bad_filled else R.drawable.ic_error_filled
                    },
                    size = iconSize,
                )
            }
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Preview(name = "State indicator", showBackground = true)
@Preview(name = "State indicator dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewIndicators() = SshovelTheme(dynamicColor = false) {
    Surface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StateIndicator(IndicatorKind.OFF, "Off")
            StateIndicator(IndicatorKind.CONNECTING, "Connecting…")
            StateIndicator(IndicatorKind.ON, "On · 1 h 24 min")
            StateIndicator(IndicatorKind.RECONNECTING, "Reconnecting…")
            StateIndicator(IndicatorKind.ATTENTION, "Needs attention")
            StateIndicator(IndicatorKind.ATTENTION, "Needs attention", hostKeyError = true, size = IndicatorSize.SMALL)
        }
    }
}
