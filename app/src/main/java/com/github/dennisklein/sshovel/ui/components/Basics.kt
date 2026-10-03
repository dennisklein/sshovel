// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.components

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.ui.theme.MonoFamily
import java.io.File

/** The monospace variant of a type role (handoff §1.3): machine values only. */
fun TextStyle.mono(): TextStyle = copy(fontFamily = MonoFamily)

/** Content column capped at 600 dp and centred on wide screens and in landscape (handoff §1.4). */
@Composable
fun MaxWidth(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 600.dp).fillMaxWidth(), content = content)
    }
}

/** Section header: titleSmall in primary (handoff §1.3). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
fun SectionDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** A Material Symbol from res/drawable, tinted with the content color unless [tint] is set. */
@Composable
fun SymbolIcon(id: Int, contentDescription: String? = null, modifier: Modifier = Modifier, tint: Color = Color.Unspecified, size: Dp = 24.dp) {
    Icon(
        painterResource(id),
        contentDescription,
        modifier = modifier.size(size),
        tint = if (tint == Color.Unspecified) LocalContentColor.current else tint,
    )
}

/** The sshovel shovel glyph (handoff icons/ic_sshovel.svg). */
val LogoIcon = R.drawable.ic_sshovel

/** Copies [text] and returns; the caller shows "Copied" (handoff O3). */
suspend fun Clipboard.copyText(label: String, text: String) {
    setClipEntry(ClipEntry(ClipData.newPlainText(label, text)))
}

/** Android's share sheet for a line of text (public keys; never private material). */
fun Context.shareText(text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Android's share sheet for a text file (Diagnostics export). The file lives in cache/diagnostics
 * only from here until the next share, which replaces it.
 */
fun Context.shareTextFile(fileName: String, text: String) {
    val dir = File(cacheDir, "diagnostics").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, fileName).apply { writeText(text) }
    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TITLE, fileName)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun Context.openVpnSettings() = startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

fun Context.openNotificationSettings() = startActivity(
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
)

/** Screen padding constants from the handoff (§1.4). */
@Immutable
object Spacing {
    val screen = 16.dp
    val onboarding = 24.dp
}
