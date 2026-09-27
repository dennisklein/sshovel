// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.profile

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.data.Apps
import com.github.dennisklein.sshovel.data.HostKey
import com.github.dennisklein.sshovel.keys.KeyEntry
import com.github.dennisklein.sshovel.ui.components.ActionChip
import com.github.dennisklein.sshovel.ui.components.ButtonIcon
import com.github.dennisklein.sshovel.ui.components.ChipField
import com.github.dennisklein.sshovel.ui.components.CidrInput
import com.github.dennisklein.sshovel.ui.components.FingerprintBlock
import com.github.dennisklein.sshovel.ui.components.FingerprintSize
import com.github.dennisklein.sshovel.ui.components.KeyChoiceRow
import com.github.dennisklein.sshovel.ui.components.MaxWidth
import com.github.dennisklein.sshovel.ui.components.SectionDivider
import com.github.dennisklein.sshovel.ui.components.SectionHeader
import com.github.dennisklein.sshovel.ui.components.SubnetRow
import com.github.dennisklein.sshovel.ui.components.SymbolIcon
import com.github.dennisklein.sshovel.ui.components.mono
import com.github.dennisklein.sshovel.ui.format.formatDate
import com.github.dennisklein.sshovel.ui.format.monoArg
import com.github.dennisklein.sshovel.ui.screens.hostkey.HostKeyDialog
import com.github.dennisklein.sshovel.ui.screens.keys.keyTypeLine
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme
import kotlinx.coroutines.launch

/** Navigation out of the editor. */
data class EditorNav(
    val onBack: () -> Unit = {},
    val onSaved: () -> Unit = {},
    val onDeleted: () -> Unit = {},
    val onCreateKey: () -> Unit = {},
    val onImportKey: () -> Unit = {},
    val onPickApps: () -> Unit = {},
)

/** Wires [ProfileEditorScreen] to its ViewModel and one-off events. */
@Composable
fun ProfileEditorRoute(vm: ProfileEditorViewModel, nav: EditorNav, focus: String?) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val targets = remember { FocusTargets() }
    val scope = rememberCoroutineScope()
    val dirtyBack = ui.loaded && ui.dirty && !ui.readOnly
    // Predictive back is intercepted only while there are unsaved changes (handoff P5).
    BackHandler(enabled = dirtyBack) { vm.requestLeave() }
    LaunchedEffect(ui.loaded, focus) {
        if (ui.loaded && focus != null) targets.focus(focus, vm)
    }
    LaunchedEffect(Unit) {
        vm.editorEvents.collect { e ->
            when (e) {
                EditorEvent.Saved -> nav.onSaved()
                EditorEvent.Deleted -> nav.onDeleted()
                is EditorEvent.FixProblems -> scope.launch {
                    val r = snackbar.showSnackbar(
                        resources.getQuantityString(R.plurals.fix_problems, e.count, e.count),
                        actionLabel = resources.getString(R.string.show_first),
                    )
                    if (r == SnackbarResult.ActionPerformed) targets.focus(e.firstField, vm)
                }
                is EditorEvent.Focus -> targets.focus(e.field, vm)
                is EditorEvent.SubnetRemoved -> scope.launch {
                    val r = snackbar.showSnackbar(resources.getString(R.string.subnet_removed), actionLabel = resources.getString(R.string.action_undo))
                    if (r == SnackbarResult.ActionPerformed) vm.undoRemove(e.cidr, e.excluded)
                }
                EditorEvent.Forgotten -> scope.launch { snackbar.showSnackbar(resources.getString(R.string.forgot_done)) }
                EditorEvent.Trusted -> scope.launch { snackbar.showSnackbar(resources.getString(R.string.server_trusted)) }
                EditorEvent.IdentityMatches -> scope.launch { snackbar.showSnackbar(resources.getString(R.string.identity_matches)) }
            }
        }
    }
    ProfileEditorScreen(
        ui,
        EditorActions(
            onBack = { if (vm.requestLeave()) nav.onBack() },
            onSave = vm::save,
            edit = vm::edit,
            touched = vm::touched,
            onSubnetInput = vm::setSubnetInput,
            onAddSubnet = vm::addSubnet,
            onExcludedInput = vm::setExcludedInput,
            onAddExcluded = vm::addExcluded,
            onRemoveSubnet = vm::removeSubnet,
            onAddDomain = { suffix, v -> vm.addDomain(suffix, v)?.text(context) },
            onRemoveDomain = vm::removeDomain,
            onToggleExcluded = vm::toggleExcluded,
            onToggleAdvanced = vm::toggleAdvanced,
            onAppsMode = vm::setAppsMode,
            onPickApps = nav.onPickApps,
            onCreateKey = nav.onCreateKey,
            onImportKey = nav.onImportKey,
            onVerify = vm::startVerify,
            onForget = vm::requestForget,
            onDiscover = vm::discover,
            onDelete = vm::requestDelete,
            onDisconnect = vm::disconnect,
        ),
        snackbar,
        targets,
    )
    when (val d = ui.dialog) {
        is EditorDialog.Discard -> DiscardDialog(
            d.changes.text(context), ui.saved.name.ifBlank { stringResource(R.string.profile_new) },
            onDiscard = { vm.dismissDialog(); nav.onBack() }, onKeep = vm::dismissDialog,
        )
        EditorDialog.Forget -> ForgetKeyDialog(ui.draft.host, vm::forget, vm::dismissDialog)
        is EditorDialog.Delete -> DeleteProfileDialog(ui.saved.name, d.newDefault, vm::delete, vm::dismissDialog)
        null -> {}
    }
    ui.verify?.let { HostKeyDialog(it, vm::trust, vm::cancelVerify) }
    ui.discover?.let { d ->
        DiscoverSheet(
            d, host = ui.draft.host, port = ui.draft.port, existing = ui.draft.routes,
            onToggle = vm::toggleDiscovered, onAdd = vm::addDiscovered, onRetry = vm::discover,
            onAddByHand = vm::addByHand, onDismiss = vm::closeDiscover,
        )
    }
}

/** P5: leaving with unsaved changes. Back again keeps editing. */
@Composable
fun DiscardDialog(changes: String, profileName: String, onDiscard: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.discard_title)) },
        text = { Text(stringResource(R.string.discard_body, changes, profileName)) },
        confirmButton = { TextButton(onDiscard) { Text(stringResource(R.string.discard)) } },
        dismissButton = { TextButton(onKeep) { Text(stringResource(R.string.keep_editing)) } },
    )
}

/** P7: forgetting the pinned host key explains the risk; the only way to drop a pin. */
@Composable
fun ForgetKeyDialog(host: String, onForget: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { SymbolIcon(R.drawable.ic_warning, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.forget_title)) },
        text = { Text(monoArg(R.string.forget_body, host)) },
        confirmButton = {
            TextButton(onForget, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.forget_confirm))
            }
        },
        dismissButton = { TextButton(onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** P8: deleting a profile; [newDefault] names the profile that becomes the default. */
@Composable
fun DeleteProfileDialog(name: String, newDefault: String?, onDelete: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { SymbolIcon(R.drawable.ic_delete, tint = MaterialTheme.colorScheme.secondary) },
        title = { Text(stringResource(R.string.delete_profile_title, name)) },
        text = {
            val next = newDefault?.let { " " + stringResource(R.string.delete_profile_new_default, it) }.orEmpty()
            Text(stringResource(R.string.delete_profile_body) + next)
        },
        confirmButton = {
            TextButton(onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = { TextButton(onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Focus targets for "Show first" (P3) and "Add by hand" (D3). */
class FocusTargets {
    val requesters = mutableMapOf<String, FocusRequester>()
    val views = mutableMapOf<String, BringIntoViewRequester>()

    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    fun view(key: String) = views.getOrPut(key) { BringIntoViewRequester() }

    suspend fun focus(key: String, vm: ProfileEditorViewModel) {
        val u = vm.ui.value
        if (key in ADVANCED && !u.advancedExpanded) vm.toggleAdvanced()
        if (key.startsWith("excluded[") && !u.excludedExpanded) vm.toggleExcluded()
        kotlinx.coroutines.delay(50) // let an expanded section compose
        val target = when {
            key == Fields.ROUTES -> "subnetInput"
            else -> key
        }
        requesters[target]?.let { runCatching { it.requestFocus() } }
        views[target]?.bringIntoView()
    }

    private companion object {
        val ADVANCED = setOf(Fields.KEEPALIVE, Fields.TIMEOUT, Fields.TUN, Fields.MTU)
    }
}

data class EditorActions(
    val onBack: () -> Unit = {},
    val onSave: () -> Unit = {},
    val edit: (String, (ProfileDraft) -> ProfileDraft) -> Unit = { _, _ -> },
    val touched: (String) -> Unit = {},
    val onSubnetInput: (String) -> Unit = {},
    val onAddSubnet: () -> Unit = {},
    val onExcludedInput: (String) -> Unit = {},
    val onAddExcluded: () -> Unit = {},
    val onRemoveSubnet: (String, Boolean) -> Unit = { _, _ -> },
    val onAddDomain: (Boolean, String) -> String? = { _, _ -> null },
    val onRemoveDomain: (Boolean, String) -> Unit = { _, _ -> },
    val onToggleExcluded: () -> Unit = {},
    val onToggleAdvanced: () -> Unit = {},
    val onAppsMode: (String) -> Unit = {},
    val onPickApps: () -> Unit = {},
    val onCreateKey: () -> Unit = {},
    val onImportKey: () -> Unit = {},
    val onVerify: () -> Unit = {},
    val onForget: () -> Unit = {},
    val onDiscover: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
)

/**
 * Profile editor (DESIGN_BRIEF §5.3, handoff P1–P8): Server, Authentication, Server identity,
 * Subnets, DNS, Apps, Advanced. Read-only while the profile is connected (P6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(ui: EditorUi, actions: EditorActions, snackbar: SnackbarHostState, targets: FocusTargets = remember { FocusTargets() }) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(if (ui.isNew) stringResource(R.string.profile_new) else ui.saved.name) },
                navigationIcon = {
                    IconButton(actions.onBack) {
                        if (ui.isNew) SymbolIcon(R.drawable.ic_close, stringResource(R.string.cd_close))
                        else SymbolIcon(R.drawable.ic_arrow_back, stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    if (!ui.readOnly) {
                        val invalid = ui.errors.isNotEmpty()
                        // Looks disabled while invalid but still answers with "Fix n problems" (P3).
                        TextButton(actions.onSave, Modifier.alpha(if (invalid) 0.38f else 1f).testTag("save")) {
                            Text(stringResource(R.string.action_save), color = if (invalid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!ui.loaded) return@Scaffold
        MaxWidth(Modifier.padding(padding).imePadding()) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AnimatedVisibility(ui.readOnly, enter = expandVertically(tween(300)), exit = shrinkVertically(tween(300))) {
                    ReadOnlyBanner(actions.onDisconnect)
                }
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ServerSection(ui, actions, targets)
                    SectionDivider()
                    AuthSection(ui, actions, targets)
                    SectionDivider()
                    IdentitySection(ui, actions)
                    SectionDivider()
                    SubnetsSection(ui, actions, targets)
                    SectionDivider()
                    DnsSection(ui, actions, targets)
                    SectionDivider()
                    AppsSection(ui, actions, targets)
                    SectionDivider()
                    AdvancedSection(ui, actions, targets)
                    if (!ui.isNew && !ui.readOnly) {
                        OutlinedButton(
                            actions.onDelete,
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        ) {
                            ButtonIcon(R.drawable.ic_delete)
                            Text(stringResource(R.string.delete_profile))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadOnlyBanner(onDisconnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer, contentColor = cs.onSecondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SymbolIcon(R.drawable.ic_lock)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.readonly_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.readonly_body), style = MaterialTheme.typography.bodyMedium)
                }
            }
            TextButton(onDisconnect, Modifier.align(Alignment.End), colors = ButtonDefaults.textButtonColors(contentColor = cs.onSecondaryContainer)) {
                Text(stringResource(R.string.action_disconnect))
            }
        }
    }
}

/** An outlined text field wired to the draft, validation, and focus targets. */
@Composable
private fun Field(
    ui: EditorUi,
    actions: EditorActions,
    targets: FocusTargets,
    key: String,
    label: Int,
    value: String,
    onChange: (ProfileDraft, String) -> ProfileDraft,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    suffix: String? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val msg = ui.messageFor(key)
    val style: TextStyle = if (mono) MaterialTheme.typography.bodyLarge.mono() else MaterialTheme.typography.bodyLarge
    OutlinedTextField(
        value = value,
        onValueChange = { v -> actions.edit(key) { onChange(it, v) } },
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(targets.requester(key))
            .bringIntoViewRequester(targets.view(key))
            .onFocusChanged { if (!it.isFocused && value.isNotEmpty()) actions.touched(key) }
            .alpha(if (ui.readOnly) 0.38f else 1f)
            .testTag("field-$key"),
        label = { Text(stringResource(label)) },
        readOnly = ui.readOnly,
        singleLine = true,
        textStyle = style,
        isError = msg != null && !msg.warning,
        supportingText = msg?.let { { Text(it.text(context)) } },
        trailingIcon = if (msg != null && !msg.warning) ({ SymbolIcon(R.drawable.ic_error_filled, tint = MaterialTheme.colorScheme.error) }) else null,
        suffix = suffix?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
    )
}

@Composable
private fun ServerSection(ui: EditorUi, actions: EditorActions, targets: FocusTargets) {
    SectionHeader(stringResource(R.string.section_server))
    Field(ui, actions, targets, Fields.NAME, R.string.field_profile_name, ui.draft.name, { d, v -> d.copy(name = v) })
    Field(ui, actions, targets, Fields.HOST, R.string.field_host, ui.draft.host, { d, v -> d.copy(host = v) }, mono = true, keyboard = KeyboardType.Uri)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Field(ui, actions, targets, Fields.PORT, R.string.field_port, ui.draft.port, { d, v -> d.copy(port = v.filter(Char::isDigit).take(5)) }, Modifier.width(96.dp), mono = true, keyboard = KeyboardType.Number)
        Field(ui, actions, targets, Fields.USER, R.string.field_username, ui.draft.user, { d, v -> d.copy(user = v) }, Modifier.weight(1f), mono = true, keyboard = KeyboardType.Ascii)
    }
}

@Composable
private fun AuthSection(ui: EditorUi, actions: EditorActions, targets: FocusTargets) {
    val context = LocalContext.current
    val resources = LocalResources.current
    SectionHeader(stringResource(R.string.section_auth))
    Column(Modifier.padding(horizontal = 0.dp).bringIntoViewRequester(targets.view(Fields.KEY))) {
        if (ui.keys.isEmpty()) {
            Text(stringResource(R.string.key_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ui.keys.forEach { k ->
            Row(Modifier.padding(start = 0.dp)) {
                KeyChoiceRow(k, keyTypeLine(context, k), selected = ui.draft.keyId == k.id, enabled = !ui.readOnly) {
                    actions.edit(Fields.KEY) { it.copy(keyId = k.id) }
                }
            }
        }
        ui.messageFor(Fields.KEY)?.let { Text(it.text(context), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
    if (!ui.readOnly) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip(R.drawable.ic_add, stringResource(R.string.create_key), onClick = actions.onCreateKey)
            ActionChip(R.drawable.ic_file_open, stringResource(R.string.import_key), onClick = actions.onImportKey)
        }
    }
}

@Composable
private fun IdentitySection(ui: EditorUi, actions: EditorActions) {
    SectionHeader(stringResource(R.string.section_identity))
    val pin: HostKey? = ui.hostKey
    if (pin == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SymbolIcon(R.drawable.ic_help, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.identity_unverified), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!ui.isNew) OutlinedButton(actions.onVerify) { Text(stringResource(R.string.verify_now)) }
    } else {
        FingerprintBlock(
            pin.type, pin.fingerprint,
            size = FingerprintSize.COMPACT,
            trailing = formatDate(pin.pinnedAt)?.let { stringResource(R.string.identity_pinned, it) },
            copyable = false,
            modifier = Modifier.testTag("pinned-fingerprint"),
        ) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(actions.onVerify) { Text(stringResource(R.string.verify_now)) }
                if (!ui.readOnly) {
                    TextButton(actions.onForget, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text(stringResource(R.string.forget_pinned_key))
                    }
                }
            }
        }
    }
}

@Composable
private fun SubnetsSection(ui: EditorUi, actions: EditorActions, targets: FocusTargets) {
    val context = LocalContext.current
    val resources = LocalResources.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionHeader(stringResource(R.string.section_subnets), Modifier.weight(1f))
        if (!ui.readOnly && !ui.isNew) {
            ActionChip(R.drawable.ic_travel_explore, stringResource(R.string.discover_from_server), enabled = ui.hostKey != null, onClick = actions.onDiscover)
        }
    }
    if (!ui.readOnly && !ui.isNew && ui.hostKey == null) {
        Text(stringResource(R.string.discover_needs_verify), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Column(Modifier.padding(horizontal = 0.dp)) {
        ui.draft.routes.forEachIndexed { i, cidr ->
            val msg = ui.messageFor(Fields.route(i))
            SubnetRow(
                cidr, msg?.text(context),
                onRemove = if (ui.readOnly) null else ({ actions.onRemoveSubnet(cidr, false) }),
                warning = msg?.warning == true,
                modifier = Modifier.bringIntoViewRequester(targets.view(Fields.route(i))),
            )
        }
        ui.messageFor(Fields.ROUTES)?.let { Text(it.text(context), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
    if (!ui.readOnly) {
        CidrInput(
            ui.subnetInput, actions.onSubnetInput, actions.onAddSubnet, ui.subnetError?.text(context),
            Modifier.bringIntoViewRequester(targets.view("subnetInput")).testTag("subnet-input"),
            targets.requester("subnetInput"),
        )
    }
    // Excluded subnets, collapsed by default.
    val expanded = ui.excludedExpanded
    val stateText = stringResource(if (expanded) R.string.a11y_expanded else R.string.a11y_collapsed)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = actions.onToggleExcluded)
            .semantics { stateDescription = stateText }
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SymbolIcon(R.drawable.ic_block, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.excluded_subnets), style = MaterialTheme.typography.bodyLarge)
            Text(
                pluralStringResource(R.plurals.excluded_summary, ui.draft.excluded.size, ui.draft.excluded.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SymbolIcon(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    AnimatedVisibility(expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ui.draft.excluded.forEachIndexed { i, cidr ->
                SubnetRow(
                    cidr, ui.messageFor(Fields.excluded(i))?.text(context),
                    onRemove = if (ui.readOnly) null else ({ actions.onRemoveSubnet(cidr, true) }),
                    leadingIcon = R.drawable.ic_block,
                    modifier = Modifier.bringIntoViewRequester(targets.view(Fields.excluded(i))),
                )
            }
            if (!ui.readOnly) CidrInput(ui.excludedInput, actions.onExcludedInput, actions.onAddExcluded, ui.excludedError?.text(context))
        }
    }
}

@Composable
private fun DnsSection(ui: EditorUi, actions: EditorActions, targets: FocusTargets) {
    val context = LocalContext.current
    val resources = LocalResources.current
    SectionHeader(stringResource(R.string.section_dns))
    Field(ui, actions, targets, Fields.DNS, R.string.field_dns_server, ui.draft.dnsServer, { d, v -> d.copy(dnsServer = v.trim()) }, mono = true, keyboard = KeyboardType.Uri)
    Column(Modifier.bringIntoViewRequester(targets.view(Fields.SUFFIXES)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.dns_suffixes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChipField(
            ui.draft.suffixes, stringResource(R.string.add_suffix),
            onRemove = { actions.onRemoveDomain(true, it) },
            onAdd = { actions.onAddDomain(true, it) },
            enabled = !ui.readOnly,
            placeholder = stringResource(R.string.dns_suffix_hint),
        )
        ui.messageFor(Fields.SUFFIXES)?.let { Text(it.text(context), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
    Column(Modifier.bringIntoViewRequester(targets.view(Fields.SEARCH)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.search_domains), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChipField(
            ui.draft.searchDomains, stringResource(R.string.add_domain),
            onRemove = { actions.onRemoveDomain(false, it) },
            onAdd = { actions.onAddDomain(false, it) },
            enabled = !ui.readOnly,
        )
    }
    SwitchRow(stringResource(R.string.dns_reverse), null, ui.draft.reverseLookups, !ui.readOnly) { v -> actions.edit(Fields.DNS) { it.copy(reverseLookups = v) } }
    SwitchRow(stringResource(R.string.dns_hide_v6), stringResource(R.string.dns_hide_v6_help), ui.draft.hideAAAA, !ui.readOnly) { v -> actions.edit(Fields.DNS) { it.copy(hideAAAA = v) } }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (subtitle != null) 72.dp else 56.dp)
            .toggleableRow(checked, enabled, onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked, null, enabled = enabled,
            thumbContent = if (checked) ({ SymbolIcon(R.drawable.ic_check, size = 16.dp) }) else null,
        )
    }
}

private fun Modifier.toggleableRow(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) =
    toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)

@Composable
private fun AppsSection(ui: EditorUi, actions: EditorActions, targets: FocusTargets) {
    val context = LocalContext.current
    val resources = LocalResources.current
    SectionHeader(stringResource(R.string.section_apps))
    val modes = listOf(Apps.ALL to R.string.apps_all, Apps.INCLUDE to R.string.apps_only, Apps.EXCLUDE to R.string.apps_except)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { i, (mode, label) ->
            SegmentedButton(
                selected = ui.draft.appsMode == mode,
                onClick = { actions.onAppsMode(mode) },
                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                enabled = !ui.readOnly,
                label = { Text(stringResource(label)) },
            )
        }
    }
    if (ui.draft.appsMode != Apps.ALL) {
        val n = ui.draft.packages.size
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !ui.readOnly, onClick = actions.onPickApps)
                .bringIntoViewRequester(targets.view(Fields.APPS))
                .heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SymbolIcon(R.drawable.ic_apps, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(
                    if (n == 0) stringResource(R.string.apps_none_selected) else pluralStringResource(R.plurals.apps_count, n, n),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (n > 0) {
                    Text(ui.appLabels.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                ui.messageFor(Fields.APPS)?.let { Text(it.text(context), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            SymbolIcon(R.drawable.ic_chevron_right, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AdvancedSection(ui: EditorUi, actions: EditorActions, targets: FocusTargets) {
    val expanded = ui.advancedExpanded
    val stateText = stringResource(if (expanded) R.string.a11y_expanded else R.string.a11y_collapsed)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = actions.onToggleAdvanced)
            .semantics { stateDescription = stateText }
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionHeader(stringResource(R.string.section_advanced), Modifier.weight(1f))
        SymbolIcon(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    AnimatedVisibility(expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val s = stringResource(R.string.unit_seconds)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(ui, actions, targets, Fields.KEEPALIVE, R.string.field_keepalive, ui.draft.keepalive, { d, v -> d.copy(keepalive = v.filter(Char::isDigit).take(4)) }, Modifier.weight(1f), keyboard = KeyboardType.Number, suffix = s)
                Field(ui, actions, targets, Fields.TIMEOUT, R.string.field_timeout, ui.draft.timeout, { d, v -> d.copy(timeout = v.filter(Char::isDigit).take(4)) }, Modifier.weight(1f), keyboard = KeyboardType.Number, suffix = s)
            }
            Field(ui, actions, targets, Fields.TUN, R.string.field_tunnel_subnet, ui.draft.tunCidr, { d, v -> d.copy(tunCidr = v.trim()) }, mono = true, keyboard = KeyboardType.Uri)
            Field(ui, actions, targets, Fields.MTU, R.string.field_mtu, ui.draft.mtu, { d, v -> d.copy(mtu = v.filter(Char::isDigit).take(5)) }, keyboard = KeyboardType.Number)
            // The default can only move to another profile, never be switched off (there's always one).
            SwitchRow(stringResource(R.string.set_default), stringResource(R.string.set_default_help), ui.draft.isDefault, !ui.readOnly && !ui.wasDefault) { v ->
                actions.edit(Fields.NAME) { it.copy(isDefault = v) }
            }
        }
    }
}

// ---- Previews ------------------------------------------------------------------------

private val previewKeys = listOf(
    KeyEntry("k1", "Pixel StrongBox", KeyEntry.KEYSTORE, "ecdsa-sha2-nistp256", "SHA256:x", "ecdsa-sha2-nistp256 AAAA", "pkix", KeyEntry.STRONGBOX, "2026-03-12T10:00:00Z"),
    KeyEntry("k2", "laptop-import", KeyEntry.IMPORTED, "ssh-ed25519", "SHA256:y", "ssh-ed25519 AAAA", null, KeyEntry.VAULT, "2026-01-20T10:00:00Z"),
)
private val officeDraft = ProfileDraft(
    id = "office", name = "Office", host = "jump.corp.example", user = "alex", keyId = "k1",
    routes = listOf("10.20.0.0/16", "10.30.4.0/24", "172.16.8.0/22"), excluded = listOf("10.20.99.0/24"),
    dnsServer = "10.20.0.53", suffixes = listOf("corp.example", "internal"), searchDomains = listOf("corp.example"),
    appsMode = Apps.INCLUDE, packages = listOf("com.android.chrome", "com.Slack", "com.termux"), isDefault = true,
)
private val pin = HostKey("ecdsa-sha2-nistp256", "SHA256:nThbRk2mXJvF3e8pQz1LYw7cDh0KsVa4Tq9NoM6uGf5BwE+i2rY", "2026-03-12T10:00:00Z")

@Composable
private fun PreviewEditor(ui: EditorUi) = SshovelTheme(dynamicColor = false) {
    ProfileEditorScreen(ui, EditorActions(), remember { SnackbarHostState() })
}

@Preview(name = "P1 New") @Preview(name = "P1 New dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewNew() = PreviewEditor(
    EditorUi(loaded = true, isNew = true, draft = ProfileDraft(name = "Lab", keyId = "k1"), keys = previewKeys),
)

@Preview(name = "P2 Existing", heightDp = 1800) @Preview(name = "P2 Existing dark", heightDp = 1800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewExisting() = PreviewEditor(
    EditorUi(
        loaded = true, isNew = false, draft = officeDraft, saved = officeDraft, hostKey = pin, keys = previewKeys,
        appLabels = listOf("Chrome", "Slack", "Termux"), excludedExpanded = true, advancedExpanded = true, wasDefault = true,
    ),
)

@Preview(name = "P3 Validation", heightDp = 1400) @Preview(name = "P3 Validation dark", heightDp = 1400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewValidation() {
    val draft = officeDraft.copy(routes = listOf("10.20.0.0/16", "10.20.4.0/24"), dnsServer = "")
    PreviewEditor(
        EditorUi(
            loaded = true, isNew = false, draft = draft, saved = officeDraft, hostKey = pin, keys = previewKeys, showAll = true,
            subnetInput = "10.40.0.0/33", subnetError = FieldMsg(FieldMsg.Kind.CIDR_INVALID, listOf("10.40.0.0/16")),
            messages = mapOf(
                Fields.route(1) to FieldMsg(FieldMsg.Kind.COVERED, listOf("10.20.0.0/16"), warning = true),
                Fields.DNS to FieldMsg(FieldMsg.Kind.DNS_REQUIRED),
            ),
        ),
    )
}

@Preview(name = "P4 Tunnel overlap", heightDp = 1800) @Preview(name = "P4 Tunnel overlap dark", heightDp = 1800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewTunOverlap() {
    val draft = officeDraft.copy(tunCidr = "10.20.99.0/24", keepalive = "30", timeout = "15", mtu = "1400")
    PreviewEditor(
        EditorUi(
            loaded = true, isNew = false, draft = draft, saved = officeDraft, hostKey = pin, keys = previewKeys, showAll = true, advancedExpanded = true,
            appLabels = listOf("Chrome", "Slack", "Termux"),
            messages = mapOf(Fields.TUN to FieldMsg(FieldMsg.Kind.TUN_OVERLAP, listOf("10.20.0.0/16", "198.18.0.0/24"))),
        ),
    )
}

@Preview(name = "P6 Read-only") @Preview(name = "P6 Read-only dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewReadOnly() = PreviewEditor(
    EditorUi(loaded = true, isNew = false, draft = officeDraft, saved = officeDraft, hostKey = pin, keys = previewKeys, readOnly = true),
)

@Preview(name = "P5 Discard") @Preview(name = "P5 Discard dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewDiscard() = SshovelTheme(dynamicColor = false) { DiscardDialog("the host and 1 subnet", "Office", {}, {}) }

@Preview(name = "P7 Forget key") @Preview(name = "P7 Forget key dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewForget() = SshovelTheme(dynamicColor = false) { ForgetKeyDialog("jump.corp.example", {}, {}) }

@Preview(name = "P8 Delete") @Preview(name = "P8 Delete dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewDelete() = SshovelTheme(dynamicColor = false) { DeleteProfileDialog("Office", "Lab", {}, {}) }
