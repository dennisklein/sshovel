// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme
import kotlinx.coroutines.launch

/**
 * PublicKeyBlock (handoff §2, O3/K2): the key line in monospace, three lines until expanded, and
 * Copy / Share / Show QR code chips.
 */
@Composable
fun PublicKeyBlock(
    line: String,
    onCopied: () -> Unit,
    onShare: () -> Unit,
    onShowQr: () -> Unit,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium.mono().copy(fontSize = 13.sp, lineHeight = 20.sp),
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton({ expanded = !expanded }, Modifier.padding(top = 0.dp)) {
                    SymbolIcon(
                        if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
                        stringResource(if (expanded) R.string.cd_collapse_key else R.string.cd_expand_key),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip(R.drawable.ic_content_copy, stringResource(R.string.action_copy), onClick = {
                scope.launch { clipboard.copyText("public key", line); onCopied() }
            })
            ActionChip(R.drawable.ic_share, stringResource(R.string.action_share), onShare)
            ActionChip(R.drawable.ic_qr_code_2, stringResource(R.string.action_show_qr), onShowQr)
        }
    }
}

/** An AssistChip with a primary-tinted leading icon. */
@Composable
fun ActionChip(icon: Int, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        enabled = enabled,
        leadingIcon = { SymbolIcon(icon, size = AssistChipDefaults.IconSize, tint = MaterialTheme.colorScheme.primary) },
    )
}

/** A one-line command with a copy button (handoff O3, "Or run this on the server"). */
@Composable
fun CommandBlock(command: String, onCopied: () -> Unit, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                command,
                style = MaterialTheme.typography.bodyMedium.mono(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton({ scope.launch { clipboard.copyText("command", command); onCopied() } }) {
                SymbolIcon(R.drawable.ic_content_copy, stringResource(R.string.cd_copy_command), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Chip field (handoff §2): values as InputChips (mono, with remove), and an "Add …" AssistChip
 * that turns into a text field. [onAdd] returns an error message, or null if the value was added.
 */
@Composable
fun ChipField(
    values: List<String>,
    addLabel: String,
    onRemove: (String) -> Unit,
    onAdd: (String) -> String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String? = null,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    fun submit() {
        val v = text.trim()
        if (v.isEmpty()) {
            adding = false
            error = null
            return
        }
        error = onAdd(v)
        if (error == null) {
            text = ""
            adding = false
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            values.forEach { v ->
                // The whole chip removes the value (a 48 dp target; the close icon marks it).
                val removeLabel = stringResource(R.string.cd_remove, v)
                InputChip(
                    selected = false,
                    onClick = { onRemove(v) },
                    enabled = enabled,
                    label = { Text(v, style = MaterialTheme.typography.labelLarge.mono()) },
                    // Merged into the chip: TalkBack reads "corp.example, Remove corp.example".
                    trailingIcon = if (enabled) ({ SymbolIcon(R.drawable.ic_close, removeLabel, size = InputChipDefaults.IconSize) }) else null,
                )
            }
            if (enabled && !adding) {
                AssistChip(
                    onClick = { adding = true },
                    label = { Text(addLabel) },
                    leadingIcon = { SymbolIcon(R.drawable.ic_add, size = AssistChipDefaults.IconSize) },
                    colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.primary, leadingIconContentColor = MaterialTheme.colorScheme.primary),
                )
            }
        }
        if (adding) {
            LaunchedEffect(Unit) { focus.requestFocus() }
            OutlinedTextField(
                text,
                { text = it; error = null },
                Modifier.fillMaxWidth().focusRequester(focus),
                label = { Text(addLabel) },
                placeholder = placeholder?.let { { Text(it) } },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.mono(),
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                trailingIcon = {
                    IconButton(::submit) { SymbolIcon(R.drawable.ic_check, addLabel) }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
        }
    }
}

/**
 * CidrInput (handoff §2, P2/P3): a mono text field and a tonal add button. [error] shows under the
 * field with an error icon (never color alone).
 */
@Composable
fun CidrInput(
    value: String,
    onValueChange: (String) -> Unit,
    onAdd: () -> Unit,
    error: String?,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        OutlinedTextField(
            value,
            onValueChange,
            Modifier.weight(1f).focusRequester(focusRequester),
            label = if (error != null || value.isNotEmpty()) ({ Text(stringResource(R.string.add_subnet)) }) else null,
            placeholder = { Text(stringResource(R.string.add_subnet_hint), style = MaterialTheme.typography.bodyLarge.mono()) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.mono(),
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            trailingIcon = error?.let { { SymbolIcon(R.drawable.ic_error_filled, tint = MaterialTheme.colorScheme.error) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { onAdd() }),
        )
        FilledTonalIconButton(onAdd, Modifier.padding(top = 4.dp), enabled = value.isNotBlank() && error == null) {
            SymbolIcon(R.drawable.ic_add, stringResource(R.string.cd_add_subnet))
        }
    }
}

@Preview(name = "Inputs", showBackground = true)
@Preview(name = "Inputs dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewInputs() = SshovelTheme(dynamicColor = false) {
    Surface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PublicKeyBlock(
                "restrict,port-forwarding ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBHk3Pj8fWm0q9sX2c7Qe1RtLbNf4aYh0uVwKz6JdE5gSx pixel-strongbox@sshovel",
                {}, {}, {},
            )
            CommandBlock("echo 'restrict,port-forwarding ecdsa-sha2-nistp256 AAAAE2Vj…' >> ~/.ssh/authorized_keys", {})
            ChipField(listOf("corp.example", "internal"), "Add suffix", {}, { null })
            CidrInput("10.40.0.0/33", {}, {}, "Not a valid CIDR. Prefix length must be 0–32, e.g. 10.40.0.0/16")
        }
    }
}
