// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.spike;

import android.app.Activity;
import android.app.StatusBarManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.ConnectivityManager;
import android.net.DnsResolver;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import com.github.dennisklein.sshovel.core.mobile.DigestSigner;
import com.github.dennisklein.sshovel.core.mobile.Mobile;

/** One screen with a button per M0 check. Results go to the log view and logcat. */
public class MainActivity extends Activity {
    private static final String KEY_ALIAS = "sshovel-spike-key";
    private static final int REQ_VPN = 1;

    private final Executor bg = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Network underlying;
    private TextView logView;
    private EditText host, port, user, probe, routeField, qname;

    static String route(Context ctx) {
        return ctx.getSharedPreferences("spike", MODE_PRIVATE).getString("route", "0.0.0.0/0");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(32, 96, 32, 96);

        section(col, "Spike 1: gomobile AAR with gVisor");
        button(col, "Go hello (build gVisor stack)", v -> bg.execute(this::hello));

        section(col, "Spike 2: VpnService + tile (FGS type " + BuildConfig.FGS_TYPE + ")");
        routeField = field(col, "VPN route (0.0.0.0/0 for spike 3, 10.77.0.0/24 for spike 4)", route(this));
        button(col, "Allow notifications", v -> requestPermissions(
                new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 2));
        button(col, "Add tile (requestAddTileService)", v -> addTile());
        button(col, "Start VPN", v -> startVpn());
        button(col, "Stop VPN", v -> stopVpn());

        section(col, "Spike 3: DnsResolver.rawQuery on the underlying network");
        qname = field(col, "Name", "example.com");
        button(col, "Query via underlying (non-VPN) network", v -> dnsQuery(true));
        button(col, "Control: query via default network (the VPN)", v -> dnsQuery(false));

        section(col, "Spike 4: Keystore ECDSA P-256 SSH auth");
        host = field(col, "Host", "10.0.2.2");
        port = field(col, "Port", "2222");
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        user = field(col, "User", "tester");
        probe = field(col, "direct-tcpip probe target (optional)", "10.77.0.20:80");
        button(col, "Generate key + copy authorized_keys line", v -> bg.execute(this::generateKey));
        button(col, "Test SSH auth with Keystore key", v -> bg.execute(this::testAuth));

        section(col, "Log (also: adb logcat -s " + SpikeLog.TAG + ")");
        logView = new TextView(this);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextSize(11);
        logView.setTextIsSelectable(true);
        col.addView(logView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        setContentView(scroll);
        SpikeLog.setListener(line -> main.post(() -> logView.append(line + "\n")));

        watchUnderlyingNetwork();
        // Give the network callback a moment before running an adb command.
        main.postDelayed(() -> handleCommand(getIntent()), 2000);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleCommand(intent);
    }

    /**
     * Automation hook for spikes/env/run-spikes.sh:
     * {@code adb shell am start -n <pkg>/.MainActivity --es cmd <name> [--es key value ...]}.
     * Each command does exactly what the matching button does.
     */
    private void handleCommand(Intent intent) {
        String cmd = intent == null ? null : intent.getStringExtra("cmd");
        if (cmd == null) return;
        intent.removeExtra("cmd");
        SpikeLog.i("command " + cmd);
        setIfPresent(intent, "route", routeField);
        setIfPresent(intent, "qname", qname);
        setIfPresent(intent, "host", host);
        setIfPresent(intent, "port", port);
        setIfPresent(intent, "user", user);
        setIfPresent(intent, "probe", probe);
        switch (cmd) {
            case "hello" -> bg.execute(this::hello);
            case "start-vpn" -> startVpn();
            case "stop-vpn" -> stopVpn();
            case "dns-underlying" -> dnsQuery(true);
            case "dns-default" -> dnsQuery(false);
            case "genkey" -> bg.execute(this::generateKey);
            case "auth" -> bg.execute(this::testAuth);
            default -> SpikeLog.i("unknown command " + cmd);
        }
    }

    private static void setIfPresent(Intent intent, String key, EditText field) {
        String v = intent.getStringExtra(key);
        if (v != null) field.setText(v);
    }

    private void hello() {
        try {
            SpikeLog.i("SPIKE1 " + Mobile.hello());
        } catch (Exception e) {
            SpikeLog.e("SPIKE1 hello failed", e);
        }
    }

    @Override
    protected void onDestroy() {
        SpikeLog.setListener(null);
        super.onDestroy();
    }

    // --- Spike 2 -------------------------------------------------------------

    private void stopVpn() {
        startService(new Intent(this, SpikeVpnService.class)
                .setAction(SpikeVpnService.ACTION_STOP).putExtra(SpikeVpnService.EXTRA_ORIGIN, "app"));
    }

    private void startVpn() {
        getSharedPreferences("spike", MODE_PRIVATE).edit()
                .putString("route", routeField.getText().toString().trim()).apply();
        Intent consent = VpnService.prepare(this);
        if (consent != null) {
            startActivityForResult(consent, REQ_VPN);
        } else {
            startForegroundService(SpikeVpnService.startIntent(this, "app", route(this)));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_VPN) {
            SpikeLog.i("consent result=" + resultCode);
            if (resultCode == RESULT_OK) {
                startForegroundService(SpikeVpnService.startIntent(this, "app", route(this)));
            }
        }
    }

    private void addTile() {
        StatusBarManager sbm = getSystemService(StatusBarManager.class);
        sbm.requestAddTileService(new ComponentName(this, SpikeTileService.class), "sshovel",
                Icon.createWithResource(this, R.drawable.ic_sshovel), getMainExecutor(),
                result -> SpikeLog.i("requestAddTileService result=" + result));
    }

    // --- Spike 3 -------------------------------------------------------------

    /** Tracks the best non-VPN internet network, as NetworkMonitor will. */
    private void watchUnderlyingNetwork() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        NetworkRequest req = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        cm.registerBestMatchingNetworkCallback(req, new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                underlying = network;
                SpikeLog.i("underlying network available: " + network);
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                SpikeLog.i("underlying " + network + " dns=" + lp.getDnsServers()
                        + " privateDnsActive=" + lp.isPrivateDnsActive()
                        + " privateDnsServer=" + lp.getPrivateDnsServerName());
            }

            @Override
            public void onLost(Network network) {
                if (network.equals(underlying)) underlying = null;
                SpikeLog.i("underlying network lost: " + network);
            }
        }, main);
    }

    private void dnsQuery(boolean viaUnderlying) {
        Network net = viaUnderlying ? underlying : null;
        if (viaUnderlying && net == null) {
            SpikeLog.i("SPIKE3 no underlying network yet");
            return;
        }
        String label = viaUnderlying ? "underlying " + net : "default network";
        byte[] query = buildQuery(qname.getText().toString().trim());
        long pktBefore = SpikeVpnService.dnsPackets.get();
        long t0 = System.nanoTime();
        CancellationSignal cancel = new CancellationSignal();
        main.postDelayed(cancel::cancel, 6000);
        DnsResolver.getInstance().rawQuery(net, query,
                DnsResolver.FLAG_NO_CACHE_LOOKUP | DnsResolver.FLAG_NO_CACHE_STORE,
                bg, cancel, new DnsResolver.Callback<byte[]>() {
                    @Override
                    public void onAnswer(byte[] answer, int rcode) {
                        int an = answer.length >= 8 ? ((answer[6] & 0xff) << 8) | (answer[7] & 0xff) : -1;
                        SpikeLog.i("SPIKE3 " + label + ": answer rcode=" + rcode + " answers=" + an
                                + " bytes=" + answer.length + " in " + ms(t0)
                                + " ms; DNS packets seen on our TUN during query: "
                                + (SpikeVpnService.dnsPackets.get() - pktBefore)
                                + " (VPN running=" + SpikeVpnService.running + ")");
                    }

                    @Override
                    public void onError(DnsResolver.DnsException e) {
                        SpikeLog.i("SPIKE3 " + label + ": error code=" + e.code + " " + e.getMessage()
                                + " after " + ms(t0) + " ms; DNS packets seen on our TUN: "
                                + (SpikeVpnService.dnsPackets.get() - pktBefore));
                    }
                });
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    /** Minimal DNS wire query: A record, recursion desired. */
    private static byte[] buildQuery(String name) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int id = (int) (Math.random() * 65535);
        b.write(id >> 8);
        b.write(id);
        b.write(0x01); // RD
        b.write(0x00);
        b.write(0); b.write(1); // QDCOUNT
        for (int i = 0; i < 6; i++) b.write(0);
        for (String l : name.split("\\.")) {
            b.write(l.length());
            b.write(l.getBytes(), 0, l.length());
        }
        b.write(0);
        b.write(0); b.write(1); // QTYPE A
        b.write(0); b.write(1); // QCLASS IN
        return b.toByteArray();
    }

    // --- Spike 4 -------------------------------------------------------------

    private void generateKey() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (!ks.containsAlias(KEY_ALIAS)) {
                try {
                    generate(true);
                } catch (StrongBoxUnavailableException e) {
                    SpikeLog.i("SPIKE4 StrongBox unavailable, retrying without");
                    generate(false);
                }
            }
            PrivateKey pk = (PrivateKey) ks.getKey(KEY_ALIAS, null);
            KeyInfo info = KeyFactory.getInstance(pk.getAlgorithm(), "AndroidKeyStore")
                    .getKeySpec(pk, KeyInfo.class);
            SpikeLog.i("SPIKE4 key securityLevel=" + info.getSecurityLevel()
                    + " (1=TEE, 2=StrongBox, 0=software)");
            byte[] pkix = ks.getCertificate(KEY_ALIAS).getPublicKey().getEncoded();
            String line = Mobile.authorizedKeyLine(pkix, "sshovel@spike");
            SpikeLog.i("SPIKE4 authorized_keys: " + line);
            main.post(() -> getSystemService(ClipboardManager.class)
                    .setPrimaryClip(ClipData.newPlainText("authorized_keys", line)));
        } catch (Exception e) {
            SpikeLog.e("SPIKE4 key generation failed", e);
        }
    }

    private void generate(boolean strongBox) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        kpg.initialize(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                .setIsStrongBoxBacked(strongBox)
                .build());
        kpg.generateKeyPair();
    }

    private void testAuth() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (!ks.containsAlias(KEY_ALIAS)) {
                SpikeLog.i("SPIKE4 generate a key first");
                return;
            }
            byte[] pkix = ks.getCertificate(KEY_ALIAS).getPublicKey().getEncoded();
            DigestSigner signer = (alias, digest) -> {
                KeyStore k = KeyStore.getInstance("AndroidKeyStore");
                k.load(null);
                Signature s = Signature.getInstance("NONEwithECDSA");
                s.initSign((PrivateKey) k.getKey(alias, null));
                s.update(digest);
                byte[] der = s.sign();
                SpikeLog.i("SPIKE4 SignDigest(" + alias + ", " + digest.length + " bytes) -> DER " + der.length + " bytes");
                return der;
            };
            String msg = Mobile.testAuth(host.getText().toString().trim(),
                    Long.parseLong(port.getText().toString().trim()),
                    user.getText().toString().trim(), pkix, KEY_ALIAS, signer,
                    probe.getText().toString().trim());
            SpikeLog.i("SPIKE4 OK: " + msg);
        } catch (Exception e) {
            SpikeLog.e("SPIKE4 auth failed", e);
        }
    }

    // --- UI helpers ------------------------------------------------------------

    private void section(LinearLayout col, String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setPadding(0, 32, 0, 8);
        col.addView(t);
    }

    private void button(LinearLayout col, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        col.addView(b);
    }

    private EditText field(LinearLayout col, String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine();
        col.addView(e);
        return e;
    }
}
