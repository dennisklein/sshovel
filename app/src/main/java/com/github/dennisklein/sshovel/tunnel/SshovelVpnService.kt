// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.tunnel

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.IpPrefix
import android.net.Network
import android.os.ParcelFileDescriptor
import android.util.Log
import com.github.dennisklein.sshovel.BuildConfig
import com.github.dennisklein.sshovel.SshovelApplication
import com.github.dennisklein.sshovel.core.mobile.Engine
import com.github.dennisklein.sshovel.core.mobile.Mobile
import com.github.dennisklein.sshovel.data.Apps
import com.github.dennisklein.sshovel.data.Auth
import com.github.dennisklein.sshovel.data.Ipv4Prefix
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Owns the Go engine and the TUN (ARCHITECTURE §7). SSH connects first; the TUN is established
 * when the engine reports sshReady, so auth and host-key failures never touch routing. Under
 * Always-on lockdown the TUN comes first.
 *
 * Every engine call and state decision runs on one worker thread, in order.
 */
class SshovelVpnService : android.net.VpnService() {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "sshovel-tunnel") }
    private val workerDispatcher = worker.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + workerDispatcher)

    private lateinit var app: SshovelApplication
    private lateinit var notifications: TunnelNotifications
    private val controller get() = app.container.tunnelController
    private val network get() = app.container.networkMonitor

    /** One connection attempt: profile, engine, and whether the TUN is up. Worker thread only. */
    private class Session(val profile: Profile, val engine: Engine, val bridge: PlatformBridge) {
        var tunUp = false
        var statsJob: Job? = null
    }

    private var session: Session? = null
    private var lastNetwork: Network? = null

    override fun onCreate() {
        super.onCreate()
        running = this
        app = application as SshovelApplication
        notifications = TunnelNotifications(this)
        lastNetwork = network.current.value
        scope.launch {
            combine(controller.state, controller.activeProfile, controller.stats) { s, p, st -> Triple(s, p, st) }
                .distinctUntilChanged()
                .collect { (s, p, st) -> if (session != null) notifications.showStatus(s, p, st) }
        }
        scope.launch { network.current.collect { onNetwork(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // First, always (M0: works from app, tile, consent activity, and Always-on at boot).
        startForeground(
            TunnelNotifications.STATUS_ID,
            notifications.status(controller.state.value, controller.activeProfile.value, controller.stats.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
        )
        val action = intent?.action
        scope.launch {
            when (action) {
                ACTION_CONNECT -> intent.getStringExtra(EXTRA_PROFILE_ID)?.let { connect(it) } ?: finishIfIdle()
                ACTION_DISCONNECT -> disconnect()
                ACTION_RETRY -> session?.engine?.retryNow() ?: finishIfIdle()
                // The system starts us for Always-on VPN with this action (or a null intent after
                // a restart): connect the default profile.
                SERVICE_INTERFACE, null -> {
                    val p = app.container.profiles.defaultProfile()
                    if (p != null) connect(p.id) else fail(null, TunnelState.NeedsAttention(Codes.INTERNAL, "no default profile"))
                }
                else -> finishIfIdle()
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // Another VPN took over, or the user revoked consent in Settings.
        scope.launch { fail(session, TunnelState.NeedsAttention(Codes.VPN_REVOKED)) }
    }

    override fun onDestroy() {
        // Queued in order on the worker: end the session, then cancel the scope.
        scope.launch { session?.let { end(it, TunnelState.Off, keepService = true) } }
        worker.execute { scope.cancel() }
        worker.shutdown()
        if (running === this) running = null
        super.onDestroy()
    }

    // ---- worker thread ---------------------------------------------------------

    private suspend fun connect(profileId: String) {
        val profile = app.container.profiles.profile(profileId)
            ?: return fail(null, TunnelState.NeedsAttention(Codes.INTERNAL, "unknown profile"))
        session?.let {
            if (it.profile.id == profileId) return // already connecting or connected
            end(it, TunnelState.Off, keepService = true)
        }
        if (prepare(this) != null) return fail(null, TunnelState.NeedsAttention(Codes.VPN_PERMISSION))

        controller.onProfile(profile)
        report(TunnelState.Connecting("resolving"))
        lateinit var s: Session
        val bridge = PlatformBridge(::protect, network, app.container.keys) { json -> scope.launch { onEngineStatus(s, json) } }
        s = Session(profile, Mobile.newEngine(bridge), bridge)
        session = s

        val key = if (profile.auth.kind == Auth.IMPORTED) {
            try {
                app.container.keys.importedKey(profile)
            } catch (e: Exception) {
                Log.e(TAG, "vault entry unreadable", e)
                null
            } ?: return fail(s, TunnelState.NeedsAttention(Codes.KEY_UNAVAILABLE, "imported key missing or unreadable"))
        } else {
            null
        }
        try {
            var fd = -1
            if (isLockdownEnabled) {
                fd = establish(profile)?.detachFd()
                    ?: return fail(s, TunnelState.NeedsAttention(Codes.VPN_PERMISSION))
                s.tunUp = true
            }
            s.engine.start(fd, profile.toJson(), key)
        } catch (e: Exception) {
            return fail(s, TunnelState.NeedsAttention(Codes.of(e), e.message.orEmpty()))
        } finally {
            key?.fill(0) // Go zeroes its copy; this is ours
        }
        s.statsJob = scope.launch { pollStats(s) }
    }

    private fun onEngineStatus(s: Session, json: String) {
        if (session !== s) return // a stopped session's late status
        val status = try {
            EngineStatus.parse(json)
        } catch (e: Exception) {
            Log.e(TAG, "bad engine status", e)
            return
        }
        if (status.state == "sshReady" && !s.tunUp) {
            val pfd = try {
                establish(s.profile)
            } catch (e: Exception) {
                Log.e(TAG, "establish failed", e)
                null
            } ?: return fail(s, TunnelState.NeedsAttention(Codes.VPN_PERMISSION))
            try {
                s.engine.attachTun(pfd.detachFd())
                s.tunUp = true
            } catch (e: Exception) {
                return fail(s, TunnelState.NeedsAttention(Codes.of(e), e.message.orEmpty()))
            }
        }
        when (val state = status.toTunnelState()) {
            is TunnelState.NeedsAttention -> fail(s, state)
            TunnelState.Off -> {} // only after our own stop; end() reports it
            else -> report(state)
        }
    }

    private fun disconnect() {
        session?.let { end(it, TunnelState.Off) } ?: finishIfIdle()
    }

    /** Ends [s] (if current) with [state] shown, alerting for errors, and stops the service. */
    private fun fail(s: Session?, state: TunnelState.NeedsAttention) {
        if (s != null && session !== s) return
        if (s != null) end(s, state) else {
            report(state)
            finishIfIdle()
        }
        notifications.showAlert(state, controller.activeProfile.value)
    }

    private fun end(s: Session, state: TunnelState, keepService: Boolean = false) {
        if (session !== s) return
        session = null
        s.statsJob?.cancel()
        if (state == TunnelState.Off) report(TunnelState.Disconnecting)
        s.engine.stop() // blocks until off; closes the TUN fd
        s.bridge.close()
        report(state)
        if (state == TunnelState.Off) controller.onProfile(null)
        if (!keepService) finishIfIdle()
    }

    /** Publishes [state]; debug builds also log it for the emulator runbook. */
    private fun report(state: TunnelState) {
        if (BuildConfig.DEBUG) Log.i("sshovel/State", state.toString())
        controller.onState(state)
    }

    private fun finishIfIdle() {
        if (session != null) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onNetwork(net: Network?) {
        val previous = lastNetwork
        lastNetwork = net
        val s = session ?: return
        if (s.tunUp) setUnderlyingNetworks(net?.let { arrayOf(it) } ?: emptyArray())
        // A new network (or the first one after none): the SSH socket is bound to the old one.
        if (net != null && net != previous) s.engine.networkChanged()
    }

    private suspend fun pollStats(s: Session) {
        while (scope.isActive && session === s) {
            controller.onStats(TunnelStats.parse(s.engine.statsJSON()))
            delay(STATS_INTERVAL_MS)
        }
    }

    /** Builds the TUN (ARCHITECTURE §3). Returns null if consent is missing. */
    private fun establish(p: Profile): ParcelFileDescriptor? {
        val tun = Ipv4Prefix.parse(p.tun.cidr)
        val b = Builder()
            .setSession(p.name)
            .setMtu(p.tun.mtu)
            .addAddress(tun.firstHost(), tun.length)
            .addDnsServer(p.tun.dnsVirtualIp)
            .setMetered(false)
            .setUnderlyingNetworks(network.current.value?.let { arrayOf(it) })
            .setConfigureIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
        p.routes.map(Ipv4Prefix::parse).forEach { b.addRoute(it.address, it.length) }
        p.excludedRoutes.map(Ipv4Prefix::parse).forEach { b.excludeRoute(IpPrefix(it.address, it.length)) }
        p.dns.searchDomains.forEach { b.addSearchDomain(it) }
        // Allowed XOR disallowed: calling both throws (ARCHITECTURE §3).
        when (p.apps.mode) {
            Apps.INCLUDE -> p.apps.packages.forEach { pkg -> skipMissing(pkg) { b.addAllowedApplication(it) } }
            Apps.EXCLUDE -> p.apps.packages.forEach { pkg -> skipMissing(pkg) { b.addDisallowedApplication(it) } }
        }
        return b.establish()
    }

    private inline fun skipMissing(pkg: String, add: (String) -> Unit) {
        try {
            add(pkg)
        } catch (_: PackageManager.NameNotFoundException) {
            Log.w(TAG, "app not installed, skipped: $pkg")
        }
    }

    companion object {
        private const val TAG = "sshovel/Service"

        @Volatile private var running: SshovelVpnService? = null

        /**
         * Protects [fd] from the VPN if the tunnel service is running (e.g. FetchHostKey while
         * connected). Without a running service there's no VPN of ours to bypass.
         */
        fun protectIfRunning(fd: Int): Boolean = running?.protect(fd) ?: true
        private const val STATS_INTERVAL_MS = 1000L
        const val ACTION_CONNECT = "com.github.dennisklein.sshovel.CONNECT"
        const val ACTION_DISCONNECT = "com.github.dennisklein.sshovel.DISCONNECT"
        const val ACTION_RETRY = "com.github.dennisklein.sshovel.RETRY"
        const val EXTRA_PROFILE_ID = "profileId"

        fun connectIntent(context: Context, profileId: String): Intent =
            Intent(context, SshovelVpnService::class.java).setAction(ACTION_CONNECT).putExtra(EXTRA_PROFILE_ID, profileId)
    }
}
