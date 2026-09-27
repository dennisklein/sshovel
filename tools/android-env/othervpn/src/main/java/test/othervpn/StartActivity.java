// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package test.othervpn;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.util.Log;

/** Takes over the VPN (consent granted beforehand with `appops set test.othervpn ACTIVATE_VPN allow`). */
public class StartActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent consent = VpnService.prepare(this);
        Log.i("othervpn", "prepare " + (consent == null ? "granted" : "needs consent"));
        if (consent == null) startService(new Intent(this, OtherVpnService.class));
        finish();
    }
}
