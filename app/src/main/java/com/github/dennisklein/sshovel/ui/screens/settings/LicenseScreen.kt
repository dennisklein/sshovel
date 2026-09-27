// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.settings

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.ui.components.MaxWidth
import com.github.dennisklein.sshovel.ui.components.SymbolIcon
import com.github.dennisklein.sshovel.ui.components.mono
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads a bundled text asset (the GPL text is built into assets by the licenseAsset task). */
suspend fun Context.readAsset(name: String): String = withContext(Dispatchers.IO) {
    assets.open(name).use { it.readBytes().decodeToString() }
}

/** A long text (license) in paragraphs, monospace so the FSF layout survives. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextDocumentScreen(title: String, text: String?, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onBack) { SymbolIcon(R.drawable.ic_arrow_back, stringResource(R.string.cd_back)) } },
            )
        },
    ) { padding ->
        MaxWidth(Modifier.padding(padding)) {
            val paragraphs = text.orEmpty().split(Regex("\n\\s*\n"))
            LazyColumn(Modifier.testTag("license-text"), contentPadding = PaddingValues(16.dp)) {
                items(paragraphs) { p ->
                    Text(p.trimEnd(), style = MaterialTheme.typography.bodySmall.mono(), modifier = Modifier.padding(bottom = 12.dp))
                }
            }
        }
    }
}

/** Settings → About → View license: the full GPL-3.0 text, bundled with the app. */
@Composable
fun LicenseScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val text by produceState<String?>(null) { value = runCatching { context.readAsset("LICENSE") }.getOrNull() }
    TextDocumentScreen(stringResource(R.string.license_title), text, onBack)
}

@Preview(name = "License") @Preview(name = "License dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewLicense() = SshovelTheme(dynamicColor = false) {
    TextDocumentScreen(
        "License",
        "                    GNU GENERAL PUBLIC LICENSE\n                       Version 3, 29 June 2007\n\n Copyright (C) 2007 Free Software Foundation, Inc. <https://fsf.org/>",
    ) {}
}
