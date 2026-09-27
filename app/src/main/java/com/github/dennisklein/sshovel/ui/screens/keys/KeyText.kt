// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.keys

import android.content.Context
import com.github.dennisklein.sshovel.R
import com.github.dennisklein.sshovel.keys.KeyEntry
import com.github.dennisklein.sshovel.keys.SshKeys

/** "ECDSA P-256", "Ed25519", "RSA 3072". */
fun keyTypeName(context: Context, key: KeyEntry): String = when {
    key.type.contains("ed25519") -> context.getString(R.string.key_type_ed25519)
    key.type.startsWith("ecdsa") -> context.getString(R.string.key_type_ecdsa)
    key.type.contains("rsa") -> SshKeys.rsaBits(key.authorizedLine)?.let { context.getString(R.string.key_type_rsa, it) } ?: "RSA"
    else -> key.type
}

/** "ECDSA P-256, created on this phone" / "Ed25519, imported" (handoff K1). */
fun keyTypeLine(context: Context, key: KeyEntry): String =
    if (key.kind == KeyEntry.KEYSTORE) context.getString(R.string.key_created_here, keyTypeName(context, key))
    else context.getString(R.string.key_imported, keyTypeName(context, key))

/** "Office and Lab", "Office, Lab and Home lab". */
fun joinNames(context: Context, names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> context.getString(R.string.changed_fields_and, names.dropLast(1).joinToString(", "), names.last())
}
