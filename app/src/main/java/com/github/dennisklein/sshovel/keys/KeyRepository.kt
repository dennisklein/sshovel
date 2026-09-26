// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.keys

import com.github.dennisklein.sshovel.data.AppStore
import com.github.dennisklein.sshovel.data.Auth
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.tunnel.Codes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.nio.CharBuffer
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** A key parsed and normalized by the Go core ([KeyCodec.import]). */
class ParsedKey(
    /** Unencrypted OpenSSH private key; the caller zeroes it. */
    val key: ByteArray,
    val type: String,
    val fingerprint: String,
    val authorizedLine: String,
)

/** The Go core's key functions, behind an interface so JVM tests don't need gomobile. */
interface KeyCodec {
    fun authorizedKeyLine(pkix: ByteArray, comment: String): String

    /** Zeroes [key] and [passphrase]. Throws with a "CODE: detail" message (KEY_PASSPHRASE, …). */
    fun import(key: ByteArray, passphrase: ByteArray, comment: String): ParsedKey
}

/** Import failed; [code] is KEY_PASSPHRASE, KEY_UNSUPPORTED, or KEY_PUTTY (import sheet errors). */
class KeyImportException(val code: String, cause: Throwable? = null) : Exception(code, cause)

/** Delete refused: these profiles still sign in with the key (DESIGN_BRIEF §5.6, K3). */
class KeyInUseException(val profileNames: List<String>) : Exception("key in use")

/**
 * The Keys list (DESIGN_BRIEF §5.6): creates Keystore keys, imports keys into the vault, and
 * hands out key material at connect time. Metadata lives in [AppStore]; private keys never do.
 */
class KeyRepository(
    private val store: AppStore,
    private val hardware: HardwareKeys,
    private val vault: ImportedKeyVault,
    private val codec: KeyCodec,
    scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
) {
    val keys: StateFlow<List<KeyEntry>> = store.state.map { it?.keys.orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun key(id: String): KeyEntry? = store.current().keys.firstOrNull { it.id == id }

    /** Generates an ECDSA P-256 key in Keystore (StrongBox if available). */
    suspend fun create(name: String): KeyEntry {
        val id = "sshovel-key-" + UUID.randomUUID()
        val generated = hardware.generate(id)
        return try {
            val line = codec.authorizedKeyLine(generated.pkix, SshKeys.comment(name))
            val entry = KeyEntry(
                id = id, name = name.trim(), kind = KeyEntry.KEYSTORE, type = ECDSA_P256,
                fingerprint = SshKeys.fingerprint(line), authorizedLine = line,
                publicKeyPkix = Base64.getEncoder().encodeToString(generated.pkix),
                security = generated.security, createdAt = now().toString(),
            )
            store.update { it.copy(keys = it.keys + entry) }
            entry
        } catch (e: Exception) {
            hardware.delete(id)
            throw e
        }
    }

    /**
     * Imports an OpenSSH or PEM private key. The passphrase is used once, here: the vault keeps
     * the key without it, because the tile can't ask (ARCHITECTURE §6). Zeroes [key] and
     * [passphrase].
     */
    suspend fun import(name: String, key: ByteArray, passphrase: CharArray?): KeyEntry {
        val pass = passphrase?.let(::utf8) ?: ByteArray(0)
        passphrase?.fill('\u0000')
        val parsed = try {
            codec.import(key, pass, SshKeys.comment(name))
        } catch (e: Exception) {
            throw KeyImportException(Codes.of(e).takeIf { it in IMPORT_CODES } ?: Codes.KEY_UNSUPPORTED, e)
        } finally {
            key.fill(0)
            pass.fill(0)
        }
        val id = "imported-" + UUID.randomUUID()
        try {
            vault.put(id, parsed.key)
        } finally {
            parsed.key.fill(0)
        }
        val entry = KeyEntry(
            id = id, name = name.trim(), kind = KeyEntry.IMPORTED, type = parsed.type,
            fingerprint = parsed.fingerprint, authorizedLine = parsed.authorizedLine,
            security = KeyEntry.VAULT, createdAt = now().toString(),
        )
        try {
            store.update { it.copy(keys = it.keys + entry) }
        } catch (e: Exception) {
            vault.delete(id)
            throw e
        }
        return entry
    }

    suspend fun rename(id: String, name: String) {
        store.update { d -> d.copy(keys = d.keys.map { if (it.id == id) it.copy(name = name.trim()) else it }) }
    }

    /** Destroys the key. Refused with [KeyInUseException] while a profile uses it. */
    suspend fun delete(id: String) {
        var entry: KeyEntry? = null
        store.update { d ->
            val users = d.profiles.filter { it.auth.alias == id }
            if (users.isNotEmpty()) throw KeyInUseException(users.map { it.name })
            entry = d.keys.firstOrNull { it.id == id }
            d.copy(keys = d.keys.filterNot { it.id == id })
        }
        when (entry?.kind) {
            KeyEntry.KEYSTORE -> hardware.delete(id)
            KeyEntry.IMPORTED -> vault.delete(id)
        }
    }

    /** Profile.auth for signing in with [key]. */
    fun authFor(key: KeyEntry): Auth = Auth(kind = key.kind, alias = key.id, publicKeyPkix = key.publicKeyPkix)

    /**
     * The decrypted private key of an imported-key profile, for one Engine.Start or
     * DiscoverRoutes call; Go zeroes it. Null if the vault has no such entry.
     */
    fun importedKey(profile: Profile): ByteArray? =
        if (profile.auth.kind == Auth.IMPORTED) vault.get(profile.auth.alias) else null

    fun sign(alias: String, digest: ByteArray): ByteArray = hardware.sign(alias, digest)

    companion object {
        /** UTF-8 bytes of [chars] without an intermediate String, which couldn't be zeroed. */
        internal fun utf8(chars: CharArray): ByteArray {
            val buf = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
            val out = ByteArray(buf.remaining()).also { buf.get(it) }
            if (buf.hasArray()) buf.array().fill(0)
            return out
        }

        const val ECDSA_P256 = "ecdsa-sha2-nistp256"
        val IMPORT_CODES = setOf(Codes.KEY_PASSPHRASE, Codes.KEY_UNSUPPORTED, Codes.KEY_PUTTY)
    }
}
