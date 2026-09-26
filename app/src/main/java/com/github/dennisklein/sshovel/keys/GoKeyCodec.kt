// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.keys

import com.github.dennisklein.sshovel.core.mobile.Mobile

/** [KeyCodec] backed by the Go core (core/mobile). */
class GoKeyCodec : KeyCodec {
    override fun authorizedKeyLine(pkix: ByteArray, comment: String): String = Mobile.authorizedKeyLine(pkix, comment)

    override fun import(key: ByteArray, passphrase: ByteArray, comment: String): ParsedKey {
        val k = Mobile.importKey(key, passphrase, comment)
        // gomobile copies byte slices across the boundary: take the one copy, then clear Go's.
        val bytes = k.key
        k.clear()
        return ParsedKey(bytes, k.type, k.fingerprint, k.authorizedLine)
    }
}
