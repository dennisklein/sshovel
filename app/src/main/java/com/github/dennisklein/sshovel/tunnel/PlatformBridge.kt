// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.tunnel

import android.net.DnsResolver
import android.os.CancellationSignal
import android.util.Log
import com.github.dennisklein.sshovel.BuildConfig
import com.github.dennisklein.sshovel.core.mobile.Platform
import com.github.dennisklein.sshovel.diagnostics.Component
import com.github.dennisklein.sshovel.diagnostics.Diagnostics
import com.github.dennisklein.sshovel.diagnostics.FlowEvent
import com.github.dennisklein.sshovel.diagnostics.FlowLog
import com.github.dennisklein.sshovel.diagnostics.FlowOwner
import com.github.dennisklein.sshovel.diagnostics.Level
import com.github.dennisklein.sshovel.keys.KeyRepository
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
    private val protectFd: (Int) -> Boolean,
    private val network: NetworkMonitor,
    private val keys: KeyRepository,
    /** Session engines feed Diagnostics; one-off checks (host key, test) don't. */
    private val diagnostics: Diagnostics? = null,
    /** App label for a flow's src → dst, see [FlowAttribution]. */
    private val appFor: (String, String) -> String? = { _, _ -> null },
    private val onEngineStatus: (String) -> Unit = {},
) : Platform {
    private val dnsExecutor = Executors.newCachedThreadPool()

    override fun protect(fd: Int): Boolean = protectFd(fd)

    override fun signDigest(keyAlias: String, digest: ByteArray): ByteArray = keys.sign(keyAlias, digest)

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
    // diagnostics buffers (ARCHITECTURE §9); debug builds also log them for the emulator runbook.
    override fun log(level: Int, component: String, message: String) {
        if (BuildConfig.DEBUG) Log.println(PRIORITIES.getOrElse(level) { Log.INFO }, "sshovel/$component", message)
        diagnostics?.events?.addFromCore(level, component, message)
    }

    override fun onDnsEvent(eventJSON: String) {
        val d = diagnostics ?: return
        val ev = d.dns.addJson(eventJSON) ?: return
        if (ev.resolvedOutsideRoutes) {
            d.events.add(Level.WARN, Component.DNS, "${ev.name.removeSuffix(".")} → ${ev.answers.joinToString(", ")} is outside routed subnets")
        }
    }

    override fun onFlowEvent(eventJSON: String) {
        if (BuildConfig.DEBUG) Log.d("sshovel/Flow", eventJSON)
        val d = diagnostics ?: return
        val ev = FlowLog.parseEvent(eventJSON) ?: return
        // Ask who owns the socket now: for failures the core waits with its RST until we return.
        val owner = if (ev.event == FlowEvent.CLOSE) FlowOwner() else FlowOwner(appFor(ev.src, ev.dst), d.dns.hostFor(ev.dst.substringBeforeLast(':')))
        d.flows.onEvent(ev, owner)
    }

    fun close() = dnsExecutor.shutdown()

    private companion object {
        const val DNS_TIMEOUT_SEC = 5L
        val PRIORITIES = listOf(Log.DEBUG, Log.INFO, Log.WARN, Log.ERROR)
    }
}
