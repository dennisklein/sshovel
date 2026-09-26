// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.keys

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Imported private keys, encrypted with AES-256-GCM under a Keystore key (ARCHITECTURE §6,
 * "Auth: imported key"). One file per key: version, IV, ciphertext. The entry id is the GCM
 * additional data, so a file renamed to another id doesn't decrypt.
 *
 * Plaintext exists only in the arrays passed to [put] and returned by [get]; callers zero them.
 */
class ImportedKeyVault(private val dir: File, private val secretKey: () -> SecretKey) {

    fun put(id: String, plaintext: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(aad(id))
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext)
        val blob = ByteBuffer.allocate(2 + iv.size + ct.size).put(VERSION).put(iv.size.toByte()).put(iv).put(ct).array()
        dir.mkdirs()
        val tmp = File(dir, "$id.tmp")
        tmp.writeBytes(blob)
        Files.move(tmp.toPath(), file(id).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** The decrypted key, or null if there's no entry. Throws [IOException] if it can't be decrypted. */
    fun get(id: String): ByteArray? {
        val f = file(id)
        if (!f.exists()) return null
        val buf = ByteBuffer.wrap(f.readBytes())
        try {
            if (buf.get() != VERSION) throw IOException("unknown vault format")
            val iv = ByteArray(buf.get().toInt()).also { buf.get(it) }
            val ct = ByteArray(buf.remaining()).also { buf.get(it) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad(id))
            return cipher.doFinal(ct)
        } catch (e: GeneralSecurityException) {
            throw IOException("vault entry can't be decrypted", e)
        } catch (e: RuntimeException) {
            throw IOException("vault entry is corrupt", e)
        }
    }

    fun delete(id: String) {
        file(id).delete()
    }

    fun contains(id: String): Boolean = file(id).exists()

    private fun file(id: String): File {
        require(ID.matches(id)) { "bad vault id" }
        return File(dir, "$id.bin")
    }

    private fun aad(id: String) = "sshovel-vault:$id".toByteArray()

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val VERSION: Byte = 1
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")
        const val MASTER_ALIAS = "sshovel-vault"

        /** The vault's AES-256 key in Android Keystore, created on first use (StrongBox if present). */
        @Synchronized
        fun keystoreKey(alias: String = MASTER_ALIAS): SecretKey {
            val ks = KeyStore.getInstance(KeystoreKeys.PROVIDER).apply { load(null) }
            (ks.getKey(alias, null) as? SecretKey)?.let { return it }
            fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setIsStrongBoxBacked(strongBox)
                .build()
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KeystoreKeys.PROVIDER)
            return try {
                gen.init(spec(strongBox = true))
                gen.generateKey()
            } catch (_: StrongBoxUnavailableException) {
                gen.init(spec(strongBox = false))
                gen.generateKey()
            }
        }
    }
}
