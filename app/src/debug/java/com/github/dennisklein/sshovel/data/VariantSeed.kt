// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.data

import android.content.Context
import android.util.Log
import com.github.dennisklein.sshovel.BuildConfig
import com.github.dennisklein.sshovel.keys.KeyRepository

/**
 * Debug builds only: on first start, adds a "test-env" profile (test-env/README.md, reached from
 * the emulator at 10.0.2.2:2222) as the default. Its key is the build-generated test key
 * (:app:debugTestKey), imported through the vault like any other key, and it pins the host key
 * test-env created on first start (test-env/hostkeys), read at build time.
 */
object VariantSeed {
    const val PROFILE_ID = "debug-test-env"

    suspend fun seed(context: Context, profiles: ProfileRepository, keys: KeyRepository) {
        if (profiles.profile(PROFILE_ID) != null) return
        try {
            val keyBytes = context.assets.open("test_env_client_key").use { it.readBytes() }
            val key = keys.import("test-env", keyBytes, null)
            profiles.save(testEnvProfile(PROFILE_ID, "test-env", keys.authFor(key)))
            BuildConfig.DEBUG_HOST_KEY_FP.takeIf { it.isNotEmpty() }?.let {
                profiles.trustHostKey(PROFILE_ID, HostKeyInfo("ssh-ed25519", it))
            }
        } catch (e: Exception) {
            Log.e("sshovel/Debug", "seeding the test-env profile failed", e)
        }
    }

    /** A profile for test-env's jump host and intranet, unpinned. */
    fun testEnvProfile(id: String, name: String, auth: Auth) = Profile(
        id = id,
        name = name,
        server = Server(host = "10.0.2.2", port = 2222, user = "tester"),
        auth = auth,
        routes = listOf("10.77.0.0/24"),
        dns = Dns(server = "10.77.0.53", suffixes = listOf("corp.test"), searchDomains = listOf("corp.test")),
    )
}
