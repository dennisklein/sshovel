// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.tunnel

import com.github.dennisklein.sshovel.data.HostKeyInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Error and warning codes (ARCHITECTURE §8). */
object Codes {
    const val AUTH_FAILED = "AUTH_FAILED"
    const val HOST_UNREACHABLE = "HOST_UNREACHABLE"
    const val HOST_KEY_UNVERIFIED = "HOST_KEY_UNVERIFIED"
    const val HOST_KEY_MISMATCH = "HOST_KEY_MISMATCH"
    const val NETWORK_LOST = "NETWORK_LOST"
    const val VPN_REVOKED = "VPN_REVOKED"
    const val VPN_PERMISSION = "VPN_PERMISSION"
    const val KEY_UNAVAILABLE = "KEY_UNAVAILABLE"
    const val INTERNAL = "INTERNAL"
    const val FORWARDING_DENIED = "FORWARDING_DENIED"
    const val DNS_UNREACHABLE = "DNS_UNREACHABLE"

    // Key import only (mobile.ImportKey).
    const val KEY_PASSPHRASE = "KEY_PASSPHRASE"
    const val KEY_UNSUPPORTED = "KEY_UNSUPPORTED"
    const val KEY_PUTTY = "KEY_PUTTY"

    /** Go errors read "CODE: detail". */
    fun of(t: Throwable): String {
        val code = t.message.orEmpty().substringBefore(':')
        return if (code.isNotEmpty() && code.all { it.isUpperCase() || it == '_' }) code else INTERNAL
    }
}

/** The one process-wide tunnel state (DESIGN_BRIEF §4), shown by app, tile, and notification. */
sealed interface TunnelState {
    data object Off : TunnelState

    /** [step]: resolving, identity, auth, tunnel. */
    data class Connecting(val step: String) : TunnelState

    data class On(val warnings: List<String> = emptyList()) : TunnelState

    /** [nextRetryAtMillis] is null while a dial is in progress. */
    data class Reconnecting(
        val reason: String,
        val attempt: Int,
        val nextRetryAtMillis: Long?,
        val code: String?,
    ) : TunnelState

    /** [receivedHostKey]: the key the server presented, for HOST_KEY_UNVERIFIED / _MISMATCH. */
    data class NeedsAttention(
        val code: String,
        val detail: String = "",
        val receivedHostKey: HostKeyInfo? = null,
    ) : TunnelState

    data object Disconnecting : TunnelState

    val isActive: Boolean get() = this !is Off && this !is NeedsAttention
}

/** Status JSON as the Go engine reports it (ARCHITECTURE §8, "State JSON"). */
@Serializable
internal data class EngineStatus(
    val state: String,
    val code: String = "",
    val detail: String = "",
    val step: String? = null,
    val reason: String? = null,
    val attempt: Int = 0,
    val nextRetryAt: Long = 0,
    val warnings: List<String> = emptyList(),
    val hostKey: HostKeyInfo? = null,
) {
    /** Maps to [TunnelState]; "sshReady" shows as the last Connecting step. */
    fun toTunnelState(): TunnelState = when (state) {
        "off" -> TunnelState.Off
        "connecting" -> TunnelState.Connecting(step ?: "resolving")
        "sshReady" -> TunnelState.Connecting("tunnel")
        "on" -> TunnelState.On(warnings)
        "reconnecting" -> TunnelState.Reconnecting(
            reason = reason ?: "",
            attempt = attempt,
            nextRetryAtMillis = nextRetryAt.takeIf { it > 0 },
            code = code.ifEmpty { null },
        )
        "needsAttention" -> TunnelState.NeedsAttention(code.ifEmpty { Codes.INTERNAL }, detail, hostKey)
        "disconnecting" -> TunnelState.Disconnecting
        else -> TunnelState.NeedsAttention(Codes.INTERNAL, "unknown engine state $state")
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(s: String): EngineStatus = json.decodeFromString(serializer(), s)
    }
}

/** Engine stats (ARCHITECTURE §7). */
@Serializable
data class TunnelStats(
    val uptimeSec: Long = 0,
    val bytesIn: Long = 0,
    val bytesOut: Long = 0,
    val activeFlows: Long = 0,
    val dnsTunneled: Long = 0,
    val dnsDirect: Long = 0,
    val droppedUdp: Long = 0,
    val droppedIcmp: Long = 0,
    val lastError: String = "",
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(s: String): TunnelStats = json.decodeFromString(serializer(), s)
    }
}
