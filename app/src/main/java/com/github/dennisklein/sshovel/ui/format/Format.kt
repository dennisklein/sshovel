// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.format

import android.content.Context
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.tunnel.Codes
import java.util.Locale

/** 1536 → "1.5 KB". Binary units with decimal-style labels, like Android's own data usage. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return String.format(Locale.ROOT, if (v < 10) "%.1f %s" else "%.0f %s", v, units[i])
}

/** 3725 → "1:02:05", 65 → "1:05". */
fun formatUptime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

/** Title and body for an error code (DESIGN_BRIEF §8). */
fun errorText(context: Context, code: String, profile: Profile?): Pair<String, String> {
    val host = profile?.let { "${it.server.host}:${it.server.port}" } ?: ""
    val userHost = profile?.let { "${it.server.user}@${it.server.host}" } ?: ""
    val key = profile?.auth?.alias ?: ""
    fun s(id: Int, vararg args: Any) = context.getString(id, *args)
    return when (code) {
        Codes.AUTH_FAILED -> s(R.string.err_auth_title) to s(R.string.err_auth_body, userHost, key)
        Codes.HOST_UNREACHABLE -> s(R.string.err_unreachable_title) to s(R.string.err_unreachable_body, host)
        Codes.HOST_KEY_UNVERIFIED -> s(R.string.err_unverified_title) to s(R.string.err_unverified_body)
        Codes.HOST_KEY_MISMATCH -> s(R.string.err_mismatch_title) to s(R.string.err_mismatch_body, profile?.server?.host ?: "")
        Codes.FORWARDING_DENIED -> s(R.string.err_forwarding_title) to s(R.string.err_forwarding_body)
        Codes.DNS_UNREACHABLE -> s(R.string.err_dns_title) to s(R.string.err_dns_body, profile?.dns?.server ?: "")
        Codes.NETWORK_LOST -> s(R.string.err_network_title) to s(R.string.err_network_body)
        Codes.VPN_REVOKED -> s(R.string.err_revoked_title) to s(R.string.err_revoked_body)
        Codes.VPN_PERMISSION -> s(R.string.err_permission_title) to s(R.string.err_permission_body)
        Codes.KEY_UNAVAILABLE -> s(R.string.err_key_unavailable_title) to s(R.string.err_key_unavailable_body, key)
        else -> s(R.string.err_internal_title) to s(R.string.err_internal_body)
    }
}
