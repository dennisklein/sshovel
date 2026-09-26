// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.keys

import com.github.dennisklein.sshovel.TestStores
import com.github.dennisklein.sshovel.data.Auth
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.data.ProfileRepository
import com.github.dennisklein.sshovel.data.Server
import com.github.dennisklein.sshovel.tunnel.Codes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class KeyRepositoryTest {
    private val t = TestStores()
    private val keys = KeyRepository(t.store, t.hardware, t.vault, t.codec, t.scope)
    private val profiles = ProfileRepository(t.store, t.scope)

    @After fun tearDown() = t.close()

    private fun profileWith(key: KeyEntry) =
        Profile(id = "", name = "Office", server = Server("h", 22, "u"), auth = keys.authFor(key), routes = listOf("10.0.0.0/8"))

    @Test fun createKeystoreKey() = runBlocking {
        val k = keys.create("Pixel 9")
        assertEquals(KeyEntry.KEYSTORE, k.kind)
        assertEquals(KeyRepository.ECDSA_P256, k.type)
        assertEquals(KeyEntry.TEE, k.security)
        assertTrue(k.id in t.hardware.aliases)
        // The comment is the key name without spaces (handoff key_name_helper).
        assertTrue(k.authorizedLine, k.authorizedLine.startsWith("restrict,port-forwarding ecdsa-sha2-nistp256 "))
        assertTrue(k.authorizedLine.endsWith(" Pixel-9"))
        assertEquals(SshKeys.fingerprint(k.authorizedLine), k.fingerprint)
        assertEquals(Auth(Auth.KEYSTORE, k.id, k.publicKeyPkix), keys.authFor(k))
        assertEquals(listOf(k), keys.keys.value.ifEmpty { t.store.current().keys })
    }

    @Test fun importUsesThePassphraseOnceAndStoresOnlyCiphertext() = runBlocking {
        val input = "ENCRYPTED:hunter2:private-key-body".encodeToByteArray()
        val pass = "hunter2".toCharArray()
        val k = keys.import("laptop", input, pass)

        assertTrue("input zeroed", input.all { it == 0.toByte() })
        assertTrue("passphrase zeroed", pass.all { it == '\u0000' })
        assertEquals(listOf("hunter2"), t.codec.seenPassphrases)
        assertEquals(KeyEntry.IMPORTED, k.kind)
        assertEquals(KeyEntry.VAULT, k.security)

        // On disk: ciphertext only; the store has no key material either.
        val file = File(t.dir, "vault/${k.id}.bin")
        assertTrue(file.exists())
        assertFalse(file.readBytes().decodeToString().contains("private-key-body"))
        assertFalse(File(t.dir, "store.json").readText().contains("private-key-body"))

        // At connect time the key comes back without any passphrase.
        val profile = profiles.save(profileWith(k))
        assertArrayEquals("PLAIN:private-key-body".encodeToByteArray(), keys.importedKey(profile))
        assertEquals(listOf("hunter2"), t.codec.seenPassphrases)
    }

    @Test fun importErrors() = runBlocking {
        suspend fun code(text: String, pass: String?) = try {
            keys.import("x", text.encodeToByteArray(), pass?.toCharArray())
            fail("import of $text succeeded")
            ""
        } catch (e: KeyImportException) {
            e.code
        }
        assertEquals(Codes.KEY_PASSPHRASE, code("ENCRYPTED:right:body", "wrong"))
        assertEquals(Codes.KEY_PASSPHRASE, code("ENCRYPTED:right:body", null))
        assertEquals(Codes.KEY_PUTTY, code("PuTTY-User-Key-File-3", null))
        assertEquals(Codes.KEY_UNSUPPORTED, code("garbage", null))
        assertTrue(t.store.current().keys.isEmpty())
        assertTrue(File(t.dir, "vault").listFiles().orEmpty().isEmpty())
    }

    @Test fun deleteIsRefusedWhileInUse() = runBlocking {
        val k = keys.import("laptop", "KEY:body".encodeToByteArray(), null)
        val p = profiles.save(profileWith(k))
        try {
            keys.delete(k.id)
            fail("deleted a key in use")
        } catch (e: KeyInUseException) {
            assertEquals(listOf("Office"), e.profileNames)
        }
        assertNotNull(keys.key(k.id))

        profiles.delete(p.id)
        keys.delete(k.id)
        assertNull(keys.key(k.id))
        assertFalse(t.vault.contains(k.id))

        val hw = keys.create("hw")
        keys.delete(hw.id)
        assertFalse(hw.id in t.hardware.aliases)
    }

    @Test fun rename() = runBlocking {
        val k = keys.create("old")
        keys.rename(k.id, "  new ")
        assertEquals("new", keys.key(k.id)?.name)
    }

    @Test fun utf8WithoutString() {
        assertArrayEquals("päss".encodeToByteArray(), KeyRepository.utf8("päss".toCharArray()))
    }
}
