// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.format

import android.content.Context
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.tunnel.Codes
import java.util.Locale

/** 1536 → "1.5 KB", 50_540_000 → "48.2 MB" (handoff H3): binary units, one decimal below 100. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return String.format(Locale.getDefault(), if (v < 100) "%.1f %s" else "%.0f %s", v, units[i])
}

/** 1902 → "1,902" in the user's locale. */
fun formatCount(n: Long): String = String.format(Locale.getDefault(), "%,d", n)

/** Uptime as the hero shows it: "1 h 24 min", "7 min" (whole minutes; handoff H3). */
fun formatUptimeShort(context: Context, seconds: Long): String {
    val h = (seconds / 3600).toInt()
    val m = ((seconds % 3600) / 60).toInt()
    return if (h > 0) context.getString(R.string.uptime_h_min, h, m) else context.getString(R.string.uptime_min, m)
}

/** An ISO-8601 instant as "12 Mar 2026" in the user's locale, or null if unparsable. */
fun formatDate(iso: String?): String? = iso?.let {
    runCatching {
        java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.parse(it))
    }.getOrNull()
}

/** 3725 → "1:02:05", 65 → "1:05". */
fun formatUptime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

/** The catalog entry for a state or warning code (DESIGN_BRIEF §8): title, body, primary action. */
data class ErrorCopy(val title: Int, val body: Int, val action: Int?)

/** DESIGN_BRIEF §8's catalog; null for codes that aren't tunnel states (import, discovery, flows). */
fun errorCopy(code: String): ErrorCopy? = when (code) {
    Codes.AUTH_FAILED -> ErrorCopy(R.string.err_auth_title, R.string.err_auth_body, R.string.err_auth_action)
    Codes.HOST_UNREACHABLE -> ErrorCopy(R.string.err_unreachable_title, R.string.err_unreachable_body, R.string.err_unreachable_action)
    Codes.HOST_KEY_UNVERIFIED -> ErrorCopy(R.string.err_unverified_title, R.string.err_unverified_body, R.string.err_unverified_action)
    Codes.HOST_KEY_MISMATCH -> ErrorCopy(R.string.err_mismatch_title, R.string.err_mismatch_body, R.string.err_mismatch_action)
    Codes.FORWARDING_DENIED -> ErrorCopy(R.string.err_forwarding_title, R.string.err_forwarding_body, R.string.err_forwarding_action)
    Codes.DNS_UNREACHABLE -> ErrorCopy(R.string.err_dns_title, R.string.err_dns_body, R.string.err_dns_action)
    Codes.NETWORK_LOST -> ErrorCopy(R.string.err_network_title, R.string.err_network_body, null)
    Codes.VPN_REVOKED -> ErrorCopy(R.string.err_revoked_title, R.string.err_revoked_body, R.string.err_revoked_action)
    Codes.VPN_PERMISSION -> ErrorCopy(R.string.err_permission_title, R.string.err_permission_body, R.string.err_permission_action)
    Codes.KEY_UNAVAILABLE -> ErrorCopy(R.string.err_key_unavailable_title, R.string.err_key_unavailable_body, R.string.err_key_unavailable_action)
    Codes.INTERNAL -> ErrorCopy(R.string.err_internal_title, R.string.err_internal_body, R.string.err_internal_action)
    else -> null
}

/** Title and next step for a failed flow's reason (Diagnostics, Connections; handoff G3). */
fun failureCopy(reason: String): Pair<Int, Int?>? = when (reason) {
    Codes.FORWARDING_DENIED -> R.string.conn_refused_title to R.string.conn_refused_body
    Codes.DEST_UNREACHABLE -> R.string.conn_unreachable_title to R.string.conn_unreachable_body
    Codes.DEST_TIMEOUT -> R.string.conn_timeout_title to null
    Codes.TUNNEL_DOWN -> R.string.conn_tunnel_down_title to null
    else -> null
}

/** Title and body for an error code (DESIGN_BRIEF §8); unknown codes read as INTERNAL. */
fun errorText(context: Context, code: String, profile: Profile?, keyName: String? = null): Pair<String, String> {
    val host = profile?.let { "${it.server.host}:${it.server.port}" } ?: ""
    val userHost = profile?.let { "${it.server.user}@${it.server.host}" } ?: ""
    val key = keyName ?: profile?.auth?.alias ?: ""
    val copy = errorCopy(code) ?: errorCopy(Codes.INTERNAL)!!
    val args: Array<Any> = when (code) {
        Codes.AUTH_FAILED -> arrayOf(userHost, key)
        Codes.HOST_UNREACHABLE -> arrayOf(host)
        Codes.HOST_KEY_MISMATCH -> arrayOf(profile?.server?.host ?: "")
        Codes.DNS_UNREACHABLE -> arrayOf(profile?.dns?.server ?: "")
        Codes.KEY_UNAVAILABLE -> arrayOf(key)
        else -> emptyArray()
    }
    return context.getString(copy.title) to context.getString(copy.body, *args)
}
