// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.keys.SshKeys
import com.github.dennisklein.sshovel.ui.theme.MonoFamily
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme
import kotlinx.coroutines.launch

/** "SHA256:nThbRk2m…" → ["nThb", "Rk2m", …]. */
fun fingerprintGroups(fingerprint: String): List<String> = fingerprint.removePrefix("SHA256:").chunked(4)

/** TalkBack reads groups character by character with pauses (handoff §5): "n, T, h, b. R, k, …". */
fun spokenFingerprint(fingerprint: String): String =
    fingerprintGroups(fingerprint).joinToString(". ") { g -> g.toList().joinToString(", ") { spokenChar(it) } }

private fun spokenChar(c: Char): String = when (c) {
    '+' -> "plus"
    '/' -> "slash"
    else -> if (c.isUpperCase()) "capital $c" else c.toString()
}

enum class FingerprintSize { LARGE, COMPACT }

/**
 * The grouped fingerprint grid: 4-character groups, at most [perRow] per row. Groups never
 * break; when [perRow] don't fit (large fonts), rows hold fewer.
 */
@Composable
fun FingerprintGrid(fingerprint: String, style: TextStyle, perRow: Int = 4, gap: androidx.compose.ui.unit.Dp = 14.dp) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.clearAndSetSemantics { contentDescription = spokenFingerprint(fingerprint) },
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        maxItemsInEachRow = perRow,
    ) {
        fingerprintGroups(fingerprint).forEach { Text(it, style = style, softWrap = false) }
    }
}

/**
 * FingerprintBlock (handoff §2): header "ECDSA · SHA-256" (+ [trailing], e.g. the pin date), the
 * grouped fingerprint, and a copy button. Large in onboarding and dialogs, Compact in the editor.
 */
@Composable
fun FingerprintBlock(
    keyType: String,
    fingerprint: String,
    modifier: Modifier = Modifier,
    size: FingerprintSize = FingerprintSize.LARGE,
    trailing: String? = null,
    copyable: Boolean = true,
    onCopied: () -> Unit = {},
    footer: (@Composable () -> Unit)? = null,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val large = size == FingerprintSize.LARGE
    val style = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = if (large) 17.sp else 15.sp,
        lineHeight = if (large) 26.sp else 22.sp,
        letterSpacing = if (large) 0.5.sp else 0.sp,
    )
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column(
            Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = if (copyable) 4.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (large) 12.dp else 10.dp),
        ) {
            Row(Modifier.padding(end = if (copyable) 12.dp else 0.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SymbolIcon(R.drawable.ic_fingerprint, size = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${SshKeys.displayType(keyType)} · SHA-256", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (trailing != null) {
                    Text(trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) { FingerprintGrid(fingerprint, style, gap = if (large) 14.dp else 12.dp) }
                if (copyable) {
                    IconButton({ scope.launch { clipboard.copyText("fingerprint", fingerprint); onCopied() } }, Modifier.padding(top = 0.dp)) {
                        SymbolIcon(R.drawable.ic_content_copy, stringResource(R.string.cd_copy_fingerprint), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            footer?.invoke()
        }
    }
}

/**
 * The S4 pair: trusted and received fingerprints side by side, the received one outlined in
 * error. Groups aren't highlighted: every group differs; users compare whole fingerprints.
 */
@Composable
fun FingerprintPair(
    trustedType: String?,
    trustedFingerprint: String?,
    trustedMeta: String?,
    receivedType: String?,
    receivedFingerprint: String?,
    receivedMeta: String?,
    modifier: Modifier = Modifier,
) {
    val style = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 20.sp)
    val cs = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        @Composable
        fun Half(label: Int, icon: Int, type: String?, fp: String?, meta: String?, received: Boolean) {
            OutlinedCard(
                Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                border = if (received) BorderStroke(2.dp, cs.error) else BorderStroke(1.dp, cs.outlineVariant),
                colors = CardDefaults.outlinedCardColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val c = if (received) cs.error else cs.onSurfaceVariant
                        SymbolIcon(icon, size = 16.dp, tint = c)
                        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = c)
                    }
                    val line = listOfNotNull(type?.let(SshKeys::displayType), meta).joinToString(" · ")
                    if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    if (fp != null) FingerprintGrid(fp, style, perRow = 2, gap = 10.dp)
                }
            }
        }
        Half(R.string.mismatch_trusted, R.drawable.ic_verified_user, trustedType, trustedFingerprint, trustedMeta, received = false)
        Half(R.string.mismatch_received, R.drawable.ic_gpp_bad, receivedType, receivedFingerprint, receivedMeta, received = true)
    }
}

@Preview(name = "Fingerprints", showBackground = true)
@Preview(name = "Fingerprints dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewFingerprints() = SshovelTheme(dynamicColor = false) {
    Surface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FingerprintBlock("ecdsa-sha2-nistp256", "SHA256:nThbRk2mXJvF3e8pQz1LYw7cDh0KsVa4Tq9NoM6uGf5BwE+i2rY")
            FingerprintBlock("ecdsa-sha2-nistp256", "SHA256:nThbRk2mXJvF3e8pQz1LYw7cDh0KsVa4Tq9NoM6uGf5BwE+i2rY", size = FingerprintSize.COMPACT, trailing = "Pinned 12 Mar 2026", copyable = false)
            FingerprintPair(
                "ecdsa-sha2-nistp256", "SHA256:nThbRk2mXJvF3e8pQz1LYw7cDh0KsVa4Tq9NoM6uGf5BwE+i2rY", "12 Mar 2026",
                "ssh-ed25519", "SHA256:p7VqZc0MhR3kWy8nUe2BtL6jXa9FdK1sGm4HrO5wNi7CbQ3zE0v", null,
            )
        }
    }
}
