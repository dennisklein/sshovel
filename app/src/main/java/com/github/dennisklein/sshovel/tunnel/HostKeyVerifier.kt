// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.tunnel

import com.github.dennisklein.sshovel.core.mobile.Mobile
import com.github.dennisklein.sshovel.data.HostKeyInfo
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.keys.KeyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** A FetchHostKey failure; [code] is from ARCHITECTURE §8 (HOST_UNREACHABLE, …). */
class HostKeyFetchException(val code: String, cause: Throwable) : Exception(code, cause)

/**
 * Fetches a server's host key for the verification dialog (DESIGN_BRIEF §5.5) without
 * authenticating. Works whether or not the tunnel is up: the socket is protected through the
 * running VPN service, if any, and the name resolves on the underlying network.
 */
class HostKeyVerifier(private val network: NetworkMonitor, private val keys: KeyRepository) {
    suspend fun fetch(profile: Profile): HostKeyInfo = withContext(Dispatchers.IO) {
        val bridge = PlatformBridge({ fd -> SshovelVpnService.protectIfRunning(fd) }, network, keys)
        try {
            json.decodeFromString(HostKeyInfo.serializer(), Mobile.fetchHostKey(bridge, profile.toJson()))
        } catch (e: Exception) {
            throw HostKeyFetchException(Codes.of(e), e)
        } finally {
            bridge.close()
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
