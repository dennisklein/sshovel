// SPDX-FileCopyrightText: 2026 <Copyright holder>
// SPDX-License-Identifier: GPL-3.0-or-later

package example.sshovel.spike;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.service.quicksettings.TileService;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spike 2/3: a bare VpnService. It routes {@code route} into a TUN that nobody
 * answers, and counts what arrives, so spike 3 can tell whether a DNS query
 * was captured by the VPN or went out on the underlying network.
 */
public class SpikeVpnService extends VpnService {
    static final String ACTION_START = "example.sshovel.spike.START";
    static final String ACTION_STOP = "example.sshovel.spike.STOP";
    static final String EXTRA_ORIGIN = "origin";
    static final String EXTRA_ROUTE = "route";

    static volatile boolean running;
    static final AtomicLong packets = new AtomicLong();
    static final AtomicLong dnsPackets = new AtomicLong();

    private ParcelFileDescriptor tun;
    private Thread reader;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        String origin = intent == null ? "null-intent" : intent.getStringExtra(EXTRA_ORIGIN);
        ActivityManager.RunningAppProcessInfo proc = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(proc);
        SpikeLog.i("onStartCommand action=" + action + " origin=" + origin
                + " importance=" + proc.importance + " fgsType=" + BuildConfig.FGS_TYPE);
        if (ACTION_STOP.equals(action)) {
            stop("user");
            return START_NOT_STICKY;
        }
        // ACTION_START, or SERVICE_INTERFACE / null intent when started by Always-on.
        if (!startForegroundSafely()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String route = intent == null || intent.getStringExtra(EXTRA_ROUTE) == null
                ? "0.0.0.0/0" : intent.getStringExtra(EXTRA_ROUTE);
        establish(route);
        return START_STICKY;
    }

    private boolean startForegroundSafely() {
        int type = "specialUse".equals(BuildConfig.FGS_TYPE)
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                : ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED;
        try {
            startForeground(1, notification("Tunnel (spike) up"), type);
            SpikeLog.i("SPIKE2 startForeground OK with type " + BuildConfig.FGS_TYPE);
            return true;
        } catch (RuntimeException e) {
            SpikeLog.e("SPIKE2 startForeground FAILED with type " + BuildConfig.FGS_TYPE, e);
            return false;
        }
    }

    private void establish(String route) {
        if (tun != null) return;
        String[] r = route.split("/");
        try {
            Builder b = new Builder()
                    .setSession("sshovel spike")
                    .setMtu(1500)
                    .addAddress("10.99.0.1", 24)
                    .addRoute(r[0], Integer.parseInt(r[1]))
                    .addDnsServer("10.99.0.53")
                    .setBlocking(true)
                    .setMetered(false)
                    .setConfigureIntent(PendingIntent.getActivity(this, 0,
                            new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
            if (!"0.0.0.0/0".equals(route)) b.addRoute("10.99.0.0", 24);
            tun = b.establish();
        } catch (RuntimeException e) {
            SpikeLog.e("establish failed", e);
        }
        if (tun == null) {
            SpikeLog.i("establish returned null (no consent?)");
            stop("establish-null");
            return;
        }
        running = true;
        SpikeLog.i("VPN up route=" + route + " alwaysOn=" + isAlwaysOn() + " lockdown=" + isLockdownEnabled());
        notifyTile(this);
        reader = new Thread(this::readLoop, "tun-reader");
        reader.start();
    }

    /** Count packets; log the first few and every DNS packet. Never answers. */
    private void readLoop() {
        byte[] buf = new byte[32767];
        try (FileInputStream in = new FileInputStream(tun.getFileDescriptor())) {
            while (running) {
                int n = in.read(buf);
                if (n <= 0) continue;
                long count = packets.incrementAndGet();
                if ((buf[0] >> 4) != 4 || n < 24) continue;
                int ihl = (buf[0] & 0x0f) * 4;
                int proto = buf[9] & 0xff;
                String dst = (buf[16] & 0xff) + "." + (buf[17] & 0xff) + "." + (buf[18] & 0xff) + "." + (buf[19] & 0xff);
                int dport = ((buf[ihl + 2] & 0xff) << 8) | (buf[ihl + 3] & 0xff);
                boolean dns = dport == 53 || dport == 853;
                if (dns) dnsPackets.incrementAndGet();
                if (count <= 10 || dns) {
                    SpikeLog.i("TUN pkt#" + count + " proto=" + proto + " dst=" + dst + ":" + dport);
                }
            }
        } catch (IOException e) {
            if (running) SpikeLog.e("tun read", e);
        }
    }

    private void stop(String why) {
        SpikeLog.i("stopping VPN: " + why);
        running = false;
        if (tun != null) {
            try { tun.close(); } catch (IOException ignored) { }
            tun = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        notifyTile(this);
    }

    @Override
    public void onRevoke() {
        SpikeLog.i("onRevoke()");
        stop("revoked");
    }

    @Override
    public void onDestroy() {
        if (running) stop("onDestroy");
        super.onDestroy();
    }

    private Notification notification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("tunnel_status", "Tunnel status",
                NotificationManager.IMPORTANCE_LOW));
        Intent stop = new Intent(this, SpikeVpnService.class).setAction(ACTION_STOP)
                .putExtra(EXTRA_ORIGIN, "notification");
        return new Notification.Builder(this, "tunnel_status")
                .setSmallIcon(R.drawable.ic_sshovel)
                .setContentTitle("sshovel spike")
                .setContentText(text)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Disconnect",
                        PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE)).build())
                .build();
    }

    static void notifyTile(Context ctx) {
        TileService.requestListeningState(ctx, new ComponentName(ctx, SpikeTileService.class));
    }

    static Intent startIntent(Context ctx, String origin, String route) {
        return new Intent(ctx, SpikeVpnService.class).setAction(ACTION_START)
                .putExtra(EXTRA_ORIGIN, origin).putExtra(EXTRA_ROUTE, route);
    }
}
