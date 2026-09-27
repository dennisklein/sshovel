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

    @Test fun rsaBitsAndOptions() {
        val rsa = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQCxsywykH18pVKKOKaGGSIxDeoJM7M+UiXLB9zSUszY7ANHAySrqjHBLjsTJT6BLZBSQZ5AMtMeqdq4sVL6cJID+Ba43F7JR2QvALqSA8TkuryXVAiuays5yrA44lA2TOLnL7F6jN9WxvPByeecTjsRb52ssne1n2uqmTNZ+hF3Cp/lcrOQLcJoRk9NxH48FXtcU4nPUrTJXqiJlk4Sfl5ZOfqCDecSqf5Srqd2zCBkevv4Oxn1oYWFk1hJ5uVUeOWuCPOEwSMbIhXQIhaXYCzUnDxEpDuuTZDnw2KfGDBMZXIMsgPPwdKt/zysx56qfd+4oZo+0n7XB2SgiczY+xfTdN2LU6niwoZtx9sHNwyAdwfMYrzrhJTUbfP1J4DO/KidK296poWoV1YABs3wiS6OyyIBMJu09/hQmKfU0gl4wTtdvvNJ6MTPeQzeVDeYIIxkXJUXJtKiFqKqi7SbYxJE3o6H8NbvoVUoi+HjwGJeKWss4okW1YAScOK1cx8aVYM= t"
        assertEquals(3072, SshKeys.rsaBits("restrict,port-forwarding $rsa"))
        assertEquals(null, SshKeys.rsaBits(line))
        assertEquals(line, SshKeys.withoutOptions("restrict,port-forwarding $line"))
        assertEquals(line, SshKeys.withoutOptions(line))
    }
}
