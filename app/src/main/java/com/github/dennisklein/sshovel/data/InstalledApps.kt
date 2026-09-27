// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A launchable app for the app picker (DESIGN_BRIEF §5.7). */
data class AppInfo(val packageName: String, val label: String, val isSystem: Boolean)

/**
 * Launchable apps, via the launcher intent (the manifest declares that <queries> filter), minus
 * sshovel itself: its own traffic never goes through its tunnel.
 */
class InstalledApps(private val context: Context) {
    private val pm: PackageManager get() = context.packageManager

    suspend fun launchable(): List<AppInfo> = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { AppInfo(it.packageName, it.loadLabel(pm).toString(), it.flags and ApplicationInfo.FLAG_SYSTEM != 0) }
            .sortedBy { it.label.lowercase() }
    }

    /** Display labels for [packages]; uninstalled ones show their package name. */
    suspend fun labels(packages: List<String>): List<String> = withContext(Dispatchers.IO) {
        packages.map { pkg ->
            try {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)).loadLabel(pm).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                pkg
            }
        }
    }

    fun icon(packageName: String): Drawable? = try {
        pm.getApplicationIcon(packageName)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
