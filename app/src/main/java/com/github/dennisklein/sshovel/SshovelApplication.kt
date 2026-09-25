// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel

import android.app.Application
import com.github.dennisklein.sshovel.tunnel.TunnelNotifications

class SshovelApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        TunnelNotifications.createChannels(this)
        container = AppContainer(this)
    }
}
