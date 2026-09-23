// SPDX-FileCopyrightText: 2026 <Copyright holder>
// SPDX-License-Identifier: GPL-3.0-or-later

package example.sshovel.spike;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Spike 2: toggle the VPN from the tile while the app is in the background. */
public class SpikeTileService extends TileService {
    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = SpikeVpnService.running;
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("sshovel");
        t.setSubtitle(on ? "Spike" : "Off");
        t.setStateDescription(on ? "Spike" : "Off");
        t.updateTile();
    }

    @Override
    public void onClick() {
        SpikeLog.i("SPIKE2 tile onClick locked=" + isLocked() + " secure=" + isSecure()
                + " running=" + SpikeVpnService.running);
        if (VpnService.prepare(this) != null) {
            PendingIntent pi = PendingIntent.getActivity(this, 0,
                    new Intent(this, ConsentActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE);
            try {
                startActivityAndCollapse(pi);
                SpikeLog.i("SPIKE2 no consent yet -> startActivityAndCollapse(ConsentActivity) OK");
            } catch (RuntimeException e) {
                SpikeLog.e("SPIKE2 startActivityAndCollapse FAILED", e);
            }
            return;
        }
        try {
            if (SpikeVpnService.running) {
                startService(new Intent(this, SpikeVpnService.class)
                        .setAction(SpikeVpnService.ACTION_STOP).putExtra(SpikeVpnService.EXTRA_ORIGIN, "tile"));
                SpikeLog.i("SPIKE2 tile -> startService(STOP) OK");
            } else {
                startForegroundService(SpikeVpnService.startIntent(this, "tile", MainActivity.route(this)));
                SpikeLog.i("SPIKE2 tile -> startForegroundService(START) OK");
            }
        } catch (RuntimeException e) {
            SpikeLog.e("SPIKE2 tile start/stop FAILED", e);
        }
    }
}
