#!/bin/bash
# SPDX-FileCopyrightText: 2026 Dennis Klein
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Runs inside the toolbox container (see run.sh). Builds the AAR and three APK
# variants, boots an API 36 emulator and drives every M0 scenario over adb.
# Results: /work/spikes/out/ (summary.txt first).
#
# Usage: run-spikes.sh [A] [B] [C]     (default: all variants)
#   A  systemExempted, exported=true   spikes 1, 2, 3 (DNS + Always-on), 4
#   B  specialUse,     exported=true   spike 2 (tile + FGS type)
#   C  systemExempted, exported=false  spike 3 (Always-on / exported)
set -uo pipefail

PKG=com.github.dennisklein.sshovel.spike
TILE="$PKG/.SpikeTileService"
APPDIR=/work/spikes/android
KEYS=/work/test-env/keys/authorized_keys
OUT=/work/spikes/out
VARIANTS=("$@")
[ ${#VARIANTS[@]} -gt 0 ] || VARIANTS=(A B C)

rm -rf "$OUT"
mkdir -p "$OUT"
exec > >(tee "$OUT/run.log") 2>&1

owner=$(stat -c %u:%g /work)
cp "$KEYS" /tmp/authorized_keys.orig
cleanup() {
    cp /tmp/authorized_keys.orig "$KEYS"
    adb emu kill >/dev/null 2>&1 || true
    [ -n "${LOGCAT_PID:-}" ] && kill "$LOGCAT_PID" 2>/dev/null
    for p in "$OUT" "$APPDIR/.gradle" "$APPDIR/build" "$APPDIR/app/build" "$APPDIR/app/libs" \
             "$APPDIR/local.properties" "$KEYS"; do
        [ -e "$p" ] && chown -R "$owner" "$p"
    done
}
trap cleanup EXIT

say() { printf '\n=== %s\n' "$*"; adb shell log -t sshovel-spike "=== $*" >/dev/null 2>&1 || true; }
die() { echo "FATAL: $*"; exit 1; }

# --- Build -------------------------------------------------------------------

say "Toolchain"
cat /opt/sdk-versions
java -version 2>&1 | head -n1
(cd /work/spikes/core && go version)

say "SPIKE1 build core.aar + 16 KB alignment"
/work/spikes/core/build-aar.sh 2>&1 | tee "$OUT/aar-build.log" | grep -E '^(OK|FAIL)' | tee "$OUT/alignment.txt"
[ -f "$APPDIR/app/libs/core.aar" ] || die "AAR build failed, see aar-build.log"

agp=$(curl -fsSL https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml \
      | grep -oE '<version>[0-9]+\.[0-9]+\.[0-9]+</version>' | sed 's/<[^>]*>//g' | sort -V | tail -n1)
echo "AGP: ${agp:-fallback from gradle.properties}"
echo "sdk.dir=$ANDROID_HOME" > "$APPDIR/local.properties"

declare -A FGS=([A]=systemExempted [B]=specialUse [C]=systemExempted [16k]=systemExempted)
declare -A EXPORTED=([A]=true [B]=true [C]=false [16k]=true)
for v in "${VARIANTS[@]}"; do
    say "Build variant $v (${FGS[$v]}, exported=${EXPORTED[$v]})"
    (cd "$APPDIR" && ./gradlew --no-daemon -q :app:assembleDebug ${agp:+-PagpVersion=$agp} \
        -PfgsType="${FGS[$v]}" -PvpnExported="${EXPORTED[$v]}") || die "Gradle build of variant $v failed"
    cp "$APPDIR/app/build/outputs/apk/debug/app-debug.apk" "$OUT/$v.apk"
done
say "SPIKE1 APK alignment"
/work/spikes/core/check-alignment.sh "$OUT/${VARIANTS[0]}.apk" | tee -a "$OUT/alignment.txt"
zipalign=$(ls "$ANDROID_HOME"/build-tools/*/zipalign | tail -n1)
"$zipalign" -c -P 16 -v 4 "$OUT/${VARIANTS[0]}.apk" | tail -n1 | tee -a "$OUT/alignment.txt"

# --- Emulator ------------------------------------------------------------------

wait_boot() {
    timeout 600 adb wait-for-device || die "emulator did not come up"
    for _ in $(seq 1 300); do
        [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
        sleep 2
    done
    [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ] || die "emulator did not finish booting"
    sleep 10
    adb shell input keyevent KEYCODE_WAKEUP
    adb shell wm dismiss-keyguard
    adb shell svc power stayon true
    for s in window_animation_scale transition_animation_scale animator_duration_scale; do
        adb shell settings put global $s 0
    done
}

logcat_to() {
    [ -n "${LOGCAT_PID:-}" ] && kill "$LOGCAT_PID" 2>/dev/null
    adb logcat -v threadtime >> "$1" 2>/dev/null &
    LOGCAT_PID=$!
}

say "Boot emulator"
timeout 3 bash -c '</dev/tcp/127.0.0.1/2222' && echo "test-env jump host reachable on :2222" \
    || echo "WARNING: jump host not reachable on 127.0.0.1:2222; spike 4 will fail"
start_emulator() {
    emulator -avd "$1" -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect \
        -accel on >> "$OUT/emulator.log" 2>&1 &
    wait_boot
}
start_emulator spike
adb shell getprop ro.build.fingerprint

# --- Helpers -------------------------------------------------------------------

# app <cmd> [key value]...  runs a MainActivity automation command.
app() {
    local cmd=$1; shift
    local args=()
    while [ $# -ge 2 ]; do args+=(--es "$1" "$2"); shift 2; done
    adb shell am start -n "$PKG/.MainActivity" --es cmd "$cmd" "${args[@]}" >/dev/null
}
tile()  { adb shell cmd statusbar click-tile "$TILE"; }
home()  { adb shell input keyevent KEYCODE_HOME; }
bg_kill() { home; sleep 2; adb shell am kill "$PKG"; sleep 2; }
shot()  { adb exec-out screencap -p > "$DIR/$1.png"; }
vpn_state() {
    if adb shell dumpsys activity services "$PKG" | grep -q SpikeVpnService; then
        echo "-> service running"; else echo "-> service not running"; fi
    adb shell dumpsys connectivity | grep -m1 -o 'VPN CONNECTED[^,]*' || echo "-> no VPN network"
}
# tap <resource-id or text>: taps the first matching UI node.
tap() {
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb exec-out cat /sdcard/ui.xml > /tmp/ui.xml
    local xy
    xy=$(python3 - "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
want = sys.argv[1]
for n in ET.parse('/tmp/ui.xml').iter('node'):
    if want in (n.get('resource-id'), n.get('text')):
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
    if [ -n "$xy" ]; then echo "tap '$1' at $xy"; adb shell input tap $xy; else echo "no UI node '$1'"; return 1; fi
}

# --- Scenarios -------------------------------------------------------------------

install() {
    say "$V: install ${FGS[$V]} exported=${EXPORTED[$V]}"
    adb uninstall "$PKG" >/dev/null 2>&1
    adb install -g "$OUT/$V.apk"
    adb shell cmd statusbar add-tile "$TILE"
}

spike1() {
    say "SPIKE1 load AAR, build gVisor stack"
    app hello; sleep 8
}

spike2() {
    say "SPIKE2a tile tap, no VPN consent yet, app process killed"
    bg_kill; tile; sleep 5; shot 2a-consent-dialog
    tap android:id/button1 || tap OK
    sleep 8; shot 2a-after-consent; vpn_state
    say "SPIKE2a tile tap off"
    tile; sleep 5; vpn_state

    say "SPIKE2b tile tap on, consent granted, app process killed"
    bg_kill; tile; sleep 8; shot 2b-on; vpn_state
    say "SPIKE2b tile tap off, app process killed"
    bg_kill; tile; sleep 5; vpn_state

    say "SPIKE2c tile tap on from the lock screen"
    adb shell locksettings set-pin 1234
    adb shell input keyevent KEYCODE_SLEEP; sleep 3
    adb shell input keyevent KEYCODE_WAKEUP; sleep 3
    tile; sleep 8; shot 2c-locked; vpn_state
    tile; sleep 5; vpn_state
    adb shell input keyevent KEYCODE_MENU; sleep 1
    adb shell input text 1234; adb shell input keyevent KEYCODE_ENTER; sleep 3
    adb shell locksettings clear --old 1234
    adb shell wm dismiss-keyguard
}

spike3_dns() {
    say "SPIKE3 DNS: VPN routes 0.0.0.0/0 into a TUN nobody answers"
    app start-vpn route 0.0.0.0/0; sleep 8; vpn_state
    say "SPIKE3 rawQuery via underlying network (expect answer, 0 TUN packets)"
    app dns-underlying qname example.com; sleep 10
    say "SPIKE3 control: rawQuery via default network (expect failure, >0 TUN packets)"
    app dns-default qname example.com; sleep 10
    say "SPIKE3 with Private DNS strict (dns.google)"
    adb shell settings put global private_dns_specifier dns.google
    adb shell settings put global private_dns_mode hostname; sleep 5
    app dns-underlying qname example.com; sleep 10
    app dns-default qname example.com; sleep 10
    adb shell settings put global private_dns_mode opportunistic
    adb shell settings delete global private_dns_specifier
    app stop-vpn; sleep 4
}

spike4() {
    say "SPIKE4 Keystore key -> SSH auth against test-env"
    app genkey; sleep 8
    local line
    line=$(adb logcat -d -s sshovel-spike | grep -oE 'restrict,port-forwarding ecdsa-sha2-nistp256 [A-Za-z0-9+/=]+ sshovel@spike' | tail -n1)
    if [ -z "$line" ]; then echo "no authorized_keys line in log"; return; fi
    { cat /tmp/authorized_keys.orig; echo "$line"; } > "$KEYS"
    app auth host 10.0.2.2 port 2222 user tester probe 10.77.0.20:80; sleep 15
}

spike3_always_on() {
    say "SPIKE3 Always-on (exported=${EXPORTED[$V]}): configure, reboot, expect system start"
    adb shell appops set "$PKG" ACTIVATE_VPN allow
    adb shell am start -a android.settings.VPN_SETTINGS >/dev/null; sleep 4; shot 3-vpn-settings
    adb shell settings put secure always_on_vpn_app "$PKG"
    adb shell settings put secure always_on_vpn_lockdown 0
    adb reboot
    wait_boot
    logcat_to "$DIR/logcat.txt"
    say "SPIKE3 after reboot"
    sleep 30; vpn_state; shot 3-after-reboot
    adb shell dumpsys connectivity | grep -iE 'always|lockdown|vpn' | head -n 30 > "$DIR/dumpsys-vpn.txt"
    adb shell settings delete secure always_on_vpn_app
    app stop-vpn; sleep 4
    say "SPIKE3 manual start with exported=${EXPORTED[$V]}"
    app start-vpn route 10.77.0.0/24; sleep 8; vpn_state
    app stop-vpn; sleep 4
}

for V in "${VARIANTS[@]}"; do
    DIR="$OUT/$V"; mkdir -p "$DIR"
    logcat_to "$DIR/logcat.txt"
    install
    case $V in
        A) spike1; spike2; spike3_dns; spike4; spike3_always_on ;;
        B) spike1; spike2 ;;
        C) spike1; spike3_always_on ;;
    esac
done

# SPIKE1 on a 16 KB page-size kernel: a misaligned .so fails to load there.
if [ -d /root/.android/avd/spike16k.avd ] && [ -f "$OUT/A.apk" ]; then
    say "SPIKE1 16 KB page-size emulator"
    adb emu kill >/dev/null 2>&1
    for _ in $(seq 1 30); do adb devices | grep -q emulator || break; sleep 2; done
    V=16k; DIR="$OUT/16k"; mkdir -p "$DIR"
    start_emulator spike16k
    logcat_to "$DIR/logcat.txt"
    echo "PAGE_SIZE=$(adb shell getconf PAGE_SIZE | tr -d '\r')" | tee -a "$OUT/alignment.txt"
    adb install -g "$OUT/A.apk"
    spike1
    VARIANTS+=(16k)
else
    echo "no 16 KB system image; skipped the 16 KB load test" | tee -a "$OUT/alignment.txt"
fi

# --- Summary -------------------------------------------------------------------

sleep 2
kill "$LOGCAT_PID" 2>/dev/null
{
    echo "sshovel M0 spike run $(date -u +%FT%TZ)"
    echo "SDK: $(cat /opt/sdk-versions); AGP: ${agp:-fallback}"
    echo; echo "## Alignment"; cat "$OUT/alignment.txt"
    for V in "${VARIANTS[@]}"; do
        echo; echo "## Variant $V (${FGS[$V]}, exported=${EXPORTED[$V]})"
        grep -E 'sshovel-spike|MissingForegroundServiceType|ForegroundServiceStartNotAllowed|BackgroundServiceStartNotAllowed|SecurityException.*(Vpn|sshovel)|FATAL EXCEPTION' \
            "$OUT/$V/logcat.txt" | sed -E 's/^[0-9-]+ //'
    done
} > "$OUT/summary.txt"
say "Done. Results in spikes/out/ (start with summary.txt)"
