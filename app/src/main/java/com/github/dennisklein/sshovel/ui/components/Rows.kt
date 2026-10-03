// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.keys.KeyEntry
import com.github.dennisklein.sshovel.ui.theme.LocalStateColors
import com.github.dennisklein.sshovel.ui.theme.MonoFamily
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme

/** Transparent list items for use on any surface. */
@Composable
fun clearListColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

/**
 * ProfileRow (handoff §2, H1): the radio selects the active profile, the rest of the row opens the
 * editor. "Default" marks the profile the tile uses.
 */
@Composable
fun ProfileRow(
    profile: Profile,
    selected: Boolean,
    isDefault: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    val useLabel = stringResource(R.string.cd_use_profile, profile.name)
    Row(
        Modifier
            .clickable(onClick = onOpen)
            .heightIn(min = 72.dp)
            .padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioButton(selected, onSelect, enabled = enabled, modifier = Modifier.semantics { contentDescription = useLabel })
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(profile.name, style = MaterialTheme.typography.bodyLarge)
                if (isDefault) DefaultLabel()
            }
            val subnets = pluralStringResource(R.plurals.profile_subnets, profile.routes.size, profile.routes.size)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontFamily = MonoFamily, fontSize = 13.sp)) { append("${profile.server.user}@${profile.server.host}") }
                    append(" · $subnets")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SymbolIcon(R.drawable.ic_chevron_right, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The "Default" label: outlined, extraSmall shape (handoff §1.5). */
@Composable
fun DefaultLabel() {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(stringResource(R.string.profile_default_label), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp))
    }
}

/** AlwaysOnRow (handoff H3): informational, not clickable. */
@Composable
fun AlwaysOnRow(lockdown: Boolean, modifier: Modifier = Modifier) {
    OutlinedCard(modifier, shape = MaterialTheme.shapes.medium) {
        Row(
            Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SymbolIcon(R.drawable.ic_shield_lock, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column {
                Text(
                    stringResource(if (lockdown) R.string.always_on_lockdown_title else R.string.always_on_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(if (lockdown) R.string.always_on_lockdown_body else R.string.always_on_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

enum class Badge { STRONGBOX, HARDWARE, SOFTWARE, ENCRYPTED, NOT_EXPORTABLE }

/** The security badge of a key (DESIGN_BRIEF §5.6). */
fun KeyEntry.badge(): Badge = when (security) {
    KeyEntry.STRONGBOX -> Badge.STRONGBOX
    KeyEntry.TEE -> Badge.HARDWARE
    // A Keystore key without secure hardware (emulators, some old devices): say so plainly.
    KeyEntry.SOFTWARE -> Badge.SOFTWARE
    else -> Badge.ENCRYPTED
}

/** KeyBadge (handoff §2): 24 dp in rows, 32 dp in detail. */
@Composable
fun KeyBadge(badge: Badge, large: Boolean = false) {
    val tonal = badge == Badge.STRONGBOX || badge == Badge.HARDWARE
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = if (large) MaterialTheme.shapes.small else RoundedCornerShape(6.dp),
        color = if (tonal) cs.secondaryContainer else Color.Transparent,
        contentColor = if (tonal) cs.onSecondaryContainer else cs.onSurfaceVariant,
        border = if (tonal) null else BorderStroke(1.dp, cs.outlineVariant),
    ) {
        Row(
            Modifier
                .heightIn(min = if (large) 32.dp else 24.dp)
                .padding(start = if (large) 8.dp else 6.dp, end = if (large) 12.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (large) 6.dp else 4.dp),
        ) {
            SymbolIcon(
                when (badge) {
                    Badge.STRONGBOX -> R.drawable.ic_memory
                    Badge.HARDWARE -> R.drawable.ic_verified_user
                    Badge.ENCRYPTED -> R.drawable.ic_lock
                    Badge.SOFTWARE -> R.drawable.ic_key
                    Badge.NOT_EXPORTABLE -> R.drawable.ic_block
                },
                size = if (large) 18.dp else 16.dp,
            )
            Text(
                stringResource(
                    when (badge) {
                        Badge.STRONGBOX -> R.string.badge_strongbox
                        Badge.HARDWARE -> R.string.badge_hardware
                        Badge.ENCRYPTED -> R.string.badge_encrypted
                        Badge.SOFTWARE -> R.string.badge_software
                        Badge.NOT_EXPORTABLE -> R.string.badge_not_exportable
                    },
                ),
                style = if (large) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * KeyRow (handoff K1): disc, name, type line, badge + "date · profiles", chevron. The disc color
 * is a secondary cue only; the badge text is the source of truth.
 */
@Composable
fun KeyRow(key: KeyEntry, typeLine: String, meta: String, onClick: () -> Unit) {
    val hardware = key.kind == KeyEntry.KEYSTORE
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            Modifier.size(40.dp),
            shape = CircleShape,
            color = if (hardware) cs.primaryContainer else cs.surfaceContainerHighest,
            contentColor = if (hardware) cs.onPrimaryContainer else cs.onSurfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) { SymbolIcon(R.drawable.ic_key) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(key.name, style = MaterialTheme.typography.bodyLarge)
            Text(typeLine, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                KeyBadge(key.badge())
                Text(meta, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        SymbolIcon(R.drawable.ic_chevron_right, tint = cs.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

/** A key choice in the editor's Authentication section (handoff P1). */
@Composable
fun KeyChoiceRow(key: KeyEntry, typeLine: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = 72.dp)
            .padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioButton(selected, null, enabled = enabled, modifier = Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(key.name, style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(typeLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                KeyBadge(key.badge())
            }
        }
    }
}

/**
 * SubnetRow (handoff §2, P2/P3/P6): normal, error (icon + text), or read-only. A [warning]
 * (e.g. already covered by another subnet) doesn't block saving and uses the warning accent.
 */
@Composable
fun SubnetRow(cidr: String, error: String?, onRemove: (() -> Unit)?, leadingIcon: Int = R.drawable.ic_lan, warning: Boolean = false, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val accent = if (warning) LocalStateColors.current.stateReconnecting else cs.error
    Row(
        modifier
            .heightIn(min = if (error != null) 72.dp else 56.dp)
            .padding(start = 16.dp, end = if (onRemove != null) 4.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when {
            error == null -> SymbolIcon(leadingIcon, tint = cs.onSurfaceVariant)
            warning -> SymbolIcon(R.drawable.ic_warning, tint = accent)
            else -> SymbolIcon(R.drawable.ic_error, tint = accent)
        }
        Column(Modifier.weight(1f)) {
            Text(cidr, style = MaterialTheme.typography.bodyLarge.mono())
            if (error != null) Text(error, style = MaterialTheme.typography.bodyMedium, color = accent)
        }
        if (onRemove != null) {
            IconButton(onRemove) {
                SymbolIcon(R.drawable.ic_remove_circle_outline, stringResource(R.string.cd_remove, cidr), tint = cs.onSurfaceVariant)
            }
        }
    }
}

/** SettingsRow (handoff G5): icon, text, optional switch or trailing icon. */
@Composable
fun SettingsRow(
    icon: Int,
    title: String,
    subtitle: String? = null,
    checked: Boolean? = null,
    trailingIcon: Int? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
) {
    val base = Modifier
    val clickable = when {
        checked != null && onCheckedChange != null ->
            base.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
        onClick != null -> base.clickable(enabled = enabled, onClick = onClick)
        else -> base
    }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { SymbolIcon(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = when {
            checked != null -> ({ Switch(checked, null, enabled = enabled) })
            trailingIcon != null -> ({ SymbolIcon(trailingIcon, tint = MaterialTheme.colorScheme.onSurfaceVariant) })
            else -> null
        },
        colors = clearListColors(),
        modifier = clickable,
    )
}

enum class CheckStatus { PASSED, RUNNING, FAILED, NOT_RUN }

/** CheckResultRow (handoff O6): one connection test check. A failure is shown by the caller. */
@Composable
fun CheckResultRow(title: String, status: CheckStatus, supporting: (@Composable () -> Unit)? = null) {
    val st = LocalStateColors.current
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (status) {
            CheckStatus.PASSED -> SymbolIcon(R.drawable.ic_check_circle_filled, tint = st.stateOn)
            CheckStatus.RUNNING -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            CheckStatus.FAILED -> SymbolIcon(R.drawable.ic_cancel_filled, tint = cs.error)
            CheckStatus.NOT_RUN -> SymbolIcon(R.drawable.ic_radio_button_unchecked, tint = cs.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            // "{check} passed" is announced as the step completes (handoff §5).
            val passed = stringResource(R.string.a11y_check_passed, title)
            Text(
                if (status == CheckStatus.NOT_RUN) stringResource(R.string.test_not_tested, title) else title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (status == CheckStatus.NOT_RUN) cs.onSurfaceVariant else cs.onSurface,
                modifier = if (status == CheckStatus.PASSED) Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = passed
                } else Modifier,
            )
            if (supporting != null && status == CheckStatus.PASSED) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides cs.onSurfaceVariant,
                    androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.bodyMedium,
                ) { supporting() }
            }
        }
    }
}

/** AppRow (handoff A1): the whole row toggles the checkbox. */
@Composable
fun AppRow(label: String, packageName: String, icon: Painter?, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier
            .toggleable(checked, role = Role.Checkbox, onValueChange = onToggle)
            .heightIn(min = 72.dp)
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) {
            androidx.compose.foundation.Image(icon, null, Modifier.size(40.dp))
        } else {
            Surface(Modifier.size(40.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.tertiaryContainer) {
                // The initial is decoration in a fixed 40 dp tile: sized in dp so large fonts don't clip it.
                val size = with(androidx.compose.ui.platform.LocalDensity.current) { 18.dp.toSp() }
                Box(contentAlignment = Alignment.Center) {
                    Text(label.take(1).uppercase(), style = MaterialTheme.typography.titleMedium.copy(fontSize = size, lineHeight = size))
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                packageName,
                style = MaterialTheme.typography.bodySmall.mono(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Checkbox(checked, null, Modifier.size(48.dp))
    }
}

/** DiscoveredRouteRow (handoff D2): checkbox, CIDR, interface or a warning label. */
@Composable
fun DiscoveredRouteRow(cidr: String, detail: String, detailIcon: Int?, checked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onToggle)
            .heightIn(min = 64.dp)
            .padding(start = 12.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked, null, Modifier.size(48.dp), enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(cidr, style = MaterialTheme.typography.bodyLarge.mono())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (detailIcon != null) SymbolIcon(detailIcon, size = 14.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---- Previews ------------------------------------------------------------------------

@Preview(name = "Rows", showBackground = true)
@Preview(name = "Rows dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewRows() = SshovelTheme(dynamicColor = false) {
    Surface {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            ProfileRow(previewProfile, selected = true, isDefault = true, enabled = true, onSelect = {}, onOpen = {})
            AlwaysOnRow(lockdown = true, Modifier.padding(16.dp))
            SubnetRow("10.20.4.0/24", "Already covered by 10.20.0.0/16", onRemove = {})
            SettingsRow(R.drawable.ic_lock, "Require unlock to use the tile", "On the lock screen the tile asks you to unlock first", checked = true, onCheckedChange = {})
            CheckResultRow("Server reachable", CheckStatus.PASSED) { Text("jump.corp.example:22 · 38 ms") }
            CheckResultRow("Key accepted", CheckStatus.RUNNING)
            CheckResultRow("Forwarding allowed", CheckStatus.NOT_RUN)
            AppRow("Chrome", "com.android.chrome", null, checked = true) {}
            DiscoveredRouteRow("0.0.0.0/0", "Default route — would send all traffic through the tunnel", R.drawable.ic_warning, checked = false, enabled = true) {}
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyBadge(Badge.STRONGBOX)
                KeyBadge(Badge.ENCRYPTED)
                KeyBadge(Badge.NOT_EXPORTABLE, large = true)
            }
        }
    }
}
