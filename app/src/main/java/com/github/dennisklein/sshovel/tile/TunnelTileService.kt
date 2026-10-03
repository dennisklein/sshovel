// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.github.dennisklein.sshovel.BuildConfig
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.SshovelApplication
import com.github.dennisklein.sshovel.ui.MainActivity
import com.github.dennisklein.sshovel.ui.screens.consent.VpnConsentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The Quick Settings tile, the app's main UI (DESIGN_BRIEF §2, §7; ARCHITECTURE §7). An active
 * tile: SystemUI binds it when [requestUpdate] asks, and it renders the shared tunnel state.
 */
class TunnelTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null
    private val container get() = (application as SshovelApplication).container

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch {
            combine(
                container.tunnelController.state,
                container.tunnelController.activeProfile,
                container.profiles.profiles,
                container.profiles.defaultProfileId,
            ) { state, active, all, defaultId ->
                TileContent.of(state, active, all.firstOrNull { it.id == defaultId } ?: all.firstOrNull())
            }.collect(::render)
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        scope.launch {
            val requireUnlock = container.settings.current().requireUnlock
            // M0: SystemUI shows the bouncer on a secured lock screen before onClick; keep the
            // check for devices that don't (ARCHITECTURE §7).
            if (requireUnlock && isLocked) unlockAndRun { scope.launch { act() } } else act()
        }
    }

    private suspend fun act() {
        val controller = container.tunnelController
        val default = container.profiles.defaultProfile()
        val action = tileAction(controller.state.value, default, VpnService.prepare(this) == null)
        if (BuildConfig.DEBUG) Log.i(TAG, "click $action")
        when (action) {
            TileAction.CONNECT -> controller.connect(default!!.id)
            TileAction.DISCONNECT -> controller.disconnect()
            TileAction.ASK_CONSENT -> collapseTo(VpnConsentActivity.intent(this, default!!.id))
            TileAction.OPEN_APP -> collapseTo(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun collapseTo(intent: Intent) {
        startActivityAndCollapse(
            PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )
    }

    private fun render(c: TileContent) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_label)
        tile.subtitle = c.subtitleText ?: getString(c.subtitleRes)
        // TalkBack: label "sshovel", state = the subtitle, role Switch (handoff §5).
        tile.stateDescription = tile.subtitle
        tile.state = if (c.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, if (c.attention) R.drawable.ic_sshovel_attention else R.drawable.ic_sshovel)
        tile.updateTile()
        if (BuildConfig.DEBUG) Log.i(TAG, "tile ${tile.subtitle} ${if (c.active) "active" else "inactive"}")
    }

    companion object {
        private const val TAG = "sshovel/Tile"

        /** Asks SystemUI to bind the tile so it re-renders (active tile, ARCHITECTURE §7). */
        fun requestUpdate(context: Context) =
            requestListeningState(context, ComponentName(context, TunnelTileService::class.java))
    }
}
