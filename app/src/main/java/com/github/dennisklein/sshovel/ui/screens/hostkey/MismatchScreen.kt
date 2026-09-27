// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.hostkey

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.data.Auth
import com.github.dennisklein.sshovel.data.HostKey
import com.github.dennisklein.sshovel.data.HostKeyInfo
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.data.Server
import com.github.dennisklein.sshovel.ui.components.FingerprintPair
import com.github.dennisklein.sshovel.ui.components.MaxWidth
import com.github.dennisklein.sshovel.ui.components.SymbolIcon
import com.github.dennisklein.sshovel.ui.format.formatDate
import com.github.dennisklein.sshovel.ui.format.monoArg
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme

/**
 * Host key mismatch (DESIGN_BRIEF §5.5, handoff S4): full screen, no app bar, no close, no scrim
 * to tap. The only actions are "Disconnect" and "Review in profile"; the back gesture is
 * "Disconnect". There is no way to accept the received key here (CLAUDE.md, Host keys): the path
 * to a new key is Forget pinned key (P7) → verify (S3) in the profile editor.
 */
@Composable
fun MismatchScreen(profile: Profile, received: HostKeyInfo?, onDisconnect: () -> Unit, onReview: () -> Unit) {
    BackHandler(onBack = onDisconnect)
    Surface(Modifier.fillMaxSize().testTag("mismatch")) {
        MaxWidth(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, end = 24.dp, top = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Surface(Modifier.size(72.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer) {
                        Box(contentAlignment = Alignment.Center) { SymbolIcon(R.drawable.ic_gpp_bad_filled, size = 40.dp) }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.err_mismatch_title), style = MaterialTheme.typography.headlineMedium)
                        Text(
                            monoArg(R.string.err_mismatch_body, "${profile.server.host}:${profile.server.port}"),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FingerprintPair(
                        profile.hostKey?.type, profile.hostKey?.fingerprint, formatDate(profile.hostKey?.pinnedAt),
                        received?.type, received?.fingerprint, null,
                    )
                    Text(stringResource(R.string.mismatch_explain), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onDisconnect,
                        Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    ) { Text(stringResource(R.string.err_mismatch_action), style = MaterialTheme.typography.titleMedium) }
                    TextButton(onReview, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.review_in_profile)) }
                }
            }
        }
    }
}

@Preview(name = "S4 Mismatch") @Preview(name = "S4 Mismatch dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewMismatch() = SshovelTheme(dynamicColor = false) {
    MismatchScreen(
        Profile(
            id = "office", name = "Office", server = Server("jump.corp.example", 22, "alex"),
            auth = Auth(Auth.KEYSTORE, "k"), routes = listOf("10.20.0.0/16"),
            hostKey = HostKey("ecdsa-sha2-nistp256", "SHA256:nThbRk2mXJvF3e8pQz1LYw7cDh0KsVa4Tq9NoM6uGf5BwE+i2rY", "2026-03-12T10:00:00Z"),
        ),
        HostKeyInfo("ssh-ed25519", "SHA256:p7VqZc0MhR3kWy8nUe2BtL6jXa9FdK1sGm4HrO5wNi7CbQ3zE0v"),
        {}, {},
    )
}
