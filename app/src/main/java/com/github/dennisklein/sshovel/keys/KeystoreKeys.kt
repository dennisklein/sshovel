// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.keys

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** A freshly generated signing key: its PKIX public key and where it lives. */
class GeneratedKey(val pkix: ByteArray, val security: String)

/** Non-exportable signing keys. [KeystoreKeys] in the app, a fake in JVM tests. */
interface HardwareKeys {
    fun generate(alias: String): GeneratedKey

    /** ASN.1 DER ECDSA signature over [digest] (NONEwithECDSA). Throws if the key is gone. */
    fun sign(alias: String, digest: ByteArray): ByteArray

    fun delete(alias: String)
}

/**
 * ECDSA P-256 keys in Android Keystore (ARCHITECTURE §6, "Auth: Keystore key"). StrongBox is
 * tried first. Keys don't require user authentication: the tile must connect while locked.
 */
class KeystoreKeys : HardwareKeys {
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    override fun generate(alias: String): GeneratedKey {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
            .build()
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        val pair = try {
            kpg.initialize(spec(strongBox = true))
            kpg.generateKeyPair()
        } catch (_: StrongBoxUnavailableException) {
            kpg.initialize(spec(strongBox = false))
            kpg.generateKeyPair()
        }
        // X.509 SubjectPublicKeyInfo is the PKIX encoding Go parses.
        return GeneratedKey(pair.public.encoded, securityOf(pair.private))
    }

    override fun sign(alias: String, digest: ByteArray): ByteArray {
        val key = keyStore.getKey(alias, null) as? PrivateKey
            ?: throw IllegalStateException("KEY_UNAVAILABLE: no Keystore key")
        return Signature.getInstance("NONEwithECDSA").run {
            initSign(key)
            update(digest)
            sign()
        }
    }

    override fun delete(alias: String) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    companion object {
        const val PROVIDER = "AndroidKeyStore"

        /** "strongbox", "tee", or "software" (DESIGN_BRIEF §5.6 badges). */
        fun securityOf(key: java.security.Key): String {
            val info = KeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, KeyInfo::class.java)
            return when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyEntry.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyEntry.TEE
                else -> KeyEntry.SOFTWARE
            }
        }
    }
}
