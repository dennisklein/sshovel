// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui

import android.content.Intent
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.github.dennisklein.sshovel.AppContainer
import com.github.dennisklein.sshovel.BuildConfig
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.core.mobile.Mobile
import com.github.dennisklein.sshovel.diagnostics.DiagnosticsReport
import com.github.dennisklein.sshovel.tunnel.TunnelState
import com.github.dennisklein.sshovel.ui.components.copyText
import com.github.dennisklein.sshovel.ui.components.openNotificationSettings
import com.github.dennisklein.sshovel.ui.components.openVpnSettings
import com.github.dennisklein.sshovel.ui.components.shareTextFile
import com.github.dennisklein.sshovel.ui.nav.Routes
import com.github.dennisklein.sshovel.ui.screens.diagnostics.DiagTab
import com.github.dennisklein.sshovel.ui.screens.diagnostics.DiagnosticsActions
import com.github.dennisklein.sshovel.ui.screens.diagnostics.DiagnosticsScreen
import com.github.dennisklein.sshovel.ui.screens.diagnostics.DiagnosticsViewModel
import com.github.dennisklein.sshovel.ui.screens.home.HomeActions
import com.github.dennisklein.sshovel.ui.screens.home.HomeEvent
import com.github.dennisklein.sshovel.ui.screens.home.HomeScreen
import com.github.dennisklein.sshovel.ui.screens.home.HomeViewModel
import com.github.dennisklein.sshovel.ui.screens.hostkey.MismatchScreen
import com.github.dennisklein.sshovel.ui.screens.keys.CreateKeySheet
import com.github.dennisklein.sshovel.ui.screens.keys.DeleteKeyDialog
import com.github.dennisklein.sshovel.ui.screens.keys.ImportKeySheet
import com.github.dennisklein.sshovel.ui.screens.keys.KeyDetailScreen
import com.github.dennisklein.sshovel.ui.screens.keys.KeyDialog
import com.github.dennisklein.sshovel.ui.screens.keys.KeyInUseDialog
import com.github.dennisklein.sshovel.ui.screens.keys.KeySheet
import com.github.dennisklein.sshovel.ui.screens.keys.KeysEvent
import com.github.dennisklein.sshovel.ui.screens.keys.KeysScreen
import com.github.dennisklein.sshovel.ui.screens.keys.KeysViewModel
import com.github.dennisklein.sshovel.ui.screens.keys.QrSheet
import com.github.dennisklein.sshovel.ui.screens.keys.RenameKeyDialog
import com.github.dennisklein.sshovel.ui.screens.onboarding.OnboardingRoute
import com.github.dennisklein.sshovel.ui.screens.onboarding.OnboardingViewModel
import com.github.dennisklein.sshovel.ui.screens.profile.AppPickerScreen
import com.github.dennisklein.sshovel.ui.screens.profile.EditorNav
import com.github.dennisklein.sshovel.ui.screens.profile.ProfileEditorRoute
import com.github.dennisklein.sshovel.ui.screens.profile.ProfileEditorViewModel
import com.github.dennisklein.sshovel.ui.screens.profile.rememberAppIcons
import com.github.dennisklein.sshovel.ui.screens.settings.AboutScreen
import com.github.dennisklein.sshovel.ui.screens.settings.LicenseScreen
import com.github.dennisklein.sshovel.ui.screens.settings.OpenSourceLicensesScreen
import com.github.dennisklein.sshovel.ui.screens.settings.SettingsActions
import com.github.dennisklein.sshovel.ui.screens.settings.SettingsScreen
import com.github.dennisklein.sshovel.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * The app's navigation graph (handoff screen ids in [Routes]). [connect] goes through the VPN
 * explainer and Android's consent when needed (MainActivity).
 */
@Composable
fun SshovelApp(
    container: AppContainer,
    connect: (String) -> Unit,
    openRequests: Flow<Pair<String, String?>> = emptyFlow(),
    nav: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val clipboard = LocalClipboard.current
    val appSnackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // First run: onboarding opens by itself once, while there are no profiles (DESIGN_BRIEF §5.2).
    val settings by container.settings.loaded.collectAsStateWithLifecycle()
    val stored by container.store.state.collectAsStateWithLifecycle()
    val seeded by container.seeded.collectAsStateWithLifecycle()
    LaunchedEffect(settings, stored, seeded) {
        val s = settings ?: return@LaunchedEffect
        if (seeded && !s.onboardingDone && stored?.profiles?.isEmpty() == true && nav.currentDestination?.hasRouteOf<Routes.Home>() == true) {
            nav.navigate(Routes.Onboarding())
        }
    }

    // Debug builds: "open <screen> [arg]" from tools/android-env.
    LaunchedEffect(Unit) {
        openRequests.collect { (screen, arg) ->
            when (screen) {
                "home" -> nav.popBackStack(Routes.Home, inclusive = false)
                "onboarding" -> nav.navigate(Routes.Onboarding(step = arg?.toIntOrNull() ?: 1))
                "profile" -> nav.navigate(Routes.Profile(arg))
                "apps" -> nav.navigate(Routes.AppPicker)
                "keys" -> nav.navigate(Routes.Keys(import = arg == "import", create = arg == "create"))
                "key" -> arg?.let { nav.navigate(Routes.KeyDetail(it)) }
                "settings" -> nav.navigate(Routes.Settings)
                "diagnostics" -> nav.navigate(Routes.Diagnostics(arg?.toIntOrNull() ?: 0))
                "about" -> nav.navigate(Routes.About)
                "license" -> nav.navigate(Routes.License)
                "licenses" -> nav.navigate(Routes.OpenSourceLicenses)
                "mismatch" -> nav.navigate(Routes.Mismatch)
            }
        }
    }

    NavHost(nav, startDestination = Routes.Home) {
        composable<Routes.Home> {
            val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
            val ui by vm.ui.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) {
                vm.homeEvents.collect { e ->
                    when (e) {
                        is HomeEvent.Connect -> connect(e.profileId)
                        is HomeEvent.OpenKey -> nav.navigate(Routes.KeyDetail(e.keyId))
                        is HomeEvent.OpenProfile -> nav.navigate(Routes.Profile(e.profileId, e.focus))
                        HomeEvent.OpenMismatch -> nav.navigate(Routes.Mismatch) { launchSingleTop = true }
                        HomeEvent.ServerTrusted -> scope.launch { appSnackbar.showSnackbar(resources.getString(R.string.server_trusted)) }
                        is HomeEvent.OpenDiagnostics -> nav.navigate(Routes.Diagnostics(e.tab))
                    }
                }
            }
            HomeScreen(
                ui,
                HomeActions(
                    onConnect = vm::requestConnect,
                    onDisconnect = vm::disconnect,
                    onRetryNow = vm::retryNow,
                    onFix = vm::fix,
                    onSelect = vm::select,
                    onOpenProfile = { nav.navigate(Routes.Profile(it.id)) },
                    onAddProfile = { nav.navigate(Routes.Profile()) },
                    onSetUp = { nav.navigate(Routes.Onboarding()) },
                    onKeys = { nav.navigate(Routes.Keys()) },
                    onSettings = { nav.navigate(Routes.Settings) },
                    onDiagnostics = { nav.navigate(Routes.Diagnostics(it)) },
                    onConfirmSwitch = vm::confirmSwitch,
                    onCancelSwitch = vm::cancelSwitch,
                    onTrust = vm::trust,
                    onCancelVerify = vm::cancelVerify,
                ),
                appSnackbar,
            )
        }

        composable<Routes.Mismatch> {
            val state by container.tunnelController.state.collectAsStateWithLifecycle()
            val active by container.tunnelController.activeProfile.collectAsStateWithLifecycle()
            val s = state as? TunnelState.NeedsAttention
            val profile = active?.let { a -> stored?.profiles?.firstOrNull { it.id == a.id } ?: a }
            if (s == null || profile == null) {
                // The error is gone (acknowledged, or a new connection): nothing to show.
                LaunchedEffect(Unit) { nav.popBackStack() }
            } else {
                MismatchScreen(
                    profile, s.receivedHostKey,
                    onDisconnect = {
                        container.tunnelController.acknowledgeError()
                        nav.popBackStack()
                    },
                    onReview = {
                        nav.popBackStack()
                        nav.navigate(Routes.Profile(profile.id, Routes.FOCUS_IDENTITY))
                    },
                )
            }
        }

        composable<Routes.Onboarding> { entry ->
            val route = entry.toRoute<Routes.Onboarding>()
            val vm: OnboardingViewModel = viewModel(factory = OnboardingViewModel.factory(container, route.step))
            OnboardingRoute(
                vm,
                onFinished = { nav.popBackStack(Routes.Home, inclusive = false) },
                onConnect = { id ->
                    nav.popBackStack(Routes.Home, inclusive = false)
                    connect(id)
                },
            )
        }

        composable<Routes.Profile> { entry ->
            val route = entry.toRoute<Routes.Profile>()
            val vm: ProfileEditorViewModel = viewModel(factory = ProfileEditorViewModel.factory(container, route.id))
            ProfileEditorRoute(
                vm,
                EditorNav(
                    onBack = { nav.popBackStack() },
                    onSaved = { nav.popBackStack() },
                    onDeleted = {
                        nav.popBackStack(Routes.Home, inclusive = false)
                        scope.launch { appSnackbar.showSnackbar(resources.getString(R.string.profile_deleted)) }
                    },
                    onCreateKey = { nav.navigate(Routes.Keys(create = true)) },
                    onImportKey = { nav.navigate(Routes.Keys(import = true)) },
                    onPickApps = { nav.navigate(Routes.AppPicker) },
                ),
                route.focus,
            )
        }

        composable<Routes.AppPicker> {
            // The editor's ViewModel, from the entry below this one.
            val parent = remember(it) { nav.previousBackStackEntry }
            if (parent == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
                return@composable
            }
            val vm: ProfileEditorViewModel = viewModel(parent)
            val ui by vm.ui.collectAsStateWithLifecycle()
            val apps by vm.apps.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { vm.loadApps() }
            AppPickerScreen(
                ui.draft.appsMode, apps, ui.draft.packages, vm::setPackages,
                onBack = { nav.popBackStack() },
                icon = rememberAppIcons(container.installedApps::icon),
            )
        }

        composable<Routes.Keys> { entry ->
            val route = entry.toRoute<Routes.Keys>()
            val vm: KeysViewModel = viewModel(factory = KeysViewModel.factory(container))
            val ui by vm.ui.collectAsStateWithLifecycle()
            val snackbar = appSnackbar
            LaunchedEffect(Unit) {
                // Opened from the editor's "Create key" / "Import key": the sheet right away.
                if (route.create) vm.open(KeySheet.Create)
                if (route.import) vm.open(KeySheet.Import)
            }
            LaunchedEffect(Unit) {
                vm.keysEvents.collect { e ->
                    val msg = when (e) {
                        KeysEvent.Created -> R.string.key_created_toast
                        KeysEvent.Imported -> R.string.key_imported_toast
                        KeysEvent.Renamed -> R.string.key_renamed
                        KeysEvent.Deleted -> R.string.key_deleted
                    }
                    // Back to the editor that asked for the key, which selects it.
                    if ((route.create || route.import) && (e == KeysEvent.Created || e == KeysEvent.Imported)) nav.popBackStack()
                    else scope.launch { snackbar.showSnackbar(resources.getString(msg)) }
                }
            }
            KeysScreen(
                ui, onBack = { nav.popBackStack() }, onOpen = { nav.navigate(Routes.KeyDetail(it.id)) },
                onCreate = { vm.open(KeySheet.Create) }, onImport = { vm.open(KeySheet.Import) }, snackbar = snackbar,
            )
            KeySheets(vm, ui.sheet, ui.busy, ui.importError, ui.hasStrongBox, ui.hasHardwareKeystore)
        }

        composable<Routes.KeyDetail> { entry ->
            val route = entry.toRoute<Routes.KeyDetail>()
            val vm: KeysViewModel = viewModel(factory = KeysViewModel.factory(container))
            val ui by vm.ui.collectAsStateWithLifecycle()
            val snackbar = appSnackbar
            LaunchedEffect(Unit) {
                vm.keysEvents.collect { e ->
                    if (e == KeysEvent.Renamed) scope.launch { snackbar.showSnackbar(resources.getString(R.string.key_renamed)) }
                    if (e == KeysEvent.Deleted) scope.launch { appSnackbar.showSnackbar(resources.getString(R.string.key_deleted)) }
                }
            }
            val key = ui.keys.firstOrNull { it.id == route.id }
            if (key == null) {
                if (ui.loaded) LaunchedEffect(Unit) { nav.popBackStack() }
                return@composable
            }
            KeyDetailScreen(
                key, ui.usedBy[key.id].orEmpty(),
                onBack = { nav.popBackStack() },
                onRename = { vm.requestRename(key) },
                onDelete = { vm.requestDelete(key) },
                onShowQr = { vm.open(KeySheet.Qr(it)) },
                onCopied = { scope.launch { snackbar.showSnackbar(resources.getString(R.string.copied)) } },
                snackbar = snackbar,
            )
            KeySheets(vm, ui.sheet, ui.busy, ui.importError, ui.hasStrongBox, ui.hasHardwareKeystore)
            when (val d = ui.dialog) {
                is KeyDialog.Rename -> RenameKeyDialog(d.key, { vm.rename(d.key, it) }, vm::dismissDialog)
                is KeyDialog.InUse -> KeyInUseDialog(d.key, d.profiles, { p -> vm.dismissDialog(); nav.navigate(Routes.Profile(p.id)) }, vm::dismissDialog)
                // Once deleted, the key is gone from the list and this screen pops itself (above).
                is KeyDialog.Delete -> DeleteKeyDialog(d.key, { vm.delete(d.key) }, vm::dismissDialog)
                null -> {}
            }
        }

        composable<Routes.Diagnostics> { entry ->
            val route = entry.toRoute<Routes.Diagnostics>()
            val vm: DiagnosticsViewModel = viewModel(factory = DiagnosticsViewModel.factory(container))
            val ui by vm.ui.collectAsStateWithLifecycle()
            val snackbar = remember { SnackbarHostState() }
            val pager = rememberPagerState(route.tab.coerceIn(0, DiagTab.entries.lastIndex)) { DiagTab.entries.size }
            // Open flows are polled only while their tab is on screen and the app is visible.
            LifecycleStartEffect(pager.currentPage) {
                vm.watchFlows(pager.currentPage == DiagTab.CONNECTIONS.ordinal)
                onStopOrDispose { vm.watchFlows(false) }
            }
            val titles = DiagnosticsReport.Titles(
                stringResource(R.string.diag_report_header),
                stringResource(R.string.tab_events),
                stringResource(R.string.tab_dns),
                stringResource(R.string.diag_report_active),
                stringResource(R.string.diag_report_failed),
            )
            DiagnosticsScreen(
                ui,
                DiagnosticsActions(
                    onBack = { nav.popBackStack() },
                    onTab = vm::showTab,
                    onLevel = vm::setMinLevel,
                    onComponent = vm::toggle,
                    onPause = vm::togglePause,
                    onShare = { context.shareTextFile(vm.reportFileName(), vm.report(titles)) },
                    onCopyAll = {
                        scope.launch {
                            clipboard.copyText(resources.getString(R.string.diagnostics), vm.report(titles))
                            snackbar.showSnackbar(resources.getString(R.string.copied))
                        }
                    },
                    onClear = {
                        vm.clear()
                        scope.launch {
                            val r = snackbar.showSnackbar(resources.getString(R.string.cleared), resources.getString(R.string.undo), duration = SnackbarDuration.Short)
                            if (r == SnackbarResult.ActionPerformed) vm.undoClear()
                        }
                    },
                    onCopyName = { name ->
                        scope.launch {
                            clipboard.copyText(name, name)
                            snackbar.showSnackbar(resources.getString(R.string.copied))
                        }
                    },
                    onConnect = { stored?.let { st -> (st.defaultProfileId ?: st.profiles.firstOrNull()?.id)?.let(connect) } },
                ),
                snackbar,
                pager,
            )
        }

        composable<Routes.Settings> {
            val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
            val ui by vm.ui.collectAsStateWithLifecycle()
            SettingsScreen(
                ui, versionLine(resources),
                SettingsActions(
                    onBack = { nav.popBackStack() },
                    onDefault = { vm.setDefault(it) },
                    onRequireUnlock = { vm.setRequireUnlock(it) },
                    onVpnSettings = { context.openVpnSettings() },
                    onNotifications = { context.openNotificationSettings() },
                    onTheme = { vm.setTheme(it) },
                    onWallpaper = { vm.setWallpaperColors(it) },
                    onRunSetup = { nav.navigate(Routes.Onboarding(step = it)) },
                    onAbout = { nav.navigate(Routes.About) },
                    onLicenses = { nav.navigate(Routes.OpenSourceLicenses) },
                ),
            )
        }

        composable<Routes.About> {
            AboutScreen(
                versionLine(resources), Mobile.version(), BuildConfig.SOURCE_URL,
                onBack = { nav.popBackStack() },
                onLicense = { nav.navigate(Routes.License) },
                onSource = { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(BuildConfig.SOURCE_URL))) },
                onLicenses = { nav.navigate(Routes.OpenSourceLicenses) },
            )
        }
        composable<Routes.License> { LicenseScreen { nav.popBackStack() } }
        composable<Routes.OpenSourceLicenses> { OpenSourceLicensesScreen { nav.popBackStack() } }
    }
}

@Composable
private fun KeySheets(vm: KeysViewModel, sheet: KeySheet?, busy: Boolean, importError: String?, hasStrongBox: Boolean, hardware: Boolean) {
    when (sheet) {
        KeySheet.Create -> CreateKeySheet(hasStrongBox, busy, androidx.compose.ui.res.stringResource(R.string.key_new_default_name), vm::create, vm::closeSheet, hardware)
        KeySheet.Import -> ImportKeySheet(busy, importError, vm::import, vm::clearImportError, vm::closeSheet)
        is KeySheet.Qr -> QrSheet(sheet.line, vm::closeSheet)
        null -> {}
    }
}

private fun versionLine(resources: android.content.res.Resources): String =
    resources.getString(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

private inline fun <reified T : Any> androidx.navigation.NavDestination.hasRouteOf(): Boolean =
    hasRoute(T::class)

