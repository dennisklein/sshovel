// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.tunnel

import android.net.DnsResolver
import android.net.VpnService
import android.os.CancellationSignal
import android.util.Log
import com.github.dennisklein.sshovel.BuildConfig
import com.github.dennisklein.sshovel.core.mobile.Platform
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Implements the Go core's callbacks (ARCHITECTURE §8). Every method may be called from any Go
 * thread, concurrently.
 */
class PlatformBridge(
    private val service: VpnService,
    private val network: NetworkMonitor,
    private val onEngineStatus: (String) -> Unit,
) : Platform {
    private val dnsExecutor = Executors.newCachedThreadPool()

    override fun protect(fd: Int): Boolean = service.protect(fd)

    override fun signDigest(keyAlias: String, digest: ByteArray): ByteArray =
        // Keystore keys arrive in M3; until then only imported keys connect.
        throw UnsupportedOperationException("KEY_UNAVAILABLE: Keystore signing is not implemented yet")

    /** DnsResolver.rawQuery on the underlying network, so the query never enters our VPN. */
    override fun queryUpstreamDNS(query: ByteArray): ByteArray {
        val net = network.current.value ?: throw IOException("no underlying network")
        val answer = CompletableFuture<ByteArray>()
        val cancel = CancellationSignal()
        // Deprecated in API 37 in favour of DnsResolver(Context, Looper), which
        // doesn't exist on API 36 (minSdk).
        @Suppress("DEPRECATION")
        DnsResolver.getInstance().rawQuery(
            net, query, DnsResolver.FLAG_EMPTY, dnsExecutor, cancel,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer_: ByteArray, rcode: Int) {
                    answer.complete(answer_)
                }

                override fun onError(error: DnsResolver.DnsException) {
                    answer.completeExceptionally(IOException("rawQuery: ${error.code}", error))
                }
            },
        )
        return try {
            answer.get(DNS_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            cancel.cancel()
            throw IOException("rawQuery timed out", e)
        }
    }

    override fun onState(stateJSON: String) = onEngineStatus(stateJSON)

    // Messages and events name hosts and destinations, which may only live in the in-memory
    // diagnostics buffer (ARCHITECTURE §9). That buffer arrives in M7; until then they go to
    // logcat in debug builds only.
    override fun log(level: Int, component: String, message: String) {
        if (BuildConfig.DEBUG) Log.println(PRIORITIES.getOrElse(level) { Log.INFO }, "sshovel/$component", message)
    }

    override fun onDnsEvent(eventJSON: String) {
        if (BuildConfig.DEBUG) Log.d("sshovel/DNS", eventJSON)
    }

    override fun onFlowEvent(eventJSON: String) {
        if (BuildConfig.DEBUG) Log.d("sshovel/Flow", eventJSON)
    }

    fun close() = dnsExecutor.shutdown()

    private companion object {
        const val DNS_TIMEOUT_SEC = 5L
        val PRIORITIES = listOf(Log.DEBUG, Log.INFO, Log.WARN, Log.ERROR)
    }
}
