// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui

import android.app.Activity
import android.content.Intent
import android.util.Log
import com.github.dennisklein.sshovel.SshovelApplication
import com.github.dennisklein.sshovel.data.HostKeyAlreadyPinnedException
import com.github.dennisklein.sshovel.data.VariantSeed
import com.github.dennisklein.sshovel.keys.KeyImportException
import com.github.dennisklein.sshovel.keys.KeyInUseException
import com.github.dennisklein.sshovel.tunnel.HostKeyFetchException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlin.concurrent.thread

/**
 * Debug builds only: adb hooks for the emulator runbooks (tools/android-env), e.g.
 *
 *     adb shell am start -n com.github.dennisklein.sshovel/.ui.MainActivity --es cmd connect
 *     adb shell am start -n com.github.dennisklein.sshovel/.ui.MainActivity --es cmd fetch --es url http://wiki.corp.test/
 *
 * Commands (extras are strings):
 * - `connect [profile]`, `disconnect`, `retry`, `state`, `fetch url`
 * - `key-create name`, `key-import name key(base64) [passphrase]`, `key-list`, `key-delete id`
 * - `profile-add name key` (a test-env profile, unpinned), `profile-list`
 * - `verify profile` (fetch and log the host key), `trust profile` (fetch and pin, like tapping
 *   "Trust this server"; refused if a pin exists), `forget profile` ("Forget pinned key")
 *
 * Results are logged under the tag "sshovel/Debug", one line per command, starting with the
 * command name. Never used for anything but test-env: passphrases here are test data.
 */
object DebugCommands {
    private const val TAG = "sshovel/Debug"

    fun handle(activity: Activity, intent: Intent?, connect: (String?) -> Unit) {
        val container = (activity.application as SshovelApplication).container
        val controller = container.tunnelController
        fun arg(name: String) = intent?.getStringExtra(name)
        fun async(block: suspend () -> Unit) = container.appScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                Log.i(TAG, "${arg("cmd")} error ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        when (arg("cmd")) {
            "connect" -> connect(arg("profile"))
            "disconnect" -> controller.disconnect()
            "retry" -> controller.retryNow()
            "state" -> Log.i(TAG, "state ${controller.state.value}")
            "fetch" -> arg("url")?.let(::fetch)

            "key-create" -> async {
                val k = container.keys.create(arg("name") ?: "debug")
                Log.i(TAG, "key-create ${k.id} ${k.security} ${k.authorizedLine}")
            }
            "key-import" -> async {
                val bytes = Base64.getDecoder().decode(arg("key").orEmpty())
                try {
                    val k = container.keys.import(arg("name") ?: "imported", bytes, arg("passphrase")?.toCharArray())
                    Log.i(TAG, "key-import ${k.id} ${k.type} ${k.fingerprint} ${k.authorizedLine}")
                } catch (e: KeyImportException) {
                    Log.i(TAG, "key-import error ${e.code}")
                }
            }
            "key-list" -> async {
                container.keys.keys.value.forEach { Log.i(TAG, "key-list ${it.id} ${it.kind} ${it.type} ${it.security} ${it.name}") }
            }
            "key-delete" -> async {
                try {
                    container.keys.delete(arg("id").orEmpty())
                    Log.i(TAG, "key-delete ok")
                } catch (e: KeyInUseException) {
                    Log.i(TAG, "key-delete in-use ${e.profileNames}")
                }
            }

            "profile-add" -> async {
                val key = container.keys.key(arg("key").orEmpty()) ?: error("no key ${arg("key")}")
                val p = container.profiles.save(VariantSeed.testEnvProfile("", arg("name") ?: "test", container.keys.authFor(key)))
                Log.i(TAG, "profile-add ${p.id}")
            }
            "profile-list" -> async {
                container.profiles.profiles.value.forEach {
                    Log.i(TAG, "profile-list ${it.id} ${it.name} ${it.auth.kind}:${it.auth.alias} pin=${it.hostKey?.fingerprint}")
                }
            }
            "verify" -> async {
                val p = container.profiles.profile(arg("profile").orEmpty()) ?: error("no profile")
                try {
                    val k = container.hostKeys.fetch(p)
                    Log.i(TAG, "verify ${k.type} ${k.fingerprint}")
                } catch (e: HostKeyFetchException) {
                    Log.i(TAG, "verify error ${e.code}")
                }
            }
            "trust" -> async {
                val p = container.profiles.profile(arg("profile").orEmpty()) ?: error("no profile")
                try {
                    val k = container.hostKeys.fetch(p)
                    container.profiles.trustHostKey(p.id, k)
                    Log.i(TAG, "trust ${k.type} ${k.fingerprint}")
                } catch (e: HostKeyFetchException) {
                    Log.i(TAG, "trust error ${e.code}")
                } catch (_: HostKeyAlreadyPinnedException) {
                    Log.i(TAG, "trust refused already-pinned")
                }
            }
            "forget" -> async {
                container.profiles.forgetHostKey(arg("profile").orEmpty())
                Log.i(TAG, "forget ok")
            }
        }
    }

    /** Fetches a URL from the app's own UID (which the VPN covers) and logs status, size, time. */
    private fun fetch(url: String) = thread(name = "debug-fetch") {
        val start = System.nanoTime()
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            val body = c.inputStream.use { it.readBytes() }
            val ms = (System.nanoTime() - start) / 1_000_000
            val title = Regex("<title>(.*?)</title>").find(String(body))?.groupValues?.get(1) ?: ""
            Log.i(TAG, "fetch $url -> ${c.responseCode} ${body.size} bytes ${ms}ms title=\"$title\"")
        } catch (e: Exception) {
            val ms = (System.nanoTime() - start) / 1_000_000
            Log.i(TAG, "fetch $url -> error ${e.javaClass.simpleName}: ${e.message} ${ms}ms")
        }
    }
}
