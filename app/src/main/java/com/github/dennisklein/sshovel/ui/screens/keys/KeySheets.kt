// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.keys

import android.content.ClipboardManager
import android.content.res.Configuration
import android.net.Uri
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.tunnel.Codes
import com.github.dennisklein.sshovel.ui.components.InfoNote
import com.github.dennisklein.sshovel.ui.components.QrCode
import com.github.dennisklein.sshovel.ui.components.SymbolIcon
import com.github.dennisklein.sshovel.ui.components.mono
import com.github.dennisklein.sshovel.ui.format.monoWords
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme

/** A bottom sheet with the handoff's layout: title, 20 dp gaps, 24 dp sides, above the IME. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeySheetFrame(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            content = content,
        )
    }
}

@Composable
private fun SheetActions(busy: Boolean, primary: String, enabled: Boolean, onCancel: () -> Unit, onPrimary: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onCancel) { Text(stringResource(R.string.action_cancel)) }
        Button(onPrimary, enabled = enabled && !busy) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(primary)
        }
    }
}

/** K4: name the key; the note says the private key can never leave this device. */
@Composable
fun CreateKeyContent(hasStrongBox: Boolean, busy: Boolean, defaultName: String, onCreate: (String) -> Unit, onCancel: () -> Unit, hardware: Boolean = true) {
    var name by remember { mutableStateOf(defaultName) }
    Text(stringResource(R.string.create_key), style = MaterialTheme.typography.headlineSmall)
    OutlinedTextField(
        name, { name = it },
        Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.key_name)) },
        supportingText = { Text(stringResource(R.string.key_name_helper)) },
        singleLine = true,
    )
    // Where the key will live, known before it exists (the badge after creation says the same).
    val note = when {
        hasStrongBox -> R.string.create_key_note_strongbox
        hardware -> R.string.create_key_note_tee
        else -> R.string.create_key_note_software
    }
    InfoNote(if (hardware) R.drawable.ic_memory else R.drawable.ic_key, stringResource(note), corner = 12.dp)
    SheetActions(busy, stringResource(R.string.create), name.isNotBlank(), onCancel) { onCreate(name.trim()) }
}

@Composable
fun CreateKeySheet(hasStrongBox: Boolean, busy: Boolean, defaultName: String, onCreate: (String) -> Unit, onDismiss: () -> Unit, hardware: Boolean = true) {
    KeySheetFrame(onDismiss) { CreateKeyContent(hasStrongBox, busy, defaultName, onCreate, onDismiss, hardware) }
}

/**
 * K5: paste or pick a private key, optional passphrase, and a name. The key text lives only in
 * this composition (never in saved state, which could be written to disk); the bytes handed on
 * are zeroed after import, and the clipboard is cleared if the key came from it.
 */
@Composable
fun ImportKeyContent(busy: Boolean, error: String?, onImport: (name: String, key: ByteArray, passphrase: CharArray?) -> Unit, onEdited: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var fromFile by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var file by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            file = uri
            fileName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment
            if (name.isBlank()) name = fileName.orEmpty().substringBeforeLast('.')
            onEdited()
        }
    }
    Text(stringResource(R.string.import_key), style = MaterialTheme.typography.headlineSmall)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(!fromFile, { fromFile = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.import_paste)) }
        SegmentedButton(fromFile, { fromFile = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.import_file)) }
    }
    val keyError = when (error) {
        Codes.KEY_UNSUPPORTED -> stringResource(R.string.err_unsupported_key)
        Codes.KEY_PUTTY -> stringResource(R.string.err_unsupported_key) + " " + stringResource(R.string.err_unsupported_putty)
        else -> null
    }
    if (!fromFile) {
        OutlinedTextField(
            text, { text = it; onEdited() },
            Modifier.fillMaxWidth().heightIn(min = 120.dp),
            label = { Text(stringResource(R.string.private_key)) },
            textStyle = MaterialTheme.typography.bodyMedium.mono(),
            isError = keyError != null,
            supportingText = keyError?.let { { Text(monoWords(it, "puttygen -O private-openssh")) } },
            maxLines = 6,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ pick.launch(arrayOf("*/*")) }) {
                SymbolIcon(R.drawable.ic_file_open, size = 18.dp)
                Text(stringResource(R.string.import_choose_file), Modifier.padding(start = 8.dp))
            }
            fileName?.let { Text(it, style = MaterialTheme.typography.bodyMedium.mono(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            keyError?.let { Text(monoWords(it, "puttygen -O private-openssh"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
    val passError = error == Codes.KEY_PASSPHRASE
    OutlinedTextField(
        passphrase, { passphrase = it; onEdited() },
        Modifier.fillMaxWidth(),
        label = { Text(stringResource(if (passError) R.string.passphrase else R.string.passphrase_optional)) },
        singleLine = true,
        visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
        isError = passError,
        supportingText = if (passError) ({ Text(stringResource(R.string.err_wrong_passphrase)) }) else null,
        trailingIcon = {
            IconButton({ showPass = !showPass }) {
                SymbolIcon(
                    if (showPass) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                    stringResource(if (showPass) R.string.cd_hide_pass else R.string.cd_show_pass),
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
    )
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.key_name)) }, singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SymbolIcon(R.drawable.ic_info, size = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.import_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val ready = name.isNotBlank() && (if (fromFile) file != null else text.isNotBlank())
    SheetActions(busy, stringResource(R.string.import_action), ready, onCancel) {
        val bytes = if (fromFile) {
            file?.let { u -> runCatching { context.contentResolver.openInputStream(u)?.use { it.readBytes() } }.getOrNull() } ?: ByteArray(0)
        } else {
            text.encodeToByteArray()
        }
        val pass = passphrase.takeIf { it.isNotEmpty() }?.toCharArray()
        if (!fromFile) clearClipboardIfKey(context, text)
        onImport(name.trim(), bytes, pass)
    }
}

/** Clears the clipboard if it still holds the pasted private key (handoff K5). */
private fun clearClipboardIfKey(context: android.content.Context, pasted: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
    if (clip != null && clip.contains("PRIVATE KEY") && pasted.contains(clip.trim().take(64))) cm.clearPrimaryClip()
}

@Composable
fun ImportKeySheet(busy: Boolean, error: String?, onImport: (String, ByteArray, CharArray?) -> Unit, onEdited: () -> Unit, onDismiss: () -> Unit) {
    KeySheetFrame(onDismiss) { ImportKeyContent(busy, error, onImport, onEdited, onDismiss) }
}

/**
 * K6: the public key line as a QR code (black on white, both themes) with the screen at full
 * brightness while the sheet is open.
 */
@Composable
fun ColumnScope.QrContent(line: String, onDone: () -> Unit) {
    Text(stringResource(R.string.qr_title), style = MaterialTheme.typography.headlineSmall)
    QrCode(line, stringResource(R.string.qr_title), Modifier.widthIn(max = 288.dp).fillMaxWidth().align(Alignment.CenterHorizontally))
    Text(line, style = MaterialTheme.typography.bodyMedium.mono(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(
        monoWords(stringResource(R.string.qr_note, "~/.ssh/authorized_keys"), "~/.ssh/authorized_keys"),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    TextButton(onDone, Modifier.align(Alignment.End)) { Text(stringResource(R.string.done)) }
}

@Composable
fun QrSheet(line: String, onDismiss: () -> Unit) {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val window = activity?.window
        val before = window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL } }
        onDispose { window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = before } } }
    }
    KeySheetFrame(onDismiss) { QrContent(line, onDismiss) }
}

// ---- Previews (sheet content on a surface; ModalBottomSheet needs a window) ------------

@Composable
private fun SheetPreview(content: @Composable ColumnScope.() -> Unit) = SshovelTheme(dynamicColor = false) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
    }
}

@Preview(name = "K4 Create key") @Preview(name = "K4 Create key dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewCreate() = SheetPreview { CreateKeyContent(true, false, "Pixel StrongBox 2", {}, {}) }

@Preview(name = "K4 Create key, software keystore") @Preview(name = "K4 Create key, software keystore dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewCreateSoftware() = SheetPreview { CreateKeyContent(false, false, "sshovel key", {}, {}, hardware = false) }

@Preview(name = "K5 Import key") @Preview(name = "K5 Import key dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewImport() = SheetPreview { ImportKeyContent(false, null, { _, _, _ -> }, {}, {}) }

@Preview(name = "K5b Wrong passphrase") @Preview(name = "K5b Wrong passphrase dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewImportPassphrase() = SheetPreview { ImportKeyContent(false, Codes.KEY_PASSPHRASE, { _, _, _ -> }, {}, {}) }

@Preview(name = "K5b Unsupported") @Preview(name = "K5b Unsupported dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewImportPutty() = SheetPreview { ImportKeyContent(false, Codes.KEY_PUTTY, { _, _, _ -> }, {}, {}) }

@Preview(name = "K6 QR") @Preview(name = "K6 QR dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewQr() = SheetPreview {
    QrContent("restrict,port-forwarding ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBHk3Pj8fWm0q9sX2c7Qe1RtLbNf4aYh0uVwKz6JdE5gSx pixel@sshovel") {}
}
