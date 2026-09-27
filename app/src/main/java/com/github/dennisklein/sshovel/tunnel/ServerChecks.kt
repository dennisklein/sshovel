// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.tunnel

import com.github.dennisklein.sshovel.core.mobile.Mobile
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.keys.KeyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A route the server reported (ARCHITECTURE §6, Route discovery). */
@Serializable
data class DiscoveredRoute(val cidr: String, val dev: String = "", val isDefault: Boolean = false, val isLinkLocal: Boolean = false)

/** One "Test connection" check (handoff O6); see core/probe. */
@Serializable
data class ConnectionCheck(
    val id: String,
    val status: String,
    val ms: Long = 0,
    val target: String = "",
    val code: String = "",
    val detail: String = "",
) {
    companion object {
        const val REACHABLE = "reachable"
        const val IDENTITY = "identity"
        const val AUTH = "auth"
        const val FORWARDING = "forwarding"
        const val DNS = "dns"
        const val PASSED = "passed"
        const val FAILED = "failed"
        const val NOT_RUN = "notRun"
        const val SKIPPED = "skipped"
        val ORDER = listOf(REACHABLE, IDENTITY, AUTH, FORWARDING, DNS)
    }
}

/** A failed server check; [code] is from ARCHITECTURE §8 (ROUTE_DISCOVERY_UNAVAILABLE, …). */
class ServerCheckException(val code: String, cause: Throwable) : Exception(code, cause)

/**
 * One-off SSH connections outside the tunnel: route discovery (handoff D1–D3) and the
 * onboarding connection test (O6). Like [HostKeyVerifier], sockets are protected through the
 * running VPN service, if any, and names resolve on the underlying network.
 */
class ServerChecks(private val network: NetworkMonitor, private val keys: KeyRepository) {
    suspend fun discoverRoutes(profile: Profile): List<DiscoveredRoute> = call(profile) { bridge, key ->
        json.decodeFromString(ListSerializer(DiscoveredRoute.serializer()), Mobile.discoverRoutes(bridge, profile.toJson(), key))
    }

    suspend fun testConnection(profile: Profile): List<ConnectionCheck> = call(profile) { bridge, key ->
        json.decodeFromString(ListSerializer(ConnectionCheck.serializer()), Mobile.testConnection(bridge, profile.toJson(), key))
    }

    private suspend fun <T> call(profile: Profile, block: (PlatformBridge, ByteArray?) -> T): T = withContext(Dispatchers.IO) {
        val bridge = PlatformBridge({ fd -> SshovelVpnService.protectIfRunning(fd) }, network, keys)
        // Go zeroes its copy of an imported key; this one is ours.
        val key = try {
            keys.importedKey(profile)
        } catch (e: Exception) {
            throw ServerCheckException(Codes.KEY_UNAVAILABLE, e)
        }
        try {
            block(bridge, key)
        } catch (e: ServerCheckException) {
            throw e
        } catch (e: Exception) {
            throw ServerCheckException(Codes.of(e), e)
        } finally {
            key?.fill(0)
            bridge.close()
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
