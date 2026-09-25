// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui

import android.app.Activity
import android.content.Intent
import android.util.Log
import com.github.dennisklein.sshovel.SshovelApplication
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Debug builds only: adb hooks for the emulator runbook (tools/android-env), e.g.
 *
 *     adb shell am start -n com.github.dennisklein.sshovel/.ui.MainActivity --es cmd connect
 *     adb shell am start -n com.github.dennisklein.sshovel/.ui.MainActivity --es cmd fetch --es url http://wiki.corp.test/
 *
 * Results are logged under the tag "sshovel/Debug".
 */
object DebugCommands {
    private const val TAG = "sshovel/Debug"

    fun handle(activity: Activity, intent: Intent?, connect: () -> Unit) {
        val controller = (activity.application as SshovelApplication).container.tunnelController
        when (intent?.getStringExtra("cmd")) {
            "connect" -> connect()
            "disconnect" -> controller.disconnect()
            "retry" -> controller.retryNow()
            "state" -> Log.i(TAG, "state ${controller.state.value}")
            "fetch" -> intent.getStringExtra("url")?.let(::fetch)
        }
    }

    /** Fetches a URL from the app's own UID (which the VPN covers) and logs status, size, time. */
    private fun fetch(url: String) = thread(name = "debug-fetch") {
        val start = System.nanoTime()
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            val body = c.inputStream.use { it.readBytes() }
            val ms = (System.nanoTime() - start) / 1_000_000
            val title = Regex("<title>(.*?)</title>").find(String(body))?.groupValues?.get(1) ?: ""
            Log.i(TAG, "fetch $url -> ${c.responseCode} ${body.size} bytes ${ms}ms title=\"$title\"")
        } catch (e: Exception) {
            val ms = (System.nanoTime() - start) / 1_000_000
            Log.i(TAG, "fetch $url -> error ${e.javaClass.simpleName}: ${e.message} ${ms}ms")
        }
    }
}
