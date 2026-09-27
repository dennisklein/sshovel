// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.ui.components.IconTile
import com.github.dennisklein.sshovel.ui.components.LogoIcon
import com.github.dennisklein.sshovel.ui.components.MaxWidth
import com.github.dennisklein.sshovel.ui.components.SettingsRow
import com.github.dennisklein.sshovel.ui.components.SymbolIcon
import com.github.dennisklein.sshovel.ui.components.mono
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme

/**
 * Settings → About: the GPL's Appropriate Legal Notices (ARCHITECTURE §12 item 3): name,
 * version, copyright, the free-software / no-warranty statement, the license (rendered in-app),
 * and the source for this exact build. The handoff has no About screen; this one is built from
 * its list rows and body text. Don't remove or weaken any of it (CLAUDE.md, Licensing).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    version: String,
    goVersion: String,
    sourceUrl: String,
    onBack: () -> Unit,
    onLicense: () -> Unit,
    onSource: () -> Unit,
    onLicenses: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = { IconButton(onBack) { SymbolIcon(R.drawable.ic_arrow_back, stringResource(R.string.cd_back)) } },
            )
        },
    ) { padding ->
        MaxWidth(Modifier.padding(padding)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val cs = MaterialTheme.colorScheme
                    IconTile(LogoIcon, cs.primaryContainer, cs.onPrimaryContainer, iconSize = 40.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                        Text(version, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        Text(stringResource(R.string.about_go_core, goVersion), style = MaterialTheme.typography.bodySmall.mono(), color = cs.onSurfaceVariant)
                    }
                    Text(stringResource(R.string.about_copyright), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.about_free_software), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(R.drawable.ic_description, stringResource(R.string.about_view_license), stringResource(R.string.about_view_license_sub), onClick = onLicense)
                SettingsRow(R.drawable.ic_terminal, stringResource(R.string.about_source), sourceUrl, trailingIcon = R.drawable.ic_open_in_new, onClick = onSource)
                SettingsRow(R.drawable.ic_description, stringResource(R.string.settings_licenses), onClick = onLicenses)
            }
        }
    }
}

@Preview(name = "About") @Preview(name = "About dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewAbout() = SshovelTheme(dynamicColor = false) {
    AboutScreen("Version 0.6.0 (1)", "0.6.0 (go1.26.3)", "https://github.com/dennisklein/sshovel/tree/v0.6.0", {}, {}, {}, {})
}
