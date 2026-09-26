// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.tunnel

import org.junit.Assert.assertEquals
import org.junit.Test

/** Status JSON exactly as core/engine emits it (ARCHITECTURE §8). */
class EngineStatusTest {
    private fun state(json: String) = EngineStatus.parse(json).toTunnelState()

    @Test fun connectingSteps() {
        assertEquals(TunnelState.Connecting("identity"), state("""{"state":"connecting","code":"","detail":"","step":"identity"}"""))
        assertEquals(TunnelState.Connecting("tunnel"), state("""{"state":"sshReady","code":"","detail":""}"""))
    }

    @Test fun onWithWarnings() {
        assertEquals(TunnelState.On(), state("""{"state":"on","code":"","detail":""}"""))
        assertEquals(
            TunnelState.On(listOf("FORWARDING_DENIED", "DNS_UNREACHABLE")),
            state("""{"state":"on","code":"","detail":"","warnings":["FORWARDING_DENIED","DNS_UNREACHABLE"]}"""),
        )
    }

    @Test fun reconnecting() {
        assertEquals(
            TunnelState.Reconnecting("dialFailed", 3, 1790337601000, "HOST_UNREACHABLE"),
            state("""{"state":"reconnecting","code":"HOST_UNREACHABLE","detail":"","reason":"dialFailed","attempt":3,"nextRetryAt":1790337601000}"""),
        )
        // While dialing there is no countdown.
        assertEquals(
            TunnelState.Reconnecting("networkChanged", 0, null, null),
            state("""{"state":"reconnecting","code":"","detail":"","reason":"networkChanged"}"""),
        )
    }

    @Test fun hostKeyErrorsCarryTheReceivedKey() {
        assertEquals(
            TunnelState.NeedsAttention(
                "HOST_KEY_MISMATCH", "host key does not match the pinned key; server presented ssh-ed25519 SHA256:abc",
                com.github.dennisklein.sshovel.data.HostKeyInfo("ssh-ed25519", "SHA256:abc", "ssh-ed25519 AAAA"),
            ),
            state(
                """{"state":"needsAttention","code":"HOST_KEY_MISMATCH","detail":"host key does not match the pinned key; server presented ssh-ed25519 SHA256:abc",""" +
                    """"hostKey":{"type":"ssh-ed25519","fingerprint":"SHA256:abc","line":"ssh-ed25519 AAAA"}}""",
            ),
        )
    }

    @Test fun needsAttentionAndUnknown() {
        assertEquals(
            TunnelState.NeedsAttention("HOST_KEY_MISMATCH", "host key does not match the pinned key"),
            state("""{"state":"needsAttention","code":"HOST_KEY_MISMATCH","detail":"host key does not match the pinned key"}"""),
        )
        assertEquals(TunnelState.NeedsAttention(Codes.INTERNAL, "unknown engine state bogus"), state("""{"state":"bogus"}"""))
        assertEquals(TunnelState.Off, state("""{"state":"off","code":"","detail":"","future":1}"""))
    }

    @Test fun errorCodesFromGoMessages() {
        assertEquals("HOST_KEY_UNVERIFIED", Codes.of(Exception("HOST_KEY_UNVERIFIED: no pinned host key")))
        assertEquals("INTERNAL", Codes.of(Exception("INTERNAL: invalid profile: routes=REQUIRED")))
        assertEquals(Codes.INTERNAL, Codes.of(Exception("java.io.IOException: boom")))
        assertEquals(Codes.INTERNAL, Codes.of(Exception()))
    }

    @Test fun stats() {
        val s = TunnelStats.parse(
            """{"uptimeSec":42,"bytesIn":2,"bytesOut":3,"activeFlows":1,"dnsTunneled":4,"dnsDirect":5,"droppedUdp":6,"droppedIcmp":7,"lastError":"HOST_UNREACHABLE"}""",
        )
        assertEquals(TunnelStats(42, 2, 3, 1, 4, 5, 6, 7, "HOST_UNREACHABLE"), s)
    }
}
