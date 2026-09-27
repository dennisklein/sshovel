// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.consent

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.github.dennisklein.sshovel.SshovelApplication
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.ui.theme.SshovelTheme
import kotlinx.coroutines.launch

/**
 * VPN consent (DESIGN_BRIEF §5.4, §6 flow 3): the explainer, then Android's dialog, then connect.
 * Opened by the tile (startActivityAndCollapse) and by the app whenever VpnService.prepare()
 * returns an intent.
 */
class VpnConsentActivity : ComponentActivity() {
    private val container get() = (application as SshovelApplication).container
    private var profile by mutableStateOf<Profile?>(null)
    private var denied by mutableStateOf(false)

    private val systemConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(this) == null) connectAndFinish() else denied = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (VpnService.prepare(this) == null) return connectAndFinish()
        denied = savedInstanceState?.getBoolean(KEY_DENIED) ?: false
        lifecycleScope.launch {
            profile = intent.getStringExtra(EXTRA_PROFILE_ID)?.let { container.profiles.profile(it) }
                ?: container.profiles.defaultProfile()
        }
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            SshovelTheme(settings) {
                if (denied) {
                    VpnDeniedScreen(
                        onTryAgain = ::askSystem,
                        onNotNow = ::finish,
                        onOpenVpnSettings = { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) },
                        onClose = ::finish,
                    )
                } else {
                    VpnExplainerScreen(profile, onContinue = ::askSystem, onClose = ::finish)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_DENIED, denied)
    }

    private fun askSystem() {
        VpnService.prepare(this)?.let(systemConsent::launch) ?: connectAndFinish()
    }

    private fun connectAndFinish() {
        lifecycleScope.launch {
            val id = intent.getStringExtra(EXTRA_PROFILE_ID) ?: container.profiles.defaultProfile()?.id
            id?.let(container.tunnelController::connect)
            finish()
        }
    }

    companion object {
        private const val EXTRA_PROFILE_ID = "profileId"
        private const val KEY_DENIED = "denied"

        /** Consent, then connect [profileId] (the default profile if null). */
        fun intent(context: Context, profileId: String?): Intent =
            Intent(context, VpnConsentActivity::class.java)
                .putExtra(EXTRA_PROFILE_ID, profileId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
