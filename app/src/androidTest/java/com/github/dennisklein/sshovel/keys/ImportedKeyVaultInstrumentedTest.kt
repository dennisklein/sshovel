// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.keys

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.KeyStore

/** The vault with its real Android Keystore AES-256-GCM key (IMPLEMENTATION_PLAN M3). */
@RunWith(AndroidJUnit4::class)
class ImportedKeyVaultInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "vault-test").apply { deleteRecursively() }
    private val alias = "sshovel-vault-test"
    private val secret = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n-----END OPENSSH PRIVATE KEY-----\n".encodeToByteArray()

    private fun vault() = ImportedKeyVault(dir) { ImportedKeyVault.keystoreKey(alias) }

    @After fun tearDown() {
        dir.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test fun encryptDecryptRoundTrip() {
        vault().put("imported-1", secret)
        val onDisk = File(dir, "imported-1.bin").readBytes()
        assertFalse(onDisk.decodeToString().contains("OPENSSH"))
        // A new vault instance (as after a process restart) reads it with the same Keystore key.
        assertArrayEquals(secret, vault().get("imported-1"))
    }

    @Test fun keystoreKeyIsCreatedOnceAndIsNotExportable() {
        val a = ImportedKeyVault.keystoreKey(alias)
        val b = ImportedKeyVault.keystoreKey(alias)
        assertNull("Keystore keys don't export raw bytes", a.encoded)
        vault().put("x", secret)
        assertArrayEquals(secret, ImportedKeyVault(dir) { b }.get("x"))
    }

    @Test fun losingTheKeystoreKeyMakesEntriesUnreadable() {
        vault().put("imported-2", secret)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        try {
            vault().get("imported-2") // a fresh key now
            fail("decrypted with a different key")
        } catch (_: IOException) {
        }
    }
}
