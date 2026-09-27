// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.hostkey

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.data.Auth
import com.github.dennisklein.sshovel.data.HostKey
import com.github.dennisklein.sshovel.data.HostKeyInfo
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.data.Server
import com.github.dennisklein.sshovel.keys.SshKeys
import com.github.dennisklein.sshovel.tunnel.Codes
import com.github.dennisklein.sshovel.ui.components.FingerprintBlock
import com.github.dennisklein.sshovel.ui.components.FingerprintSize
import com.github.dennisklein.sshovel.ui.components.SymbolIcon
import com.github.dennisklein.sshovel.ui.format.errorText
import com.github.dennisklein.sshovel.ui.format.monoArg
import com.github.dennisklein.sshovel.ui.format.monoWords
import com.github.dennisklein.sshovel.ui.screens.home.VerifyUi
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme

/**
 * Host key first use (DESIGN_BRIEF §5.5, handoff S3): who sent which key, the grouped
 * fingerprint with copy, how to check it on the server, and "Trust this server" / "Cancel". The
 * content scrolls at 200 % font; the buttons stay.
 */
@Composable
fun HostKeyDialog(verify: VerifyUi, onTrust: () -> Unit, onCancel: () -> Unit) {
    val hostPort = "${verify.profile.server.host}:${verify.profile.server.port}"
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { SymbolIcon(R.drawable.ic_fingerprint, tint = MaterialTheme.colorScheme.secondary) },
        title = { Text(stringResource(R.string.verify_server)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (verify) {
                    is VerifyUi.Loading -> {
                        Text(stringResource(R.string.connecting_identity), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is VerifyUi.Ready -> {
                        Text(
                            monoWords(stringResource(R.string.verify_sent_key, hostPort, SshKeys.displayType(verify.key.type)), hostPort),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FingerprintBlock(verify.key.type, verify.key.fingerprint, size = FingerprintSize.COMPACT)
                        Text(monoArg(R.string.verify_compare, "ssh-keygen -lf ${SshKeys.hostKeyFile(verify.key.type)}"))
                    }
                    is VerifyUi.Failed -> {
                        val (title, body) = errorText(LocalContext.current, verify.code, verify.profile)
                        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
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

// ---- Previews ------------------------------------------------------------------

private val previewProfile = Profile(
    id = "p", name = "Lab", server = Server("lab.example.net", 22, "alice"),
    auth = Auth(Auth.KEYSTORE, "k"), routes = listOf("10.0.0.0/8"),
    hostKey = HostKey("ecdsa-sha2-nistp256", "SHA256:nThbRk2mXJvF3e8pQz1LYw7cDh0KsVa4Tq9NoM6uGf5", "2026-03-12T10:00:00Z"),
)
private val previewKey = HostKeyInfo("ssh-ed25519", "SHA256:Jc8Wq0TnLp4xHs2FaZ7mEv1RuK9dOb3YwN6tGi5QfX0eMh8SyD4")

@Preview(name = "S3 Verify") @Preview(name = "S3 Verify dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewVerify() = SshovelTheme(dynamicColor = false) {
    HostKeyDialog(VerifyUi.Ready(previewProfile, previewKey), {}, {})
}

@Preview(name = "S3 Verify loading") @Preview(name = "S3 Verify loading dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewVerifyLoading() = SshovelTheme(dynamicColor = false) {
    HostKeyDialog(VerifyUi.Loading(previewProfile), {}, {})
}

@Preview(name = "S3 Verify failed") @Preview(name = "S3 Verify failed dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewVerifyFailed() = SshovelTheme(dynamicColor = false) {
    HostKeyDialog(VerifyUi.Failed(previewProfile, Codes.HOST_UNREACHABLE), {}, {})
}
