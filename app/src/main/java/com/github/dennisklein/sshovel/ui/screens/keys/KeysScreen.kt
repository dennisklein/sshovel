// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.keys

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.data.Auth
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.data.Server
import com.github.dennisklein.sshovel.keys.KeyEntry
import com.github.dennisklein.sshovel.ui.components.KeyRow
import com.github.dennisklein.sshovel.ui.components.MaxWidth
import com.github.dennisklein.sshovel.ui.components.SymbolIcon
import com.github.dennisklein.sshovel.ui.format.formatDate
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme

/** Keys (DESIGN_BRIEF §5.6, handoff K1). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(
    ui: KeysUi,
    onBack: () -> Unit,
    onOpen: (KeyEntry) -> Unit,
    onCreate: () -> Unit,
    onImport: () -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.keys_title)) },
                navigationIcon = { IconButton(onBack) { SymbolIcon(R.drawable.ic_arrow_back, stringResource(R.string.cd_back)) } },
                actions = { IconButton(onImport) { SymbolIcon(R.drawable.ic_file_open, stringResource(R.string.cd_import_key)) } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onCreate, icon = { SymbolIcon(R.drawable.ic_add) }, text = { Text(stringResource(R.string.create_key)) })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        MaxWidth(Modifier.padding(padding)) {
            if (ui.loaded && ui.keys.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.keys_empty_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Text(
                        stringResource(R.string.keys_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)) {
                    items(ui.keys, key = { it.id }) { k ->
                        val users = ui.usedBy[k.id].orEmpty().map { it.name }
                        val meta = resources.getString(
                            R.string.key_row_meta,
                            formatDate(k.createdAt).orEmpty(),
                            if (users.isEmpty()) resources.getString(R.string.key_not_used) else users.joinToString(", "),
                        )
                        KeyRow(k, keyTypeLine(context, k), meta) { onOpen(k) }
                    }
                }
            }
        }
    }
}

// ---- Previews ------------------------------------------------------------------------

internal val previewKeys = listOf(
    KeyEntry("k1", "Pixel StrongBox", KeyEntry.KEYSTORE, "ecdsa-sha2-nistp256", "SHA256:4xQkLr8Zm2TnWc5JhY0bPq7VsE3uNd1KoA6fGi9RtM2w",
        "restrict,port-forwarding ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBHk3Pj8fWm0q9sX2c7Qe1RtLbNf4aYh0uVwKz6JdE5gSxL2vN8pQw0mR3tY6uI9oP1aS4dF7gH0jK= pixel-strongbox@sshovel",
        "pkix", KeyEntry.STRONGBOX, "2026-03-12T10:00:00Z"),
    KeyEntry("k2", "Work profile key", KeyEntry.KEYSTORE, "ecdsa-sha2-nistp256", "SHA256:x", "ecdsa-sha2-nistp256 AAAA", "pkix", KeyEntry.TEE, "2026-02-02T10:00:00Z"),
    KeyEntry("k3", "laptop-import", KeyEntry.IMPORTED, "ssh-ed25519", "SHA256:y", "ssh-ed25519 AAAA", null, KeyEntry.VAULT, "2026-01-20T10:00:00Z"),
)
internal val previewUsers = mapOf(
    "k1" to listOf(
        Profile("office", "Office", Server("jump.corp.example", 22, "alex"), Auth(Auth.KEYSTORE, "k1"), routes = listOf("10.20.0.0/16")),
        Profile("lab", "Lab", Server("lab.example.net", 22, "alex"), Auth(Auth.KEYSTORE, "k1"), routes = listOf("10.40.0.0/16")),
    ),
    "k3" to listOf(Profile("home", "Home lab", Server("home.example.org", 22, "pi"), Auth(Auth.IMPORTED, "k3"), routes = listOf("192.168.1.0/24"))),
)

@Preview(name = "K1 Keys") @Preview(name = "K1 Keys dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewKeys() = SshovelTheme(dynamicColor = false) {
    KeysScreen(KeysUi(loaded = true, keys = previewKeys, usedBy = previewUsers), {}, {}, {}, {})
}

@Preview(name = "K1 Empty") @Preview(name = "K1 Empty dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewKeysEmpty() = SshovelTheme(dynamicColor = false) {
    KeysScreen(KeysUi(loaded = true), {}, {}, {}, {})
}
