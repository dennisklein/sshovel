// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.keys

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Base64

/**
 * A key in the Keys list (DESIGN_BRIEF §5.6). Only public material: the private key lives in
 * Keystore ([KEYSTORE]) or encrypted in [ImportedKeyVault] ([IMPORTED]), both under [id].
 */
@Serializable
data class KeyEntry(
    val id: String,
    val name: String,
    /** [KEYSTORE] or [IMPORTED], as in Profile.auth.kind. */
    val kind: String,
    /** SSH key type, e.g. ecdsa-sha2-nistp256. */
    val type: String,
    val fingerprint: String,
    /** Recommended authorized_keys line (ARCHITECTURE §6). */
    val authorizedLine: String,
    /** Base64 PKIX public key; Keystore keys only (the Go signer needs it). */
    val publicKeyPkix: String? = null,
    /** [STRONGBOX], [TEE], [SOFTWARE], or [VAULT] for imported keys. */
    val security: String,
    /** ISO-8601 instant. */
    val createdAt: String,
) {
    companion object {
        const val KEYSTORE = "keystore"
        const val IMPORTED = "imported"
        const val STRONGBOX = "strongbox"
        const val TEE = "tee"
        const val SOFTWARE = "software"
        const val VAULT = "vault"
    }
}

/** Helpers for SSH public keys and fingerprints as shown in the UI. */
object SshKeys {
    /** SHA256 fingerprint of the key in an authorized_keys line, as ssh-keygen -l prints it. */
    fun fingerprint(authorizedLine: String): String {
        val tokens = authorizedLine.trim().split(Regex("\\s+"))
        val i = tokens.indexOfFirst { it.startsWith("ssh-") || it.startsWith("ecdsa-") || it.startsWith("sk-") }
        require(i >= 0 && i + 1 < tokens.size) { "not an authorized_keys line" }
        val blob = Base64.getDecoder().decode(tokens[i + 1])
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    /** "SHA256:Jc8Wq0Tn…" → "Jc8W q0Tn …", the grouped form of the host key screens. */
    fun groupFingerprint(fingerprint: String): String = fingerprint.removePrefix("SHA256:").chunked(4).joinToString(" ")

    /** "ssh-ed25519" → "ED25519". */
    fun displayType(type: String): String = when {
        type.contains("ed25519") -> "ED25519"
        type.startsWith("ecdsa") -> "ECDSA"
        type.contains("rsa") -> "RSA"
        else -> type
    }

    /** The server file to compare a host key with (DESIGN_BRIEF §5.5). */
    fun hostKeyFile(type: String): String = when (displayType(type)) {
        "ED25519" -> "/etc/ssh/ssh_host_ed25519_key.pub"
        "RSA" -> "/etc/ssh/ssh_host_rsa_key.pub"
        else -> "/etc/ssh/ssh_host_ecdsa_key.pub"
    }

    /** A key name usable as the public key comment: no spaces, no control characters. */
    fun comment(name: String): String =
        name.trim().replace(Regex("\\s+"), "-").filter { it.isLetterOrDigit() || it in "@._-+" }.ifEmpty { "sshovel" }
}
