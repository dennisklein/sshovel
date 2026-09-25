// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel

import android.content.Context
import com.github.dennisklein.sshovel.data.ProfileRepository
import com.github.dennisklein.sshovel.data.VariantProfiles
import com.github.dennisklein.sshovel.tunnel.NetworkMonitor
import com.github.dennisklein.sshovel.tunnel.ServiceTunnelCommands
import com.github.dennisklein.sshovel.tunnel.TunnelController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency injection: one instance per process, created by [SshovelApplication]. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val networkMonitor = NetworkMonitor(context, appScope)
    val profiles: ProfileRepository = VariantProfiles.create(context)
    val tunnelController = TunnelController(ServiceTunnelCommands(context), networkMonitor.available, appScope)
}
