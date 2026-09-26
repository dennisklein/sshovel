// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.hostkey

import android.content.ClipData
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.data.Auth
import com.github.dennisklein.sshovel.data.HostKey
import com.github.dennisklein.sshovel.data.HostKeyInfo
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.data.Server
import com.github.dennisklein.sshovel.keys.SshKeys
import com.github.dennisklein.sshovel.tunnel.Codes
import com.github.dennisklein.sshovel.ui.format.errorText
import com.github.dennisklein.sshovel.ui.home.VerifyUi
import com.github.dennisklein.sshovel.ui.theme.MonoFamily
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme
import kotlinx.coroutines.launch

/**
 * Host key first use (DESIGN_BRIEF §5.5, handoff S3): who sent which key, the grouped
 * fingerprint, how to check it on the server, and "Trust this server" / "Cancel".
 * M3 builds it from plain Material 3 parts; M6 applies the handoff's full layout.
 */
@Composable
fun HostKeyDialog(verify: VerifyUi, onTrust: () -> Unit, onCancel: () -> Unit) {
    val hostPort = "${verify.profile.server.host}:${verify.profile.server.port}"
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.verify_server)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (verify) {
                    is VerifyUi.Loading -> {
                        Text(stringResource(R.string.connecting_identity))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is VerifyUi.Ready -> {
                        Text(monoArg(R.string.verify_sent_key, hostPort, SshKeys.displayType(verify.key.type)))
                        Fingerprint(verify.key.fingerprint)
                        Text(
                            monoArg(R.string.verify_compare, "ssh-keygen -lf ${SshKeys.hostKeyFile(verify.key.type)}"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    is VerifyUi.Failed -> {
                        val (title, body) = errorText(LocalContext.current, verify.code, verify.profile)
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(body)
                    }
                }
            }
        },
        confirmButton = {
            if (verify is VerifyUi.Ready) TextButton(onTrust) { Text(stringResource(R.string.action_trust_server)) }
        },
        dismissButton = { TextButton(onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Host key mismatch details (handoff S4): trusted vs received fingerprint and what to do. There
 * is no accept action; the full-screen, non-dismissible version arrives in M6.
 */
@Composable
fun HostKeyMismatch(pinned: HostKey?, received: HostKeyInfo?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (pinned != null) {
            Text(
                "${stringResource(R.string.mismatch_trusted)} · ${SshKeys.displayType(pinned.type)}",
                style = MaterialTheme.typography.labelLarge,
            )
            Fingerprint(pinned.fingerprint, copy = false)
        }
        if (received != null) {
            Text(
                "${stringResource(R.string.mismatch_received)} · ${SshKeys.displayType(received.type)}",
                style = MaterialTheme.typography.labelLarge,
            )
            Fingerprint(received.fingerprint, copy = false)
        }
        Text(stringResource(R.string.mismatch_explain), style = MaterialTheme.typography.bodyMedium)
    }
}

/** A SHA-256 fingerprint in groups of four, monospace, optionally with a copy button. */
@Composable
fun Fingerprint(fingerprint: String, copy: Boolean = true) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            SshKeys.groupFingerprint(fingerprint),
            fontFamily = MonoFamily,
            style = MaterialTheme.typography.titleMedium,
            letterSpacing = 0.5.sp,
            modifier = Modifier.weight(1f),
        )
        if (copy) {
            IconButton({
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("fingerprint", fingerprint))) }
            }) {
                Icon(painterResource(R.drawable.ic_content_copy), stringResource(R.string.cd_copy_fingerprint))
            }
        }
    }
}

/** A string resource whose arguments (hosts, commands) are set in monospace (DESIGN_BRIEF §8). */
@Composable
private fun monoArg(id: Int, vararg args: String): AnnotatedString {
    val marker = "\u0000"
    val template = stringResource(id, *Array(args.size) { "$marker$it$marker" })
    return buildAnnotatedString {
        template.split(marker).forEachIndexed { i, part ->
            val arg = if (i % 2 == 1) part.toIntOrNull()?.let { args.getOrNull(it) } else null
            if (arg != null) withStyle(SpanStyle(fontFamily = MonoFamily)) { append(arg) } else append(part)
        }
    }
}

// ---- Previews ------------------------------------------------------------------

private val previewProfile = Profile(
    id = "p", name = "Lab", server = Server("lab.example.net", 22, "alice"),
    auth = Auth(Auth.KEYSTORE, "k"), routes = listOf("10.0.0.0/8"),
    hostKey = HostKey("ecdsa-sha2-nistp256", "SHA256:nThbRk2mXJvF3e8pQz1LYw7cDh0KsVa4Tq9NoM6uGf5", "2026-03-12T10:00:00Z"),
)
private val previewKey = HostKeyInfo("ssh-ed25519", "SHA256:Jc8Wq0TnLp4xHs2FaZ7mEv1RuK9dOb3YwN6tGi5QfX0")

@Preview(name = "Verify") @Preview(name = "Verify dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewVerify() = SshovelTheme(dynamicColor = false) {
    HostKeyDialog(VerifyUi.Ready(previewProfile, previewKey), {}, {})
}

@Preview(name = "Verify loading") @Preview(name = "Verify loading dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewVerifyLoading() = SshovelTheme(dynamicColor = false) {
    HostKeyDialog(VerifyUi.Loading(previewProfile), {}, {})
}

@Preview(name = "Verify failed") @Preview(name = "Verify failed dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewVerifyFailed() = SshovelTheme(dynamicColor = false) {
    HostKeyDialog(VerifyUi.Failed(previewProfile, Codes.HOST_UNREACHABLE), {}, {})
}

@Preview(name = "Mismatch", showBackground = true)
@Preview(name = "Mismatch dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewMismatch() = SshovelTheme(dynamicColor = false) {
    HostKeyMismatch(previewProfile.hostKey, previewKey)
}
