// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileTest {
    private val golden = Profile(
        id = "3f1c0d2e-0000-4000-8000-000000000001",
        name = "Office",
        server = Server("jump.example.com", 22, "alice"),
        auth = Auth(Auth.KEYSTORE, "sshovel-key-1",
            // A real P-256 PKIX key, so Go's validation accepts it.
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE/VvER+8JIsT8UvqJjvOYn5w9sS+a5waNCLKzoOzmxy47kZxr/IUtkqck2bsuUR3wqpyVBt4FUWTtOqQIp3Ix5Q=="),
        hostKey = HostKey("ecdsa-sha2-nistp256", "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU", "2026-09-23T10:00:00Z"),
        routes = listOf("10.0.0.0/8", "172.16.0.0/12"),
        dns = Dns("10.1.0.53", listOf("corp.example", "internal"), listOf("corp.example")),
    )

    /**
     * The contract with Go: core/config/golden_test.go parses and validates the same file.
     * Regenerate with the new output only if both sides agree on the change.
     */
    @Test fun encodesTheGoldenJson() {
        val expected = javaClass.getResource("/profile_golden.json")!!.readText().trim()
        assertEquals(expected, golden.toJson())
        assertEquals(golden, Profile.fromJson(expected))
    }

    @Test fun decodesArchitectureExampleWithDefaultsAndUnknownFields() {
        val p = Profile.fromJson(
            """{"id":"x","name":"n","server":{"host":"h","user":"u"},"auth":{"kind":"imported","alias":"a"},
               "routes":["10.0.0.0/8"],"somethingNew":true}""",
        )
        assertEquals(22, p.server.port)
        assertEquals("198.18.0.0/24", p.tun.cidr)
        assertEquals(Apps.ALL, p.apps.mode)
        assertNull(p.hostKey)
    }

    @Test fun ipv4Prefix() {
        val p = Ipv4Prefix.parse("198.18.0.0/24")
        assertEquals(24, p.length)
        assertEquals("198.18.0.1", p.firstHost().hostAddress)
        assertEquals("10.0.1.0", Ipv4Prefix.parse("10.0.0.255/32").firstHost().hostAddress)
    }

    @Test(expected = IllegalArgumentException::class)
    fun ipv4PrefixRejectsNames() {
        Ipv4Prefix.parse("wiki.corp.test/24")
    }
}
