// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package test.othervpn;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

/** Establishes a VPN that routes one unused subnet, and holds it until stopped. */
public class OtherVpnService extends VpnService {
    private ParcelFileDescriptor tun;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (tun == null) {
            tun = new Builder().setSession("othervpn").addAddress("10.99.98.1", 24).addRoute("10.99.99.0", 24).establish();
            Log.i("othervpn", "established " + (tun != null));
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            if (tun != null) tun.close();
        } catch (java.io.IOException ignored) {
        }
        super.onDestroy();
    }
}
