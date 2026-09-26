// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.keys

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** Keystore keys sign SHA-256 digests the way the Go signer expects (ARCHITECTURE §6). */
@RunWith(AndroidJUnit4::class)
class KeystoreKeysInstrumentedTest {
    private val keys = KeystoreKeys()
    private val alias = "sshovel-key-instrumented-test"

    @After fun tearDown() = keys.delete(alias)

    @Test fun signDigestVerifiesWithThePkixKey() {
        val generated = keys.generate(alias)
        assertTrue(generated.security in setOf(KeyEntry.STRONGBOX, KeyEntry.TEE, KeyEntry.SOFTWARE))

        val data = "ssh session data".encodeToByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val der = keys.sign(alias, digest) // NONEwithECDSA over the digest, as Go's crypto.Signer does

        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(generated.pkix))
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(pub)
            update(data)
            verify(der)
        }
        assertTrue("signature over the digest verifies as SHA256withECDSA over the data", ok)
    }

    @Test fun deletedKeyCantSign() {
        keys.generate(alias)
        keys.delete(alias)
        try {
            keys.sign(alias, ByteArray(32))
            fail("signed with a deleted key")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.startsWith("KEY_UNAVAILABLE"))
        }
    }
}
