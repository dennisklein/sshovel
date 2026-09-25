// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.spike;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;

/** Spike 2: tile path when VPN consent is missing. The real app shows the explainer first. */
public class ConsentActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent consent = VpnService.prepare(this);
        if (consent == null) {
            start();
        } else {
            startActivityForResult(consent, 1);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        SpikeLog.i("SPIKE2 consent result=" + (resultCode == RESULT_OK ? "granted" : "denied"));
        if (resultCode == RESULT_OK) start();
        else finish();
    }

    private void start() {
        startForegroundService(SpikeVpnService.startIntent(this, "consent-activity", MainActivity.route(this)));
        finish();
    }
}
