// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel

import android.app.Application
import android.os.StrictMode
import com.github.dennisklein.sshovel.tunnel.TunnelNotifications

class SshovelApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Debug builds report main-thread disk/network access and leaks (M8); logged as
        // "StrictMode policy violation", which the acceptance run counts.
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
        }
        TunnelNotifications.createChannels(this)
        container = AppContainer(this)
    }
}
