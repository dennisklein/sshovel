// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.settings

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.ui.components.MaxWidth
import com.github.dennisklein.sshovel.ui.components.SectionHeader
import com.github.dennisklein.sshovel.ui.components.SymbolIcon
import com.github.dennisklein.sshovel.ui.components.clearListColors
import com.github.dennisklein.sshovel.ui.components.mono
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One Go module's license, as collectGoLicenses writes it (go_licenses.json). */
@Serializable
data class GoLicense(val module: String, val url: String = "", val license: String, val text: String)

/** A bundled font or icon set with its license text asset. */
data class AssetLicense(val name: Int, val copyright: String, val license: String, val asset: String)

private val licenseJson = Json { ignoreUnknownKeys = true }

private val assetLicenses = listOf(
    AssetLicense(R.string.licenses_roboto_mono, "Copyright 2015 The Roboto Mono Project Authors", "OFL-1.1", "licenses/OFL-1.1.txt"),
    AssetLicense(R.string.licenses_material_symbols, "Copyright Google LLC", "Apache-2.0", "licenses/Apache-2.0.txt"),
)

/**
 * Open-source licenses (ARCHITECTURE §12 item 5): every Android library (AboutLibraries, its
 * Compose UI in our theme), then every Go module of the core including Go itself
 * (go-licenses), then the bundled font and icons. Each opens its full license text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceLicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val libraries by produceLibraries(R.raw.aboutlibraries)
    val go by produceState<List<GoLicense>>(emptyList()) {
        value = runCatching {
            licenseJson.decodeFromString(ListSerializer(GoLicense.serializer()), context.readAsset("go_licenses.json"))
        }.getOrDefault(emptyList())
    }
    var shown by remember { mutableStateOf<Pair<String, String>?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_licenses)) },
                navigationIcon = { IconButton(onBack) { SymbolIcon(R.drawable.ic_arrow_back, stringResource(R.string.cd_back)) } },
            )
        },
    ) { padding ->
        MaxWidth(Modifier.padding(padding)) {
            LibrariesContainer(
                libraries = libraries,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                header = { sectionHeader(R.string.licenses_android) },
                footer = {
                    sectionHeader(R.string.licenses_go)
                    items(go, key = { "go-" + it.module }) { m ->
                        LicenseRow(m.module, m.license) { shown = m.module to m.text }
                    }
                    sectionHeader(R.string.licenses_assets)
                    items(assetLicenses, key = { "asset-" + it.asset }) { a ->
                        val name = stringResource(a.name)
                        val copyright = a.copyright
                        LicenseRow(name, a.license) {
                            shown = name to (copyright + "\n\n" + runCatching { context.assets.open(a.asset).use { it.readBytes().decodeToString() } }.getOrDefault(""))
                        }
                    }
                },
            )
        }
    }
    shown?.let { (title, text) -> LicenseTextDialog(title, text) { shown = null } }
}

private fun LazyListScope.sectionHeader(id: Int) {
    item(key = "header-$id") { SectionHeader(stringResource(id), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) }
}

@Composable
private fun LicenseRow(name: String, license: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(name, style = MaterialTheme.typography.bodyLarge.mono()) },
        supportingContent = { Text(license) },
        colors = clearListColors(),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun LicenseTextDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge.mono()) },
        text = { Text(text, style = MaterialTheme.typography.bodySmall.mono(), modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

@Preview(name = "License text") @Preview(name = "License text dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewLicenseText() = SshovelTheme(dynamicColor = false) {
    LicenseTextDialog("golang.org/x/crypto", "Copyright 2009 The Go Authors.\n\nRedistribution and use in source and binary forms, with or without modification, are permitted…") {}
}
