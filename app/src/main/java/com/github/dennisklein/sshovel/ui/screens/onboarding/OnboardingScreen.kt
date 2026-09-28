// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.onboarding

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Icon
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.data.Auth
import com.github.dennisklein.sshovel.data.Dns
import com.github.dennisklein.sshovel.data.HostKeyInfo
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.data.Server
import com.github.dennisklein.sshovel.keys.KeyEntry
import com.github.dennisklein.sshovel.keys.SshKeys
import com.github.dennisklein.sshovel.tile.TunnelTileService
import com.github.dennisklein.sshovel.tunnel.Codes
import com.github.dennisklein.sshovel.tunnel.ConnectionCheck
import com.github.dennisklein.sshovel.ui.components.Badge
import com.github.dennisklein.sshovel.ui.components.BottomActions
import com.github.dennisklein.sshovel.ui.components.ButtonIcon
import com.github.dennisklein.sshovel.ui.components.CheckResultRow
import com.github.dennisklein.sshovel.ui.components.CheckStatus
import com.github.dennisklein.sshovel.ui.components.ChipField
import com.github.dennisklein.sshovel.ui.components.CommandBlock
import com.github.dennisklein.sshovel.ui.components.FingerprintBlock
import com.github.dennisklein.sshovel.ui.components.GroupedList
import com.github.dennisklein.sshovel.ui.components.Headline
import com.github.dennisklein.sshovel.ui.components.IconTile
import com.github.dennisklein.sshovel.ui.components.InfoNote
import com.github.dennisklein.sshovel.ui.components.KeyBadge
import com.github.dennisklein.sshovel.ui.components.LogoIcon
import com.github.dennisklein.sshovel.ui.components.OnboardingScaffold
import com.github.dennisklein.sshovel.ui.components.Point
import com.github.dennisklein.sshovel.ui.components.PublicKeyBlock
import com.github.dennisklein.sshovel.ui.components.SymbolIcon
import com.github.dennisklein.sshovel.ui.components.badge
import com.github.dennisklein.sshovel.ui.components.mono
import com.github.dennisklein.sshovel.ui.components.shareText
import com.github.dennisklein.sshovel.ui.format.errorText
import com.github.dennisklein.sshovel.ui.format.formatDate
import com.github.dennisklein.sshovel.ui.format.monoArg
import com.github.dennisklein.sshovel.ui.format.monoWords
import com.github.dennisklein.sshovel.ui.screens.home.VerifyUi
import com.github.dennisklein.sshovel.ui.screens.keys.ImportKeySheet
import com.github.dennisklein.sshovel.ui.screens.keys.QrSheet
import com.github.dennisklein.sshovel.ui.screens.keys.keyTypeName
import com.github.dennisklein.sshovel.ui.screens.profile.Fields
import com.github.dennisklein.sshovel.ui.screens.profile.ProfileDraft
import com.github.dennisklein.sshovel.ui.screens.profile.text
import com.github.dennisklein.sshovel.ui.theme.LocalStateColors
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme
import java.time.Duration
import java.time.Instant

/** Onboarding with its ViewModel: events, sheets, and the system "Add tile" prompt. */
@Composable
fun OnboardingRoute(vm: OnboardingViewModel, onFinished: () -> Unit, onConnect: (String) -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    BackHandler(enabled = ui.step > 1) { vm.back() }
    LaunchedEffect(Unit) {
        vm.onboardingEvents.collect { e ->
            when (e) {
                OnboardingEvent.Finished -> onFinished()
                is OnboardingEvent.ConnectNow -> onConnect(e.profileId)
                OnboardingEvent.ServerTrusted -> snackbar.showSnackbar(resources.getString(R.string.server_trusted))
                OnboardingEvent.Copied -> snackbar.showSnackbar(resources.getString(R.string.copied))
            }
        }
    }
    if (!ui.loaded) return
    OnboardingScreen(
        ui,
        OnboardingActions(
            onBack = { vm.back() },
            onSkip = vm::skip,
            onNext = vm::next,
            onKeyName = vm::setKeyName,
            onCreateKey = vm::createKey,
            onImportInstead = { vm.openImport(true) },
            onCopied = vm::copied,
            onShare = { context.shareText(it) },
            onShowQr = { vm.showQr(it) },
            edit = vm::edit,
            touched = vm::touched,
            onAddRoute = { vm.addRoute(it)?.text(context) },
            onRemoveRoute = vm::removeRoute,
            onAddSuffix = { vm.addSuffix(it)?.text(context) },
            onRemoveSuffix = vm::removeSuffix,
            onSaveServer = vm::saveServer,
            onTrust = vm::trust,
            onRetryVerify = vm::retryVerify,
            onTestAgain = vm::runTest,
            onShowPublicKey = vm::showPublicKey,
            onAddTile = { requestTile(context, vm::tileResult) },
            onRetryTile = vm::retryTile,
            onFinish = vm::finish,
        ),
        snackbar,
        defaultProfile = vm.defaultProfileName(),
    )
    if (ui.importOpen) {
        ImportKeySheet(ui.importBusy, ui.importError, vm::importKey, vm::clearImportError) { vm.openImport(false) }
    }
    ui.qr?.let { QrSheet(it) { vm.showQr(null) } }
}

/** StatusBarManager.requestAddTileService (O7); already added counts as added (O8). */
private fun requestTile(context: Context, onResult: (Boolean) -> Unit) {
    val sbm = context.getSystemService(StatusBarManager::class.java) ?: return onResult(false)
    sbm.requestAddTileService(
        ComponentName(context, TunnelTileService::class.java),
        context.getString(R.string.tile_label),
        Icon.createWithResource(context, R.drawable.ic_sshovel),
        context.mainExecutor,
    ) { result ->
        onResult(result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
    }
}

data class OnboardingActions(
    val onBack: () -> Unit = {},
    val onSkip: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onKeyName: (String) -> Unit = {},
    val onCreateKey: (String) -> Unit = {},
    val onImportInstead: () -> Unit = {},
    val onCopied: () -> Unit = {},
    val onShare: (String) -> Unit = {},
    val onShowQr: (String) -> Unit = {},
    val edit: ((ProfileDraft) -> ProfileDraft) -> Unit = {},
    val touched: (String) -> Unit = {},
    val onAddRoute: (String) -> String? = { null },
    val onRemoveRoute: (String) -> Unit = {},
    val onAddSuffix: (String) -> String? = { null },
    val onRemoveSuffix: (String) -> Unit = {},
    val onSaveServer: () -> Unit = {},
    val onTrust: () -> Unit = {},
    val onRetryVerify: () -> Unit = {},
    val onTestAgain: () -> Unit = {},
    val onShowPublicKey: () -> Unit = {},
    val onAddTile: () -> Unit = {},
    val onRetryTile: () -> Unit = {},
    val onFinish: (connect: Boolean) -> Unit = {},
)

/** Onboarding (DESIGN_BRIEF §5.2, handoff O1–O9). */
@Composable
fun OnboardingScreen(ui: OnboardingUi, actions: OnboardingActions, snackbar: SnackbarHostState = remember { SnackbarHostState() }, defaultProfile: String? = null) {
    val steps = OnboardingUi.STEPS
    val back = if (ui.step > 1) actions.onBack else null
    val skip = if (ui.step < 7 && !(ui.step == 6 && ui.tile != null)) actions.onSkip else null
    when (ui.step) {
        1 -> OnboardingScaffold(1, steps, null, skip, { BottomActions(null) { Button(actions.onNext) { Text(stringResource(R.string.onb_get_started)) } } }, snackbar = snackbar) {
            Welcome()
        }
        2 -> OnboardingScaffold(2, steps, back, skip, {
            BottomActions({ TextButton(actions.onImportInstead) { Text(stringResource(R.string.onb_key_import_instead)) } }) {
                if (ui.key != null && ui.keyIsNew) {
                    Button(actions.onNext) { Text(stringResource(R.string.onb_next)) }
                } else {
                    val defaultName = stringResource(R.string.key_new_default_name)
                    Button({ actions.onCreateKey(defaultName) }, enabled = !ui.creating) {
                        if (ui.creating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.onb_key_create))
                    }
                }
            }
        }, snackbar = snackbar) { CreateKeyStep(ui, actions) }
        3 -> OnboardingScaffold(3, steps, back, skip, {
            BottomActions(null) { Button(actions.onNext) { Text(stringResource(R.string.onb_install_done)) } }
        }, snackbar = snackbar) { InstallKeyStep(ui, actions) }
        4 -> OnboardingScaffold(4, steps, back, skip, {
            BottomActions(null) { Button(actions.onSaveServer, enabled = ui.serverReady && !ui.saving) { Text(stringResource(R.string.onb_next)) } }
        }, snackbar = snackbar, topDivider = true) { ServerStep(ui, actions) }
        5 -> OnboardingScaffold(5, steps, back, skip, { VerifyActions(ui, actions) }, snackbar = snackbar) { VerifyStep(ui, actions) }
        6 -> OnboardingScaffold(6, steps, back, skip, {
            BottomActions(if (ui.tile == null) ({ TextButton(actions.onNext) { Text(stringResource(R.string.onb_not_now)) } }) else null) {
                if (ui.tile == null) {
                    Button(actions.onAddTile) {
                        ButtonIcon(R.drawable.ic_add)
                        Text(stringResource(R.string.onb_tile_add))
                    }
                } else {
                    Button(actions.onNext) { Text(stringResource(R.string.onb_next)) }
                }
            }
        }, snackbar = snackbar) { TileStep(ui, actions, defaultProfile ?: ui.profile?.name.orEmpty()) }
        else -> OnboardingScaffold(7, steps, back, null, {
            BottomActions({ TextButton({ actions.onFinish(false) }) { Text(stringResource(R.string.onb_go_home)) } }) {
                Button({ actions.onFinish(true) }, enabled = ui.profile != null) {
                    ButtonIcon(R.drawable.ic_power_settings_new)
                    Text(stringResource(R.string.onb_connect_now))
                }
            }
        }, snackbar = snackbar) { DoneStep(ui) }
    }
}

@Composable
private fun Welcome() {
    val cs = MaterialTheme.colorScheme
    IconTile(LogoIcon, cs.primaryContainer, cs.onPrimaryContainer, size = 112.dp, iconSize = 64.dp, corner = 28.dp)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.onb_welcome_title), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.onb_welcome_body), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
    }
    GroupedList {
        Text(
            stringResource(R.string.onb_welcome_need),
            style = MaterialTheme.typography.titleSmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        )
        Point(R.drawable.ic_dns, stringResource(R.string.onb_welcome_need_server), null, titleStyleLarge = true)
        Point(R.drawable.ic_lan, stringResource(R.string.onb_welcome_need_subnets), null, titleStyleLarge = true)
    }
}

@Composable
private fun CreateKeyStep(ui: OnboardingUi, actions: OnboardingActions) {
    val context = LocalContext.current
    val resources = LocalResources.current
    Headline(stringResource(R.string.onb_key_title), stringResource(R.string.onb_key_body))
    val key = ui.key?.takeIf { ui.keyIsNew }
    OutlinedTextField(
        key?.name ?: ui.keyName,
        actions.onKeyName,
        Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.key_name)) },
        placeholder = { Text(stringResource(R.string.key_new_default_name)) },
        supportingText = { Text(stringResource(R.string.key_name_helper)) },
        singleLine = true,
        readOnly = key != null,
    )
    if (key != null) {
        val st = LocalStateColors.current
        OutlinedCard(shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(Modifier.size(40.dp), shape = CircleShape, color = st.stateOnContainer, contentColor = st.onStateOnContainer) {
                        Box(contentAlignment = Alignment.Center) { SymbolIcon(R.drawable.ic_check) }
                    }
                    Column {
                        Text(
                            stringResource(if (key.kind == KeyEntry.KEYSTORE) R.string.onb_key_created else R.string.key_imported_toast),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(keyTypeName(context, key), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyBadge(key.badge(), large = true)
                    if (key.kind == KeyEntry.KEYSTORE) KeyBadge(Badge.NOT_EXPORTABLE, large = true)
                }
                if (key.kind == KeyEntry.KEYSTORE) {
                    Text(
                        stringResource(
                            when (key.security) {
                                KeyEntry.STRONGBOX -> R.string.onb_key_strongbox_note
                                KeyEntry.TEE -> R.string.onb_key_tee_note
                                else -> R.string.onb_key_software_note
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(stringResource(R.string.import_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun InstallKeyStep(ui: OnboardingUi, actions: OnboardingActions) {
    val key = ui.key ?: return
    val file = "~/.ssh/authorized_keys"
    Headline(stringResource(R.string.onb_install_title), monoWords(stringResource(R.string.onb_install_body, file), file))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(monoWords(stringResource(R.string.onb_install_line_label), "authorized_keys"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PublicKeyBlock(key.authorizedLine, actions.onCopied, { actions.onShare(key.authorizedLine) }, { actions.onShowQr(key.authorizedLine) })
        Text(
            monoWords(stringResource(R.string.onb_install_restrict_help), "restrict", "port-forwarding"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.onb_install_command_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CommandBlock("echo '${key.authorizedLine}' >> ~/.ssh/authorized_keys", actions.onCopied)
    }
}

@Composable
private fun ServerStep(ui: OnboardingUi, actions: OnboardingActions) {
    val context = LocalContext.current
    val resources = LocalResources.current
    Text(stringResource(R.string.onb_server_title), style = MaterialTheme.typography.headlineMedium)
    @Composable
    fun F(key: String, label: Int, value: String, mono: Boolean, kb: KeyboardType, modifier: Modifier = Modifier, change: (ProfileDraft, String) -> ProfileDraft) {
        val msg = ui.messageFor(key)
        OutlinedTextField(
            value, { v -> actions.edit { change(it, v) } },
            modifier.fillMaxWidth().onBlur { if (value.isNotEmpty()) actions.touched(key) },
            label = { Text(stringResource(label)) },
            singleLine = true,
            textStyle = if (mono) MaterialTheme.typography.bodyLarge.mono() else MaterialTheme.typography.bodyLarge,
            isError = msg != null,
            supportingText = msg?.let { { Text(it.text(context)) } },
            keyboardOptions = KeyboardOptions(keyboardType = kb, autoCorrectEnabled = false),
        )
    }
    F(Fields.NAME, R.string.field_profile_name, ui.draft.name, false, KeyboardType.Text) { d, v -> d.copy(name = v) }
    F(Fields.HOST, R.string.field_host, ui.draft.host, true, KeyboardType.Uri) { d, v -> d.copy(host = v) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        F(Fields.PORT, R.string.field_port, ui.draft.port, true, KeyboardType.Number, Modifier.width(96.dp)) { d, v -> d.copy(port = v.filter(Char::isDigit).take(5)) }
        F(Fields.USER, R.string.field_username, ui.draft.user, true, KeyboardType.Ascii, Modifier.weight(1f)) { d, v -> d.copy(user = v) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.onb_subnets_label), style = MaterialTheme.typography.titleSmall)
        ChipField(ui.draft.routes, stringResource(R.string.add_subnet), actions.onRemoveRoute, actions.onAddRoute, placeholder = stringResource(R.string.add_subnet_hint))
    }
    F(Fields.DNS, R.string.field_dns_server, ui.draft.dnsServer, true, KeyboardType.Uri) { d, v -> d.copy(dnsServer = v.trim()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.onb_suffixes_label), style = MaterialTheme.typography.titleSmall)
        ChipField(ui.draft.suffixes, stringResource(R.string.add_suffix), actions.onRemoveSuffix, actions.onAddSuffix, placeholder = stringResource(R.string.dns_suffix_hint))
    }
}

/** Calls [action] when the field loses focus after having had it. */
private fun Modifier.onBlur(action: () -> Unit): Modifier {
    var had = false
    return onFocusChanged { f ->
        if (had && !f.isFocused) action()
        had = f.isFocused
    }
}

@Composable
private fun VerifyStep(ui: OnboardingUi, actions: OnboardingActions) {
    val p = ui.profile ?: return
    val hostPort = "${p.server.host}:${p.server.port}"
    val test = ui.test
    when {
        test != null -> TestResults(ui, test, actions)
        else -> {
            Headline(stringResource(R.string.onb_verify_title), monoWords(stringResource(R.string.onb_verify_body, hostPort), hostPort))
            when (val v = ui.verify) {
                is VerifyUi.Ready -> {
                    FingerprintBlock(v.key.type, v.key.fingerprint, onCopied = actions.onCopied)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SymbolIcon(R.drawable.ic_terminal, size = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            monoArg(R.string.verify_compare, "ssh-keygen -lf ${SshKeys.hostKeyFile(v.key.type)}"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is VerifyUi.Failed -> {
                    val (title, body) = errorText(LocalContext.current, v.code, p)
                    ErrorCard(title, AnnotatedString(body))
                }
                else -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun VerifyActions(ui: OnboardingUi, actions: OnboardingActions) {
    val test = ui.test
    when {
        test == null -> BottomActions({ TextButton(actions.onBack) { Text(stringResource(R.string.action_cancel)) } }) {
            when (ui.verify) {
                is VerifyUi.Ready -> Button(actions.onTrust) {
                    ButtonIcon(R.drawable.ic_verified_user)
                    Text(stringResource(R.string.action_trust_server))
                }
                is VerifyUi.Failed -> Button(actions.onRetryVerify) {
                    ButtonIcon(R.drawable.ic_refresh)
                    Text(stringResource(R.string.action_try_again))
                }
                else -> Button({}, enabled = false) { Text(stringResource(R.string.action_trust_server)) }
            }
        }
        test.running -> BottomActions(null) { Button({}, enabled = false) { Text(stringResource(R.string.onb_next)) } }
        test.failed == null -> BottomActions({ TextButton(actions.onTestAgain) { Text(stringResource(R.string.test_again)) } }) {
            Button(actions.onNext) { Text(stringResource(R.string.onb_next)) }
        }
        else -> BottomActions({ TextButton(actions.onNext) { Text(stringResource(R.string.test_continue_anyway)) } }) {
            FilledTonalButton(actions.onTestAgain) {
                ButtonIcon(R.drawable.ic_refresh)
                Text(stringResource(R.string.test_again))
            }
        }
    }
}

/** O6a/O6b: the checks in order; the first failure stops the rest. */
@Composable
private fun TestResults(ui: OnboardingUi, test: TestUi, actions: OnboardingActions) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val p = ui.profile ?: return
    val failed = test.failed
    val noDns = test.checks.any { it.status == ConnectionCheck.SKIPPED }
    val title = when {
        test.running -> R.string.test_running
        failed == null -> R.string.test_ok_title
        else -> R.string.test_fail_title
    }
    val body = when {
        test.running -> null
        failed == null -> stringResource(if (noDns) R.string.test_ok_body_no_dns else R.string.test_ok_body)
        else -> stringResource(
            when (failed.id) {
                ConnectionCheck.REACHABLE -> R.string.test_fail_reachable
                ConnectionCheck.IDENTITY -> R.string.test_fail_identity
                ConnectionCheck.AUTH -> R.string.test_fail_auth
                ConnectionCheck.FORWARDING -> R.string.test_fail_forwarding
                else -> R.string.test_fail_dns
            },
        )
    }
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) { Headline(stringResource(title), body) }
    GroupedList {
        ConnectionCheck.ORDER.forEach { id ->
            val c = test.checks.firstOrNull { it.id == id }
            val label = stringResource(
                when (id) {
                    ConnectionCheck.REACHABLE -> R.string.test_check_reachable
                    ConnectionCheck.IDENTITY -> R.string.test_check_identity
                    ConnectionCheck.AUTH -> R.string.test_check_key
                    ConnectionCheck.FORWARDING -> R.string.test_check_forwarding
                    else -> R.string.test_check_dns
                },
            )
            if (c?.status == ConnectionCheck.FAILED) {
                val (t, b) = errorText(context, c.code, p, ui.key?.name)
                val errBody = if (c.code == Codes.AUTH_FAILED) {
                    val userHost = "${p.server.user}@${p.server.host}"
                    monoWords(b, userHost, "~/.ssh/authorized_keys")
                } else {
                    monoWords(b, p.dns.server.orEmpty(), "${p.server.host}:${p.server.port}")
                }
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SymbolIcon(R.drawable.ic_cancel_filled)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(t, style = MaterialTheme.typography.titleMedium)
                            Text(errBody, style = MaterialTheme.typography.bodyMedium)
                            when (c.code) {
                                Codes.AUTH_FAILED -> Button(
                                    actions.onShowPublicKey,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                                ) {
                                    ButtonIcon(R.drawable.ic_key)
                                    Text(stringResource(R.string.err_auth_action))
                                }
                                Codes.HOST_UNREACHABLE -> Button(
                                    actions.onTestAgain,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                                ) {
                                    ButtonIcon(R.drawable.ic_refresh)
                                    Text(stringResource(R.string.err_unreachable_action))
                                }
                            }
                        }
                    }
                }
            } else {
                val status = when {
                    test.running -> CheckStatus.RUNNING
                    c?.status == ConnectionCheck.PASSED -> CheckStatus.PASSED
                    else -> CheckStatus.NOT_RUN
                }
                CheckResultRow(label, status) { CheckDetail(ui, p, c) }
            }
        }
    }
}

@Composable
private fun CheckDetail(ui: OnboardingUi, p: Profile, c: ConnectionCheck?) {
    c ?: return
    val mono = MaterialTheme.typography.bodyMedium.mono()
    when (c.id) {
        ConnectionCheck.REACHABLE -> Text(stringResource(R.string.test_ms, "${p.server.host}:${p.server.port}", c.ms.toInt()), style = mono)
        ConnectionCheck.IDENTITY -> p.hostKey?.let { hk ->
            val recent = hk.pinnedAt?.let { runCatching { Duration.between(Instant.parse(it), Instant.now()).toMinutes() < 5 }.getOrNull() } == true
            Text(stringResource(R.string.test_identity_detail, SshKeys.displayType(hk.type), if (recent) stringResource(R.string.test_just_now) else formatDate(hk.pinnedAt).orEmpty()))
        }
        ConnectionCheck.AUTH -> Text(monoWords(stringResource(R.string.test_key_detail, ui.key?.name.orEmpty(), p.server.user), p.server.user))
        ConnectionCheck.FORWARDING -> Text(monoWords(stringResource(R.string.test_forwarding_detail, c.target), c.target))
        ConnectionCheck.DNS -> Text(stringResource(R.string.test_ms, p.dns.server.orEmpty(), c.ms.toInt()), style = mono)
    }
}

@Composable
private fun ErrorCard(title: String, body: AnnotatedString) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SymbolIcon(R.drawable.ic_error_filled)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun TileStep(ui: OnboardingUi, actions: OnboardingActions, profileName: String) {
    val cs = MaterialTheme.colorScheme
    val st = LocalStateColors.current
    when (ui.tile) {
        null -> {
            Headline(stringResource(R.string.onb_tile_title), stringResource(R.string.onb_tile_body, profileName))
            // A static drawing of the system tile when On, not a component (handoff O7).
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainerHigh) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(Modifier.weight(1f).heightIn(min = 72.dp).alpha(0.55f), shape = RoundedCornerShape(36.dp), color = cs.surfaceContainerHighest) {
                            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { SymbolIcon(R.drawable.ic_wifi) }
                                Text(stringResource(R.string.onb_tile_mock_internet), style = MaterialTheme.typography.titleSmall)
                            }
                        }
                        Surface(Modifier.weight(1f).heightIn(min = 72.dp), shape = RoundedCornerShape(36.dp), color = cs.primary, contentColor = cs.onPrimary) {
                            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Surface(Modifier.size(48.dp), shape = CircleShape, color = cs.onPrimary, contentColor = cs.primary) {
                                    Box(contentAlignment = Alignment.Center) { SymbolIcon(LogoIcon) }
                                }
                                Column {
                                    Text(stringResource(R.string.tile_label), style = MaterialTheme.typography.titleSmall)
                                    Text(profileName, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    Text(stringResource(R.string.onb_tile_caption), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
        TileOutcome.ADDED -> {
            Headline(stringResource(R.string.onb_tile_added_title), stringResource(R.string.onb_tile_added_body))
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = st.stateOnContainer, contentColor = st.onStateOnContainer) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    SymbolIcon(R.drawable.ic_check_circle_filled)
                    Text(stringResource(R.string.onb_tile_added_card), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        TileOutcome.DECLINED -> {
            Headline(stringResource(R.string.onb_tile_declined_title), stringResource(R.string.onb_tile_declined_body))
            InfoNote(R.drawable.ic_info, stringResource(R.string.onb_tile_declined_note))
            // The system stops prompting after two declines (handoff O8).
            if (ui.declines < 2) {
                OutlinedButton(actions.onRetryTile) {
                    ButtonIcon(R.drawable.ic_refresh)
                    Text(stringResource(R.string.action_try_again))
                }
            }
        }
    }
}

@Composable
private fun DoneStep(ui: OnboardingUi) {
    val cs = MaterialTheme.colorScheme
    IconTile(LogoIcon, cs.primaryContainer, cs.onPrimaryContainer, iconSize = 40.dp)
    Headline(stringResource(R.string.onb_done_title), stringResource(R.string.onb_done_body))
    val p = ui.profile ?: return
    GroupedList {
        SummaryRow(R.drawable.ic_dns, p.name, "${p.server.user}@${p.server.host}:${p.server.port}", mono = true)
        SummaryRow(
            R.drawable.ic_lan,
            stringResource(
                R.string.onb_done_routes,
                pluralStringResource(R.plurals.profile_subnets, p.routes.size, p.routes.size),
                pluralStringResource(R.plurals.domain_suffixes, p.dns.suffixes.size, p.dns.suffixes.size),
            ),
            p.routes.joinToString(", "),
            mono = true,
        )
        ui.key?.let { k ->
            val badge = stringResource(
                when (k.badge()) {
                    Badge.STRONGBOX -> R.string.badge_strongbox
                    Badge.HARDWARE -> R.string.badge_hardware
                    Badge.SOFTWARE -> R.string.badge_software
                    else -> R.string.badge_encrypted
                },
            )
            val accepted = ui.test?.checks?.any { it.id == ConnectionCheck.AUTH && it.status == ConnectionCheck.PASSED } == true
            SummaryRow(R.drawable.ic_key, k.name, if (accepted) stringResource(R.string.onb_done_key, badge) else badge, mono = false)
        }
    }
}

@Composable
private fun SummaryRow(icon: Int, title: String, subtitle: String, mono: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        SymbolIcon(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = if (mono) MaterialTheme.typography.bodyMedium.mono() else MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- Previews: every step and outcome, light and dark ---------------------------------------

private val pKey = KeyEntry(
    "k1", "Pixel StrongBox", KeyEntry.KEYSTORE, "ecdsa-sha2-nistp256", "SHA256:x",
    "restrict,port-forwarding ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBHk3Pj8fWm0q9sX2c7Qe1RtLbNf4aYh0uVwKz6JdE5gSx pixel-strongbox@sshovel",
    "pkix", KeyEntry.STRONGBOX, "2026-03-12T10:00:00Z",
)
private val pProfile = Profile(
    "office", "Office", Server("jump.corp.example", 22, "alex"), Auth(Auth.KEYSTORE, "k1"),
    hostKey = com.github.dennisklein.sshovel.data.HostKey("ecdsa-sha2-nistp256", "SHA256:nThbRk2mXJvF3e8pQz1LYw7cDh0KsVa4Tq9NoM6uGf5BwE+i2rY", Instant.now().toString()),
    routes = listOf("10.20.0.0/16", "10.30.4.0/24"), dns = Dns("10.20.0.53", listOf("corp.example", "internal")),
)
private val pDraft = ProfileDraft(name = "Office", host = "jump.corp.example", user = "alex", keyId = "k1", routes = pProfile.routes, dnsServer = "10.20.0.53", suffixes = pProfile.dns.suffixes)
private val passed = listOf(
    ConnectionCheck("reachable", "passed", 38), ConnectionCheck("identity", "passed"), ConnectionCheck("auth", "passed"),
    ConnectionCheck("forwarding", "passed", target = "10.20.0.53:53"), ConnectionCheck("dns", "passed", 12, "10.20.0.53:53"),
)
private val authFailed = listOf(
    ConnectionCheck("reachable", "passed", 38), ConnectionCheck("identity", "passed"), ConnectionCheck("auth", "failed", code = Codes.AUTH_FAILED),
    ConnectionCheck("forwarding", "notRun"), ConnectionCheck("dns", "notRun"),
)

@Composable
private fun P(ui: OnboardingUi) = SshovelTheme(dynamicColor = false) { OnboardingScreen(ui.copy(loaded = true), OnboardingActions(), defaultProfile = "Office") }

@Preview(name = "O1 Welcome") @Preview(name = "O1 Welcome dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewO1() = P(OnboardingUi(step = 1))

@Preview(name = "O2 Create key") @Preview(name = "O2 Create key dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewO2() = P(OnboardingUi(step = 2, keyName = "Pixel StrongBox"))

@Preview(name = "O2 Key created") @Preview(name = "O2 Key created dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewO2Created() = P(OnboardingUi(step = 2, key = pKey, keyIsNew = true))

@Preview(name = "O3 Install") @Preview(name = "O3 Install dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewO3() = P(OnboardingUi(step = 3, key = pKey))

@Preview(name = "O4 Server") @Preview(name = "O4 Server dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewO4() = P(OnboardingUi(step = 4, key = pKey, draft = pDraft))

@Preview(name = "O5 Verify") @Preview(name = "O5 Verify dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewO5() = P(
    OnboardingUi(step = 5, key = pKey, profile = pProfile.copy(hostKey = null), verify = VerifyUi.Ready(pProfile, HostKeyInfo("ecdsa-sha2-nistp256", pProfile.hostKey!!.fingerprint))),
)

@Preview(name = "O6 Testing") @Preview(name = "O6 Testing dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewO6Running() = P(OnboardingUi(step = 5, key = pKey, profile = pProfile, test = TestUi(running = true)))

@Preview(name = "O6a Test success") @Preview(name = "O6a Test success dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewO6a() = P(OnboardingUi(step = 5, key = pKey, profile = pProfile, test = TestUi(running = false, checks = passed)))

@Preview(name = "O6b Test failed") @Preview(name = "O6b Test failed dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewO6b() = P(OnboardingUi(step = 5, key = pKey, profile = pProfile, test = TestUi(running = false, checks = authFailed)))

@Preview(name = "O7 Add tile") @Preview(name = "O7 Add tile dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewO7() = P(OnboardingUi(step = 6, key = pKey, profile = pProfile))

@Preview(name = "O8 Tile added") @Preview(name = "O8 Tile added dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewO8Added() = P(OnboardingUi(step = 6, key = pKey, profile = pProfile, tile = TileOutcome.ADDED))

@Preview(name = "O8 Tile declined") @Preview(name = "O8 Tile declined dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewO8Declined() = P(OnboardingUi(step = 6, key = pKey, profile = pProfile, tile = TileOutcome.DECLINED, declines = 1))

@Preview(name = "O9 Done") @Preview(name = "O9 Done dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun PreviewO9() = P(OnboardingUi(step = 7, key = pKey, profile = pProfile, test = TestUi(running = false, checks = passed)))
