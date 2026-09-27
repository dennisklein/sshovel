#!/bin/bash
# SPDX-FileCopyrightText: 2026 Dennis Klein
# SPDX-License-Identifier: GPL-3.0-or-later
#
# M5 acceptance (IMPLEMENTATION_PLAN §6), inside the toolbox (see run.sh), through the real
# Quick Settings tile (cmd statusbar click-tile, the same SystemUI path as a tap; M0):
#   DESIGN_BRIEF §6 flow 3: tile without VPN consent → explainer → Android's dialog → On
#                           (with a detour through "Cancel" → permission denied → Try again)
#   flow 2: tile tap when off → Connecting → On; tile, notification, and home in sync
#   flow 4: On → Wi-Fi → mobile data → Reconnecting → On
#   flow 5: connect → host key changed → Needs attention → mismatch screen
#   another VPN app starts → VPN_REVOKED
#   Always-on in system settings connects at boot; with lockdown the TUN comes up first
# The "require unlock" lock-screen check needs a physical device (IMPLEMENTATION_PLAN §7).
# Results: tools/android-env/out/ (summary.txt first).
set -uo pipefail

OUT=/work/tools/android-env/out
rm -rf "$OUT"; mkdir -p "$OUT"
exec > >(tee "$OUT/run.log") 2>&1
source /work/tools/android-env/lib.sh
trap cleanup EXIT
P=debug-test-env
TILE=$PKG/.tile.TunnelTileService
OTHER=test.othervpn
OTHER_APK=/work/tools/android-env/othervpn/build/outputs/apk/debug/othervpn-debug.apk

click_tile() { mark "$1"; adb shell cmd statusbar click-tile "$TILE"; }
state_after() { wait_log "$1" "sshovel/State.*$2" "${3:-60}" >/dev/null; }
tile_after() { wait_log "$1" "sshovel/Tile.*tile $2" "${3:-30}" >/dev/null; }
home() { adb shell am start -n "$PKG/.ui.MainActivity" >/dev/null; sleep 2; }
boot_wait() {
    timeout 600 adb wait-for-device || die "emulator did not come back"
    for _ in $(seq 1 300); do [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 2; done
    adb root >/dev/null 2>&1; sleep 2; adb wait-for-device
    adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard
}

say "Toolchain"; toolchain
say "Wait for test-env's host key"; wait_hostkey
echo "sdk.dir=$ANDROID_HOME" > /work/local.properties
say "./gradlew check assembleDebug"
if gradle check :app:assembleDebug > "$OUT/gradle-check.log" 2>&1; then
    result PASS "gradle check (unit tests, lint, license tasks, reuseLint, verifyPageAlignment)"
else
    tail -n 60 "$OUT/gradle-check.log"; die "gradle check failed (gradle-check.log)"
fi
gradle -p tools/android-env/othervpn assembleDebug > "$OUT/othervpn-build.log" 2>&1 || die "othervpn build failed"

say "Boot emulator"; boot_emulator; start_logcat
say "Install (no VPN consent yet)"
adb install -r -g "$APK" || die "install failed"
adb install -r "$OTHER_APK" || die "othervpn install failed"
home; sleep 3  # seeds the test-env profile as the default
adb shell cmd statusbar add-tile "$TILE"; sleep 2

# --- Flow 3: tile without consent ---------------------------------------------------------

say "Flow 3: tile without VPN consent"
click_tile f3
sleep 4; shot 1-explainer
if tap_text "Continue"; then
    # Android's dialog: first Cancel → the denied screen, then Try again → OK.
    if tap_text "Cancel" && sleep 3 && shot 2-denied && tap_text "Try again"; then
        denied_ok=1
    else
        denied_ok=0
    fi
    sleep 2
    if tap_text "OK" && state_after f3 'On\(' 60; then
        [ "$denied_ok" = 1 ] && result PASS "tile without consent → explainer → Android's dialog (Cancel → denied screen → Try again) → OK → On" ||
            result PASS "tile without consent → explainer → Android's dialog → On (the Cancel/Try again detour didn't run)"
    else
        result FAIL "no On after accepting Android's VPN dialog (see 1-explainer.png, 2-denied.png)"
    fi
else
    shot 1-explainer; result FAIL "the tile didn't open the explainer (no Continue button)"
fi
sleep 2; shot 3-on-after-consent

# --- Flow 2: tile when off, permission granted -----------------------------------------------

say "Flow 2: tile off → On, in sync"
click_tile f2-off
state_after f2-off 'Off' 30 && tile_after f2-off 'Off inactive' || result FAIL "tile tap while On didn't disconnect"
click_tile f2
if state_after f2 'Connecting' 20 && tile_after f2 'Connecting… active' && state_after f2 'On\(' 60 && tile_after f2 'test-env active'; then
    sleep 1
    adb shell cmd statusbar expand-settings; sleep 2; shot 4-tile-and-notification
    notif=$(adb shell dumpsys notification --noredact | grep -c "Connected to test-env")
    adb shell cmd statusbar collapse; home; shot 5-home-on
    [ "$notif" -ge 1 ] && result PASS "tile tap when off → Connecting → On; tile \"test-env\", notification \"Connected to test-env\", home On" ||
        result FAIL "On, but the notification doesn't say \"Connected to test-env\""
else
    result FAIL "tile tap when off didn't go Connecting → On with the tile in sync"
fi

# --- Flow 4: network switch -----------------------------------------------------------------

say "Flow 4: Wi-Fi → mobile data → Wi-Fi"
mark f4-off; adb shell svc wifi disable
state_after f4-off 'Reconnecting' 30 && state_after f4-off 'On\(' 60 && tile_after f4-off 'test-env active' &&
    result PASS "Wi-Fi → mobile data: Reconnecting → On, tile back to \"test-env\"" || result FAIL "no reconnect after disabling Wi-Fi"
mark f4-on; adb shell svc wifi enable
state_after f4-on 'Reconnecting' 45 && state_after f4-on 'On\(' 60 &&
    result PASS "mobile data → Wi-Fi: Reconnecting → On" || result FAIL "no reconnect after enabling Wi-Fi"

# --- Another VPN app ---------------------------------------------------------------------------

say "Another VPN app takes over"
adb shell appops set "$OTHER" ACTIVATE_VPN allow
mark revoke; adb shell am start -n "$OTHER/.StartActivity" >/dev/null
if state_after revoke 'NeedsAttention\(code=VPN_REVOKED' 30 && tile_after revoke 'Tap to fix inactive'; then
    result PASS "another VPN app started → VPN_REVOKED; tile \"Tap to fix\""
else
    result FAIL "no VPN_REVOKED after another VPN app took over"
fi
sleep 1; home; shot 6-revoked
adb shell am force-stop "$OTHER"; adb shell appops set "$OTHER" ACTIVATE_VPN ignore

# --- Flow 5: host key changed -------------------------------------------------------------------

say "Flow 5: host key changed"
rm -f "$HOSTKEYS"/ssh_host_*
for _ in $(seq 1 20); do sleep 1; [ -f "$HOSTKEYS/ssh_host_ed25519_key.pub" ] && [ -f "$HOSTKEYS/ssh_host_ecdsa_key.pub" ] && break; done
sleep 2
# The tile shows "Tap to fix" after the revoke, which opens the app: connect from the app.
mark f5; app connect profile "$P"
if state_after f5 'NeedsAttention\(code=HOST_KEY_MISMATCH' 60 && tile_after f5 'Tap to fix inactive'; then
    click_tile f5-tap; sleep 3
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb exec-out cat /sdcard/ui.xml > "$OUT/mismatch-ui.xml"
    shot 7-mismatch
    grep -q "Server identity changed" "$OUT/mismatch-ui.xml" && grep -q "Received now" "$OUT/mismatch-ui.xml" &&
        result PASS "host key changed → Needs attention; tile \"Tap to fix\" opens the mismatch screen" ||
        result FAIL "tile tap didn't show the mismatch screen (7-mismatch.png)"
else
    result FAIL "no HOST_KEY_MISMATCH after replacing the host key"
fi
tap_text "Disconnect" || true
debug f5-forget forget profile "$P" >/dev/null
r=$(debug f5-trust trust profile "$P"); echo "$r"

# --- Always-on at boot ----------------------------------------------------------------------------

# always_on_boot <lockdown 0|1>: sets Always-on, reboots, and waits for On. Nothing starts the
# app; only the system can bring the tunnel up. The tunnel may come up before a mark could be
# written, so this looks at everything logged since the reboot ($OUT/boot.txt).
always_on_boot() {
    adb shell settings put secure always_on_vpn_app "$PKG"
    adb shell settings put secure always_on_vpn_lockdown "$1"
    kill "$LOGCAT_PID" 2>/dev/null; adb reboot; sleep 5; boot_wait
    : > "$OUT/boot.txt"
    adb logcat -v time > "$OUT/boot.txt" 2>/dev/null &   # the whole buffer since boot
    LOGCAT_PID=$!
    for _ in $(seq 1 120); do grep -q 'sshovel/State.*On(' "$OUT/boot.txt" && break; sleep 1; done
    cat "$OUT/boot.txt" >> "$OUT/boot-all.txt"
    grep -q 'sshovel/State.*On(' "$OUT/boot.txt"
}
say "Always-on at boot"
if always_on_boot 0 && grep -q 'sshovel/Service.*connect alwaysOn=true lockdown=false' "$OUT/boot.txt"; then
    result PASS "Always-on: the system starts sshovel at boot and it connects the default profile"
else
    result FAIL "Always-on didn't connect at boot"
fi
sleep 2; shot 8-always-on
say "Always-on with lockdown at boot"
if always_on_boot 1 && grep -q 'sshovel/Service.*connect alwaysOn=true lockdown=true' "$OUT/boot.txt"; then
    # fetch reads $OUT/logcat.txt: point the logcat stream there again.
    kill "$LOGCAT_PID" 2>/dev/null
    adb logcat -v time >> "$OUT/logcat.txt" 2>/dev/null &
    LOGCAT_PID=$!
    r=$(fetch lockdown-wiki http://wiki.corp.test/); echo "$r"
    echo "$r" | grep -q ' 200 ' && result PASS "Lockdown: connects at boot with the TUN up first; wiki loads ($r)" ||
        result FAIL "Lockdown connected but the wiki fetch failed: $r"
else
    result FAIL "Always-on with lockdown didn't connect at boot"
fi
adb shell settings delete secure always_on_vpn_app; adb shell settings delete secure always_on_vpn_lockdown

say "Summary"
cat "$OUT/boot-all.txt" >> "$OUT/logcat.txt" 2>/dev/null
write_summary M5
