// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.keys

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.keys.KeyEntry
import com.github.dennisklein.sshovel.keys.SshKeys
import com.github.dennisklein.sshovel.ui.components.Badge
import com.github.dennisklein.sshovel.ui.components.KeyBadge
import com.github.dennisklein.sshovel.ui.components.MaxWidth
import com.github.dennisklein.sshovel.ui.components.PublicKeyBlock
import com.github.dennisklein.sshovel.ui.components.SectionHeader
import com.github.dennisklein.sshovel.ui.components.SymbolIcon
import com.github.dennisklein.sshovel.ui.components.badge
import com.github.dennisklein.sshovel.ui.components.copyText
import com.github.dennisklein.sshovel.ui.components.fingerprintGroups
import com.github.dennisklein.sshovel.ui.components.mono
import com.github.dennisklein.sshovel.ui.components.shareText
import com.github.dennisklein.sshovel.ui.components.spokenFingerprint
import com.github.dennisklein.sshovel.ui.format.formatDate
import com.github.dennisklein.sshovel.ui.format.monoWords
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme
import kotlinx.coroutines.launch

/** Key detail (DESIGN_BRIEF §5.6, handoff K2): badges, facts, public key, fingerprint. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyDetailScreen(
    key: KeyEntry,
    usedBy: List<Profile>,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShowQr: (String) -> Unit,
    onCopied: () -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val context = LocalContext.current
    var authorizedLine by rememberSaveable { mutableStateOf(true) }
    val line = if (authorizedLine) key.authorizedLine else SshKeys.withoutOptions(key.authorizedLine)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(key.name) },
                navigationIcon = { IconButton(onBack) { SymbolIcon(R.drawable.ic_arrow_back, stringResource(R.string.cd_back)) } },
                actions = {
                    IconButton(onRename) { SymbolIcon(R.drawable.ic_edit, stringResource(R.string.cd_rename_key)) }
                    IconButton(onDelete) { SymbolIcon(R.drawable.ic_delete, stringResource(R.string.cd_delete_key)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        MaxWidth(Modifier.padding(padding)) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyBadge(key.badge(), large = true)
                    if (key.kind == KeyEntry.KEYSTORE) KeyBadge(Badge.NOT_EXPORTABLE, large = true)
                }
                Facts(
                    listOf(
                        stringResource(R.string.key_type) to keyTypeName(context, key),
                        stringResource(R.string.key_created) to formatDate(key.createdAt).orEmpty().let { d ->
                            if (key.kind == KeyEntry.KEYSTORE) stringResource(R.string.key_created_on_phone, d) else stringResource(R.string.key_imported_on, d)
                        },
                        stringResource(R.string.key_used_by) to if (usedBy.isEmpty()) stringResource(R.string.key_not_used) else usedBy.joinToString(", ") { it.name },
                    ),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SectionHeader(stringResource(R.string.public_key))
                PublicKeyBlock(line, onCopied, { context.shareText(line) }, { onShowQr(line) }, initiallyExpanded = true)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(authorizedLine, role = Role.Switch) { authorizedLine = it },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(monoWords(stringResource(R.string.copy_as_authorized), "authorized_keys"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(authorizedLine, null)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SectionHeader(stringResource(R.string.fingerprint))
                val clipboard = LocalClipboard.current
                val scope = rememberCoroutineScope()
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        "SHA256:" + fingerprintGroups(key.fingerprint).joinToString(" "),
                        style = MaterialTheme.typography.titleSmall.mono(),
                        modifier = Modifier.weight(1f).clearAndSetSemantics { contentDescription = spokenFingerprint(key.fingerprint) },
                    )
                    IconButton({ scope.launch { clipboard.copyText("fingerprint", key.fingerprint); onCopied() } }) {
                        SymbolIcon(R.drawable.ic_content_copy, stringResource(R.string.cd_copy_fingerprint), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Facts(rows: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { (label, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.3f))
                Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.7f))
            }
        }
    }
}

/** Rename (K2 edit): one text field. */
@Composable
fun RenameKeyDialog(key: KeyEntry, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(key.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_key)) },
        text = { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.key_name)) }, singleLine = true) },
        confirmButton = { TextButton({ onRename(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** K3: the key is in use; open the first profile using it, or OK. */
@Composable
fun KeyInUseDialog(key: KeyEntry, profiles: List<Profile>, onOpenProfile: (Profile) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { SymbolIcon(R.drawable.ic_link, tint = MaterialTheme.colorScheme.secondary) },
        title = { Text(stringResource(R.string.key_in_use_title)) },
        text = { Text(stringResource(R.string.key_in_use_body, joinNames(context, profiles.map { it.name }), key.name)) },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.ok)) } },
        dismissButton = profiles.firstOrNull()?.let { p -> { TextButton({ onOpenProfile(p) }) { Text(stringResource(R.string.open_profile, p.name)) } } },
    )
}

/** Deleting an unused key: destructive confirmation. */
@Composable
fun DeleteKeyDialog(key: KeyEntry, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { SymbolIcon(R.drawable.ic_delete, tint = MaterialTheme.colorScheme.secondary) },
        title = { Text(stringResource(R.string.delete_key_title, key.name)) },
        text = { Text(stringResource(R.string.delete_key_body)) },
        confirmButton = {
            TextButton(onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Preview(name = "K2 Key detail", heightDp = 1000) @Preview(name = "K2 Key detail dark", heightDp = 1000, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewDetail() = SshovelTheme(dynamicColor = false) {
    KeyDetailScreen(previewKeys[0], previewUsers["k1"].orEmpty(), {}, {}, {}, {}, {})
}

@Preview(name = "K3 In use") @Preview(name = "K3 In use dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewInUse() = SshovelTheme(dynamicColor = false) {
    KeyInUseDialog(previewKeys[0], previewUsers["k1"].orEmpty(), {}, {})
}

@Preview(name = "K3 Delete") @Preview(name = "K3 Delete dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewDelete() = SshovelTheme(dynamicColor = false) {
    DeleteKeyDialog(previewKeys[1], {}, {})
}

@Preview(name = "K2 Rename") @Preview(name = "K2 Rename dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewRename() = SshovelTheme(dynamicColor = false) {
    RenameKeyDialog(previewKeys[0], {}, {})
}
