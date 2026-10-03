// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.tunnel

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Process
import android.system.OsConstants
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Which app a tunneled connection belongs to, for Diagnostics ("Chrome · wiki.corp.example").
 * getConnectionOwnerUid answers only the app providing the VPN, and only while the socket exists,
 * so it's asked when a flow opens or fails (the core reports failures before its RST). Apps the
 * package-visibility rules hide show as unknown.
 */
class FlowAttribution(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val pm = context.packageManager
    private val labels = ConcurrentHashMap<Int, String>()

    /** The app label for the connection [src] → [dst] ("ip:port"), or null if unknown. */
    fun appFor(src: String, dst: String): String? {
        val local = socketAddress(src) ?: return null
        val remote = socketAddress(dst) ?: return null
        val uid = try {
            cm.getConnectionOwnerUid(OsConstants.IPPROTO_TCP, local, remote)
        } catch (_: RuntimeException) {
            return null // SecurityException when we aren't the active VPN any more
        }
        if (uid == Process.INVALID_UID) return null
        return labels[uid] ?: label(uid)?.also { labels[uid] = it }
    }

    private fun label(uid: Int): String? {
        val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: return null
        return try {
            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)).loadLabel(pm).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    companion object {
        /** "10.0.0.1:443" or "[fd00::1]:443"; numeric only, so this never does a DNS lookup. */
        fun socketAddress(s: String): InetSocketAddress? {
            val i = s.lastIndexOf(':')
            if (i <= 0) return null
            val host = s.substring(0, i).removePrefix("[").removeSuffix("]")
            val port = s.substring(i + 1).toIntOrNull() ?: return null
            if (host.isEmpty() || !host.all { it.isDigit() || it == '.' || it == ':' || it.lowercaseChar() in 'a'..'f' }) return null
            return try {
                InetSocketAddress(InetAddress.getByName(host), port)
            } catch (_: Exception) {
                null
            }
        }
    }
}
