// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.data.AppSettings
import com.github.dennisklein.sshovel.data.Auth
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.data.Server
import com.github.dennisklein.sshovel.ui.components.MaxWidth
import com.github.dennisklein.sshovel.ui.components.SectionHeader
import com.github.dennisklein.sshovel.ui.components.SettingsRow
import com.github.dennisklein.sshovel.ui.components.SymbolIcon
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme

data class SettingsActions(
    val onBack: () -> Unit = {},
    val onDefault: (String) -> Unit = {},
    val onRequireUnlock: (Boolean) -> Unit = {},
    val onVpnSettings: () -> Unit = {},
    val onNotifications: () -> Unit = {},
    val onTheme: (String) -> Unit = {},
    val onWallpaper: (Boolean) -> Unit = {},
    val onRunSetup: (step: Int) -> Unit = {},
    val onAbout: () -> Unit = {},
    val onLicenses: () -> Unit = {},
)

/** Settings (DESIGN_BRIEF §5.10, handoff G5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(ui: SettingsUi, version: String, actions: SettingsActions) {
    var pickDefault by rememberSaveable { mutableStateOf(false) }
    var pickStep by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconButton(actions.onBack) { SymbolIcon(R.drawable.ic_arrow_back, stringResource(R.string.cd_back)) } },
            )
        },
    ) { padding ->
        MaxWidth(Modifier.padding(padding)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                SectionHeader(stringResource(R.string.settings_tunnel), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                SettingsRow(
                    R.drawable.ic_star,
                    stringResource(R.string.settings_default_profile),
                    ui.defaultProfile?.let { stringResource(R.string.settings_default_profile_sub, it.name) },
                    enabled = ui.profiles.isNotEmpty(),
                    onClick = { pickDefault = true },
                )
                SettingsRow(
                    R.drawable.ic_lock,
                    stringResource(R.string.settings_require_unlock),
                    stringResource(R.string.settings_require_unlock_sub),
                    checked = ui.settings.requireUnlock,
                    onCheckedChange = actions.onRequireUnlock,
                )
                SettingsRow(
                    R.drawable.ic_shield_lock,
                    stringResource(R.string.settings_always_on),
                    stringResource(R.string.settings_always_on_sub),
                    trailingIcon = R.drawable.ic_open_in_new,
                    onClick = actions.onVpnSettings,
                )
                SettingsRow(R.drawable.ic_notifications, stringResource(R.string.settings_notifications), trailingIcon = R.drawable.ic_open_in_new, onClick = actions.onNotifications)
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SectionHeader(stringResource(R.string.settings_appearance), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
                    val themes = listOf(AppSettings.THEME_SYSTEM to R.string.theme_system, AppSettings.THEME_LIGHT to R.string.theme_light, AppSettings.THEME_DARK to R.string.theme_dark)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        themes.forEachIndexed { i, (value, label) ->
                            SegmentedButton(ui.settings.theme == value, { actions.onTheme(value) }, SegmentedButtonDefaults.itemShape(i, themes.size)) {
                                Text(stringResource(label))
                            }
                        }
                    }
                }
                SettingsRow(
                    R.drawable.ic_palette,
                    stringResource(R.string.settings_wallpaper),
                    stringResource(R.string.settings_wallpaper_sub),
                    checked = ui.settings.wallpaperColors,
                    onCheckedChange = actions.onWallpaper,
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(R.drawable.ic_restart_alt, stringResource(R.string.settings_rerun), onClick = { pickStep = true })
                SettingsRow(R.drawable.ic_info, stringResource(R.string.settings_about), version, onClick = actions.onAbout)
                SettingsRow(R.drawable.ic_description, stringResource(R.string.settings_licenses), onClick = actions.onLicenses)
            }
        }
    }
    if (pickDefault) {
        ChoiceDialog(
            title = stringResource(R.string.settings_default_profile),
            options = ui.profiles.map { it.id to it.name },
            selected = ui.defaultProfile?.id,
            onPick = { actions.onDefault(it); pickDefault = false },
            onDismiss = { pickDefault = false },
        )
    }
    if (pickStep) {
        val steps = listOf(
            R.string.onb_step_welcome, R.string.onb_key_title, R.string.onb_install_title, R.string.onb_server_title,
            R.string.onb_verify_title, R.string.onb_tile_title, R.string.onb_done_title,
        )
        ChoiceDialog(
            title = stringResource(R.string.settings_rerun),
            options = steps.mapIndexed { i, id -> (i + 1).toString() to "${i + 1}. " + stringResource(id) },
            selected = null,
            onPick = { actions.onRunSetup(it.toInt()); pickStep = false },
            onDismiss = { pickStep = false },
        )
    }
}

/** A radio list in an AlertDialog (default profile picker; "Run setup again" steps). */
@Composable
private fun ChoiceDialog(title: String, options: List<Pair<String, String>>, selected: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (id, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(id == selected, role = Role.RadioButton) { onPick(id) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(id == selected, null)
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}


@Preview(name = "G5 Settings", heightDp = 1000) @Preview(name = "G5 Settings dark", heightDp = 1000, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewSettings() = SshovelTheme(dynamicColor = false) {
    val office = Profile("office", "Office", Server("jump.corp.example", 22, "alex"), Auth(Auth.KEYSTORE, "k"), routes = listOf("10.20.0.0/16"))
    SettingsScreen(SettingsUi(AppSettings(requireUnlock = true), listOf(office), office), "Version 1.0.0 (42)", SettingsActions())
}

@Preview(name = "G5 Default profile") @Preview(name = "G5 Default profile dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewDefaultPicker() = SshovelTheme(dynamicColor = false) {
    ChoiceDialog("Default profile", listOf("office" to "Office", "lab" to "Lab", "home" to "Home lab"), "office", {}, {})
}
