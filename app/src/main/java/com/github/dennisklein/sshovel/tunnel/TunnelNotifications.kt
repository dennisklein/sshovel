// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.ui.MainActivity
import com.github.dennisklein.sshovel.ui.format.errorText
import com.github.dennisklein.sshovel.ui.format.formatBytes

/** The ongoing status notification and error alerts (DESIGN_BRIEF §7, ARCHITECTURE §7). */
class TunnelNotifications(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    fun status(state: TunnelState, profile: Profile?, stats: TunnelStats): Notification {
        val name = profile?.name ?: context.getString(R.string.app_name)
        val b = Notification.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_sshovel)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openApp())
        when (state) {
            is TunnelState.On -> {
                b.setContentTitle(
                    if (state.warnings.isEmpty()) context.getString(R.string.notif_on, name)
                    else context.getString(R.string.notif_on_warning, name),
                )
                val subnets = profile?.routes?.size ?: 0
                b.setContentText(
                    context.getString(
                        R.string.notif_on_text,
                        context.resources.getQuantityString(R.plurals.profile_subnets, subnets, subnets),
                        context.resources.getQuantityString(R.plurals.active_connections, stats.activeFlows.toInt(), stats.activeFlows.toInt()),
                    ),
                )
                b.setSubText("↓ ${formatBytes(stats.bytesIn)} ↑ ${formatBytes(stats.bytesOut)}")
                b.addAction(action(R.string.action_disconnect, SshovelVpnService.ACTION_DISCONNECT))
            }
            is TunnelState.Reconnecting -> {
                b.setContentTitle(context.getString(R.string.notif_reconnecting, name))
                if (state.code == Codes.NETWORK_LOST) b.setContentText(context.getString(R.string.err_network_body))
                b.addAction(action(R.string.action_retry_now, SshovelVpnService.ACTION_RETRY))
                b.addAction(action(R.string.action_disconnect, SshovelVpnService.ACTION_DISCONNECT))
            }
            is TunnelState.Connecting -> {
                b.setContentTitle(context.getString(R.string.notif_connecting, name))
                b.setProgress(0, 0, true)
                b.addAction(action(R.string.action_cancel, SshovelVpnService.ACTION_DISCONNECT))
            }
            TunnelState.Disconnecting -> b.setContentTitle(context.getString(R.string.state_disconnecting))
            TunnelState.Off, is TunnelState.NeedsAttention -> b.setContentTitle(name)
        }
        return b.build()
    }

    fun showStatus(state: TunnelState, profile: Profile?, stats: TunnelStats) {
        if (state == TunnelState.Off || state is TunnelState.NeedsAttention) return
        nm.notify(STATUS_ID, status(state, profile, stats))
    }

    /** A normal-importance alert for errors that need the user. */
    fun showAlert(state: TunnelState.NeedsAttention, profile: Profile?) {
        val (title, body) = errorText(context, state.code, profile)
        nm.notify(
            ALERT_ID,
            Notification.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_sshovel_attention)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build(),
        )
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(label: Int, serviceAction: String): Notification.Action {
        val pi = PendingIntent.getService(
            context, serviceAction.hashCode(),
            Intent(context, SshovelVpnService::class.java).setAction(serviceAction),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(null, context.getString(label), pi).build()
    }

    companion object {
        const val CHANNEL_STATUS = "tunnel_status"
        const val CHANNEL_ALERTS = "tunnel_alerts"
        const val STATUS_ID = 1
        const val ALERT_ID = 2

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannels(
                listOf(
                    NotificationChannel(CHANNEL_STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW),
                    NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_problems), NotificationManager.IMPORTANCE_DEFAULT),
                ),
            )
        }
    }
}
