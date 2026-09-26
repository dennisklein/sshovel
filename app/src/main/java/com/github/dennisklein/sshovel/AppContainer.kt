// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel

import android.content.Context
import androidx.datastore.dataStoreFile
import com.github.dennisklein.sshovel.data.AppStore
import com.github.dennisklein.sshovel.data.ProfileRepository
import com.github.dennisklein.sshovel.data.VariantSeed
import com.github.dennisklein.sshovel.keys.GoKeyCodec
import com.github.dennisklein.sshovel.keys.ImportedKeyVault
import com.github.dennisklein.sshovel.keys.KeyRepository
import com.github.dennisklein.sshovel.keys.KeystoreKeys
import com.github.dennisklein.sshovel.tunnel.HostKeyVerifier
import com.github.dennisklein.sshovel.tunnel.NetworkMonitor
import com.github.dennisklein.sshovel.tunnel.ServiceTunnelCommands
import com.github.dennisklein.sshovel.tunnel.TunnelController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Manual dependency injection: one instance per process, created by [SshovelApplication]. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val networkMonitor = NetworkMonitor(context, appScope)
    val store = AppStore(context.dataStoreFile("sshovel.json"), CoroutineScope(SupervisorJob() + Dispatchers.IO))
    val profiles = ProfileRepository(store, appScope)
    val keys = KeyRepository(
        store = store,
        hardware = KeystoreKeys(),
        vault = ImportedKeyVault(File(context.noBackupFilesDir, "vault")) { ImportedKeyVault.keystoreKey() },
        codec = GoKeyCodec(),
        scope = appScope,
    )
    val hostKeys = HostKeyVerifier(networkMonitor, keys)
    val tunnelController = TunnelController(ServiceTunnelCommands(context), networkMonitor.available, appScope)

    init {
        appScope.launch(Dispatchers.IO) { VariantSeed.seed(context, profiles, keys) }
    }
}
