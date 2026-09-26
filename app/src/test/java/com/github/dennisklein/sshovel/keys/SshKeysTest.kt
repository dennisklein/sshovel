// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.keys

import org.junit.Assert.assertEquals
import org.junit.Test

class SshKeysTest {
    // ssh-keygen -lf prints SHA256:A5GxN91swqe7Yf6FJ+7mPG4m2TA8Y6OuzrupfKQzUn4 for this key.
    private val line = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGJ2ubYQ3N6yN9/2RvKjgcFR2klXtT3y12eCwKnGTp6c test"

    @Test fun fingerprintMatchesSshKeygen() {
        assertEquals("SHA256:A5GxN91swqe7Yf6FJ+7mPG4m2TA8Y6OuzrupfKQzUn4", SshKeys.fingerprint(line))
        assertEquals(SshKeys.fingerprint(line), SshKeys.fingerprint("restrict,port-forwarding $line"))
    }

    @Test fun display() {
        assertEquals("Jc8W q0Tn Lp4x yD4", SshKeys.groupFingerprint("SHA256:Jc8Wq0TnLp4xyD4"))
        assertEquals("ED25519", SshKeys.displayType("ssh-ed25519"))
        assertEquals("ECDSA", SshKeys.displayType("ecdsa-sha2-nistp256"))
        assertEquals("/etc/ssh/ssh_host_rsa_key.pub", SshKeys.hostKeyFile("ssh-rsa"))
        assertEquals("Pixel-9-Pro", SshKeys.comment(" Pixel 9  Pro "))
        assertEquals("sshovel", SshKeys.comment("   "))
    }
}
