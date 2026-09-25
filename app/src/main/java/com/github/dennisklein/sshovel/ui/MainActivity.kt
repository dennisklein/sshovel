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
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.github.dennisklein.sshovel.SshovelApplication
import com.github.dennisklein.sshovel.tunnel.Codes
import com.github.dennisklein.sshovel.tunnel.TunnelState
import com.github.dennisklein.sshovel.ui.home.HomeScreen
import com.github.dennisklein.sshovel.ui.home.HomeViewModel
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as SshovelApplication).container
    private val home: HomeViewModel by viewModels { HomeViewModel.factory(container) }

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(this) == null) {
            home.connect()
        } else {
            container.tunnelController.onState(TunnelState.NeedsAttention(Codes.VPN_PERMISSION))
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            SshovelTheme {
                val ui by home.ui.collectAsStateWithLifecycle()
                HomeScreen(ui, onConnect = ::connect, onDisconnect = home::disconnect, onRetryNow = home::retryNow)
            }
        }
        DebugCommands.handle(this, intent, ::connect)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        DebugCommands.handle(this, intent, ::connect)
    }

    /** Asks for VPN consent first if needed (the explainer screen arrives in M5). */
    private fun connect() {
        val consent = VpnService.prepare(this)
        if (consent != null) vpnConsent.launch(consent) else home.connect()
    }
}
