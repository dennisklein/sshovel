// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.profile

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.data.AppInfo
import com.github.dennisklein.sshovel.data.Apps
import com.github.dennisklein.sshovel.ui.components.AppRow
import com.github.dennisklein.sshovel.ui.components.MaxWidth
import com.github.dennisklein.sshovel.ui.components.SymbolIcon
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme

/**
 * App picker (DESIGN_BRIEF §5.7, handoff A1). Changes apply to the editor's draft as they are
 * made; there is no Save here. sshovel itself is never listed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerScreen(
    mode: String,
    apps: List<AppInfo>?,
    selected: List<String>,
    onChange: (List<String>) -> Unit,
    onBack: () -> Unit,
    icon: (String) -> Painter? = { null },
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (mode == Apps.EXCLUDE) R.string.apps_except_title else R.string.apps_only_title)) },
                navigationIcon = { IconButton(onBack) { SymbolIcon(R.drawable.ic_arrow_back, stringResource(R.string.cd_back)) } },
            )
        },
    ) { padding ->
        MaxWidth(Modifier.padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                SearchBar(
                    inputField = {
                        SearchBarDefaults.InputField(
                            query = query,
                            onQueryChange = { query = it },
                            onSearch = {},
                            expanded = false,
                            onExpandedChange = {},
                            placeholder = { Text(stringResource(R.string.search_apps)) },
                            leadingIcon = { SymbolIcon(R.drawable.ic_search) },
                        )
                    },
                    expanded = false,
                    onExpandedChange = {},
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {}
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(showSystem, role = Role.Switch) { showSystem = it }
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.show_system_apps), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(showSystem, null)
                }
                val visible = apps.orEmpty().filter { (showSystem || !it.isSystem || it.packageName in selected) && it.matches(query) }
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.apps_selected, selected.size, apps.orEmpty().count { showSystem || !it.isSystem }),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton({ onChange((selected + visible.map { it.packageName }).distinct()) }) { Text(stringResource(R.string.select_all)) }
                    TextButton({ onChange(emptyList()) }) { Text(stringResource(R.string.clear)) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                when {
                    apps == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    visible.isEmpty() && query.isNotBlank() -> Text(
                        stringResource(R.string.apps_no_match, query),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                    else -> LazyColumn {
                        items(visible, key = { it.packageName }) { app ->
                            val checked = app.packageName in selected
                            AppRow(app.label, app.packageName, icon(app.packageName), checked) { on ->
                                onChange(if (on) selected + app.packageName else selected - app.packageName)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun AppInfo.matches(q: String): Boolean =
    q.isBlank() || label.contains(q.trim(), ignoreCase = true) || packageName.contains(q.trim(), ignoreCase = true)

/** App icons from PackageManager, 40 dp, cached for the picker's lifetime. */
@Composable
fun rememberAppIcons(load: (String) -> android.graphics.drawable.Drawable?): (String) -> Painter? {
    val cache = remember { mutableMapOf<String, Painter?>() }
    val sizePx = with(androidx.compose.ui.platform.LocalDensity.current) { 40.dp.roundToPx() }
    return { pkg -> cache.getOrPut(pkg) { load(pkg)?.let { BitmapPainter(it.render(sizePx).asImageBitmap()) } } }
}

private fun android.graphics.drawable.Drawable.render(px: Int): android.graphics.Bitmap {
    val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    setBounds(0, 0, px, px)
    draw(canvas)
    return bmp
}

@Preview(name = "A1 App picker") @Preview(name = "A1 App picker dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewPicker() = SshovelTheme(dynamicColor = false) {
    AppPickerScreen(
        Apps.INCLUDE,
        listOf(
            AppInfo("com.android.chrome", "Chrome", false), AppInfo("org.mozilla.firefox", "Firefox", false),
            AppInfo("com.google.android.gm", "Gmail", false), AppInfo("io.homeassistant.companion.android", "Home Assistant", false),
            AppInfo("com.Slack", "Slack", false), AppInfo("com.termux", "Termux", false),
        ),
        listOf("com.android.chrome", "com.Slack", "com.termux"),
        {}, {},
    )
}

@Preview(name = "A1 No match") @Preview(name = "A1 No match dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewPickerEmpty() = SshovelTheme(dynamicColor = false) {
    AppPickerScreen(Apps.EXCLUDE, emptyList(), emptyList(), {}, {})
}
