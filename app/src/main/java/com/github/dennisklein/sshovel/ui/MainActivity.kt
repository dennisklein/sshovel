// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.dennisklein.sshovel.SshovelApplication
import com.github.dennisklein.sshovel.ui.screens.consent.VpnConsentActivity
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {
    private val container get() = (application as SshovelApplication).container

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /**
     * Screens to open once composed: Diagnostics from the notification, and debug builds' "open"
     * command (tools/android-env screenshots).
     */
    private val opens = Channel<Pair<String, String?>>(Channel.BUFFERED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            SshovelTheme(settings) {
                SshovelApp(container, ::connect, opens.receiveAsFlow())
            }
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        intent.getIntExtra(EXTRA_DIAGNOSTICS_TAB, -1).takeIf { it >= 0 }?.let { opens.trySend("diagnostics" to it.toString()) }
        DebugCommands.handle(this, intent, ::connect) { screen, arg -> opens.trySend(screen to arg) }
    }

    /** Connects, via the explainer and Android's consent dialog if needed (DESIGN_BRIEF §5.4). */
    private fun connect(profileId: String?) {
        val id = profileId ?: container.profiles.defaultProfileNow()?.id ?: return
        if (VpnService.prepare(this) != null) {
            startActivity(VpnConsentActivity.intent(this, id))
        } else {
            container.tunnelController.connect(id)
        }
    }

    companion object {
        /** Opens Diagnostics on this tab (0 Events, 1 DNS, 2 Connections). */
        const val EXTRA_DIAGNOSTICS_TAB = "diagnostics_tab"
    }
}
