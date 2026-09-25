// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.data

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Connection profile. Mirrors the Go schema in core/config (ARCHITECTURE §8);
 * the JSON crosses the gomobile boundary as is. Never put key material here.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val server: Server,
    val auth: Auth,
    val hostKey: HostKey? = null,
    val routes: List<String>,
    @EncodeDefault val excludedRoutes: List<String> = emptyList(),
    @EncodeDefault val dns: Dns = Dns(),
    @EncodeDefault val apps: Apps = Apps(),
    @EncodeDefault val tun: Tun = Tun(),
    @EncodeDefault val keepaliveSec: Int = 20,
    @EncodeDefault val connectTimeoutSec: Int = 10,
) {
    fun toJson(): String = ProfileJson.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): Profile = ProfileJson.decodeFromString(serializer(), json)
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Server(val host: String, @EncodeDefault val port: Int = 22, val user: String)

@Serializable
data class Auth(
    /** "keystore" or "imported". */
    val kind: String,
    /** Keystore alias, or the vault entry of an imported key. */
    val alias: String,
    /** Base64 PKIX public key of a Keystore key. */
    val publicKeyPkix: String? = null,
) {
    companion object {
        const val KEYSTORE = "keystore"
        const val IMPORTED = "imported"
    }
}

@Serializable
data class HostKey(val type: String, val fingerprint: String, val pinnedAt: String? = null)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Dns(
    val server: String? = null,
    @EncodeDefault val suffixes: List<String> = emptyList(),
    @EncodeDefault val searchDomains: List<String> = emptyList(),
    @EncodeDefault val reverseLookups: Boolean = true,
    @EncodeDefault val hideAAAA: Boolean = true,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Apps(
    /** "all", "include", or "exclude". */
    @EncodeDefault val mode: String = ALL,
    @EncodeDefault val packages: List<String> = emptyList(),
) {
    companion object {
        const val ALL = "all"
        const val INCLUDE = "include"
        const val EXCLUDE = "exclude"
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Tun(
    @EncodeDefault val cidr: String = "198.18.0.0/24",
    @EncodeDefault val dnsVirtualIp: String = "198.18.0.53",
    @EncodeDefault val mtu: Int = 1500,
)

/** Lenient on input (newer fields), explicit on output. */
val ProfileJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
