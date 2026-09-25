// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.data

import android.content.Context
import com.github.dennisklein.sshovel.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Debug builds only (M2): one hardcoded profile for test-env (test-env/README.md), reached
 * from the emulator at 10.0.2.2:2222. The build generates its key (:app:debugTestKey) and pins
 * the host key test-env created on first start (test-env/hostkeys). Real profiles arrive in M6.
 */
object VariantProfiles {
    fun create(context: Context): ProfileRepository = TestEnvProfiles(context.applicationContext)
}

private class TestEnvProfiles(private val context: Context) : ProfileRepository {
    private val testEnv = Profile(
        id = "debug-test-env",
        name = "test-env",
        server = Server(host = "10.0.2.2", port = 2222, user = "tester"),
        auth = Auth(kind = Auth.IMPORTED, alias = "test-env"),
        hostKey = BuildConfig.DEBUG_HOST_KEY_FP.takeIf { it.isNotEmpty() }
            ?.let { HostKey(type = "ssh-ed25519", fingerprint = it) },
        routes = listOf("10.77.0.0/24"),
        dns = Dns(server = "10.77.0.53", suffixes = listOf("corp.test"), searchDomains = listOf("corp.test")),
    )

    override val profiles: StateFlow<List<Profile>> = MutableStateFlow(listOf(testEnv))

    override fun defaultProfile(): Profile = testEnv

    override fun importedKey(profile: Profile): ByteArray? =
        if (profile.id == testEnv.id) context.assets.open("test_env_client_key").use { it.readBytes() } else null
}
