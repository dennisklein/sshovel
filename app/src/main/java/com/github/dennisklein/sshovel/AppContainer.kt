// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel

import android.content.Context
import androidx.datastore.dataStoreFile
import com.github.dennisklein.sshovel.data.AppStore
import com.github.dennisklein.sshovel.data.GoProfileValidator
import com.github.dennisklein.sshovel.data.InstalledApps
import com.github.dennisklein.sshovel.data.ProfileRepository
import com.github.dennisklein.sshovel.data.SettingsRepository
import com.github.dennisklein.sshovel.data.VariantSeed
import com.github.dennisklein.sshovel.diagnostics.Diagnostics
import com.github.dennisklein.sshovel.keys.GoKeyCodec
import com.github.dennisklein.sshovel.keys.ImportedKeyVault
import com.github.dennisklein.sshovel.keys.KeyRepository
import com.github.dennisklein.sshovel.keys.KeystoreKeys
import com.github.dennisklein.sshovel.tile.TunnelTileService
import com.github.dennisklein.sshovel.tunnel.FlowAttribution
import com.github.dennisklein.sshovel.tunnel.HostKeyVerifier
import com.github.dennisklein.sshovel.tunnel.NetworkMonitor
import com.github.dennisklein.sshovel.tunnel.ServerChecks
import com.github.dennisklein.sshovel.tunnel.ServiceTunnelCommands
import com.github.dennisklein.sshovel.tunnel.TunnelController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File

/** Manual dependency injection: one instance per process, created by [SshovelApplication]. */
class AppContainer(context: Context) {
    val installedApps = InstalledApps(context)

    /** Keys are created in StrongBox when the device has it (KeystoreKeys). */
    val hasStrongBox: Boolean = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val networkMonitor = NetworkMonitor(context, appScope)
    val store = AppStore(context.dataStoreFile("sshovel.json"), CoroutineScope(SupervisorJob() + Dispatchers.IO))
    val profiles = ProfileRepository(store, appScope, GoProfileValidator())
    val keys = KeyRepository(
        store = store,
        hardware = KeystoreKeys(),
        vault = ImportedKeyVault(File(context.noBackupFilesDir, "vault")) { ImportedKeyVault.keystoreKey() },
        codec = GoKeyCodec(),
        scope = appScope,
    )
    val hostKeys = HostKeyVerifier(networkMonitor, keys)
    val serverChecks = ServerChecks(networkMonitor, keys)
    val diagnostics = Diagnostics()
    val dnsLog get() = diagnostics.dns
    val flowAttribution = FlowAttribution(context)
    val settings = SettingsRepository(store, appScope)
    val tunnelController = TunnelController(ServiceTunnelCommands(context), networkMonitor.available, appScope)

    private val _seeded = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Debug builds seed a test-env profile at start; first-run onboarding waits for it. */
    val seeded: kotlinx.coroutines.flow.StateFlow<Boolean> = _seeded

    init {
        appScope.launch(Dispatchers.IO) {
            try {
                VariantSeed.seed(context, profiles, keys)
            } finally {
                _seeded.value = true
            }
        }
        // The tile is an active tile: it only re-renders when asked (ARCHITECTURE §7).
        appScope.launch {
            combine(tunnelController.state, profiles.defaultProfileId) { s, d -> s to d }
                .distinctUntilChanged()
                .collect { TunnelTileService.requestUpdate(context) }
        }
    }
}
