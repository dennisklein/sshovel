// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel

import com.github.dennisklein.sshovel.data.AppStore
import com.github.dennisklein.sshovel.keys.GeneratedKey
import com.github.dennisklein.sshovel.keys.HardwareKeys
import com.github.dennisklein.sshovel.keys.ImportedKeyVault
import com.github.dennisklein.sshovel.keys.KeyCodec
import com.github.dennisklein.sshovel.keys.KeyEntry
import com.github.dennisklein.sshovel.keys.ParsedKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** A temp directory with an AppStore, a software-keyed vault, and fakes for Keystore and Go. */
class TestStores : AutoCloseable {
    val dir: File = Files.createTempDirectory("sshovel-test").toFile()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store = AppStore(File(dir, "store.json"), scope)
    val aesKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    val vault = ImportedKeyVault(File(dir, "vault")) { aesKey }
    val hardware = FakeHardwareKeys()
    val codec = FakeCodec()

    override fun close() {
        scope.cancel()
        dir.deleteRecursively()
    }
}

class FakeHardwareKeys : HardwareKeys {
    val aliases = mutableSetOf<String>()

    override fun generate(alias: String): GeneratedKey {
        aliases += alias
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        return GeneratedKey(kpg.generateKeyPair().public.encoded, KeyEntry.TEE)
    }

    override fun sign(alias: String, digest: ByteArray): ByteArray =
        if (alias in aliases) byteArrayOf(1) else error("KEY_UNAVAILABLE: gone")

    override fun delete(alias: String) {
        aliases -= alias
    }
}

/**
 * Stands in for the Go core: "keys" are text; "ENCRYPTED:<pass>:<body>" needs the passphrase.
 * Like Go, it zeroes its inputs.
 */
class FakeCodec : KeyCodec {
    val seenPassphrases = mutableListOf<String>()

    override fun authorizedKeyLine(pkix: ByteArray, comment: String): String =
        "restrict,port-forwarding ecdsa-sha2-nistp256 " + Base64.getEncoder().encodeToString(pkix) + " " + comment

    override fun import(key: ByteArray, passphrase: ByteArray, comment: String): ParsedKey {
        val text = key.decodeToString()
        val pass = passphrase.decodeToString()
        seenPassphrases += pass
        key.fill(0)
        passphrase.fill(0)
        val body = when {
            text.startsWith("PuTTY") -> throw IllegalArgumentException("KEY_PUTTY: putty")
            text.startsWith("ENCRYPTED:") -> {
                val (_, want, rest) = text.split(":", limit = 3)
                if (pass != want) throw IllegalArgumentException("KEY_PASSPHRASE: wrong passphrase")
                rest
            }
            text.startsWith("KEY:") -> text.removePrefix("KEY:")
            else -> throw IllegalArgumentException("KEY_UNSUPPORTED: garbage")
        }
        val blob = Base64.getEncoder().encodeToString(body.encodeToByteArray())
        return ParsedKey(
            "PLAIN:$body".encodeToByteArray(), "ssh-ed25519", "SHA256:fake",
            "restrict,port-forwarding ssh-ed25519 $blob $comment",
        )
    }
}
