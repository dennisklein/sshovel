// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.keys

import com.github.dennisklein.sshovel.TestStores
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import javax.crypto.KeyGenerator

/** The vault's file format and failure modes, with a software AES key (androidTest uses Keystore). */
class ImportedKeyVaultTest {
    private val t = TestStores()
    private val secret = "-----BEGIN OPENSSH PRIVATE KEY-----\nsecret\n".encodeToByteArray()

    @After fun tearDown() = t.close()

    private fun assertUnreadable(block: () -> Unit) {
        try {
            block()
            fail("decrypted")
        } catch (_: IOException) {
        }
    }

    @Test fun roundTripAndDelete() {
        t.vault.put("a", secret)
        assertArrayEquals(secret, t.vault.get("a"))
        assertFalse(File(t.dir, "vault/a.bin").readBytes().decodeToString().contains("secret"))
        t.vault.delete("a")
        assertNull(t.vault.get("a"))
    }

    @Test fun entryIsBoundToItsId() {
        t.vault.put("a", secret)
        File(t.dir, "vault/a.bin").copyTo(File(t.dir, "vault/b.bin"))
        assertUnreadable { t.vault.get("b") }
    }

    @Test fun tamperingAndWrongKeyFail() {
        t.vault.put("a", secret)
        val f = File(t.dir, "vault/a.bin")
        val bytes = f.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        f.writeBytes(bytes)
        assertUnreadable { t.vault.get("a") }

        t.vault.put("c", secret)
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertUnreadable { ImportedKeyVault(File(t.dir, "vault")) { otherKey }.get("c") }
    }

    @Test fun rejectsPathLikeIds() {
        try {
            t.vault.put("../x", secret)
            fail("accepted a path")
        } catch (_: IllegalArgumentException) {
        }
    }
}
