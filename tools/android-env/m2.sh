#!/bin/bash
# SPDX-FileCopyrightText: 2026 Dennis Klein
# SPDX-License-Identifier: GPL-3.0-or-later
#
# M2 acceptance (IMPLEMENTATION_PLAN §6), inside the toolbox (see run.sh):
#   1. ./gradlew check assembleDebug (tests, lint, license tasks, page alignment)
#   2. a forbidden-license dependency makes checkLicenses fail
#   3. on an API 36 emulator: the debug profile connects to test-env,
#      wiki.corp.test loads (app and Chrome), public sites keep their speed,
#      and Wi-Fi → mobile data → Wi-Fi reconnects automatically.
# Results: tools/android-env/out/ (summary.txt first).
set -uo pipefail

PKG=com.github.dennisklein.sshovel
OUT=/work/tools/android-env/out
APK=/work/app/build/outputs/apk/debug/app-debug.apk
rm -rf "$OUT"; mkdir -p "$OUT"
exec > >(tee "$OUT/run.log") 2>&1

owner=$(stat -c %u:%g /work)
cleanup() {
    adb emu kill >/dev/null 2>&1 || true
    [ -n "${LOGCAT_PID:-}" ] && kill "$LOGCAT_PID" 2>/dev/null
    for p in "$OUT" /work/.gradle /work/.kotlin /work/build /work/app/build /work/app/libs /work/local.properties \
             /work/test-env/keys /work/test-env/hostkeys; do
        [ -e "$p" ] && chown -R "$owner" "$p"
    done
}
trap cleanup EXIT

RESULTS=()
result() { RESULTS+=("$(printf '%-4s %s' "$1" "$2")"); echo "RESULT: $1 $2"; }
say() { printf '\n=== %s\n' "$*"; }
die() { echo "FATAL: $*"; result FAIL "$*"; exit 1; }
gradle() { (cd /work && ./gradlew --no-daemon --console=plain "$@"); }

# --- Build -------------------------------------------------------------------

say "Toolchain"
cat /opt/sdk-versions; java -version 2>&1 | head -n1; (cd /work/core && go version); go-licenses --help >/dev/null 2>&1 && echo "go-licenses OK"; reuse --version | head -n1

say "Wait for test-env's host key (pinned by the debug build)"
for _ in $(seq 1 60); do [ -f /work/test-env/hostkeys/ssh_host_ed25519_key.pub ] && break; sleep 1; done
[ -f /work/test-env/hostkeys/ssh_host_ed25519_key.pub ] || die "test-env jump host never wrote its host key"
ssh-keygen -lf /work/test-env/hostkeys/ssh_host_ed25519_key.pub

echo "sdk.dir=$ANDROID_HOME" > /work/local.properties
say "./gradlew check assembleDebug"
if gradle check :app:assembleDebug > "$OUT/gradle-check.log" 2>&1; then
    result PASS "gradle check (unit tests, lint, checkLicenses, collectGoLicenses, reuseLint, verifyPageAlignment)"
else
    tail -n 60 "$OUT/gradle-check.log"; die "gradle check failed (tools/android-env/out/gradle-check.log)"
fi
grep -E "^OK +|collectGoLicenses:" "$OUT/gradle-check.log" | sort -u | tee "$OUT/alignment.txt"
cp /work/app/build/reports/lint-results-debug.html "$OUT/" 2>/dev/null || true
zipalign=$(ls "$ANDROID_HOME"/build-tools/*/zipalign | tail -n1)
"$zipalign" -c -P 16 -v 4 "$APK" | tail -n1 | tee -a "$OUT/alignment.txt"

say "Forbidden license probe (mysql-connector-j, GPL-2.0 with FOSS exception)"
if gradle :app:checkLicenses -Psshovel.licenseProbe > "$OUT/license-probe.log" 2>&1; then
    result FAIL "checkLicenses accepted a GPL-2.0 dependency"
else
    grep -i -m5 -E "mysql|license" "$OUT/license-probe.log"
    result PASS "checkLicenses fails on a forbidden license (log: license-probe.log)"
fi

# --- Emulator ------------------------------------------------------------------

say "Boot emulator"
timeout 3 bash -c '</dev/tcp/127.0.0.1/2222' || die "test-env jump host not reachable on 127.0.0.1:2222"
emulator -avd sshovel -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -accel on \
    >> "$OUT/emulator.log" 2>&1 &
timeout 600 adb wait-for-device || die "emulator did not come up"
for _ in $(seq 1 300); do [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 2; done
sleep 10
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard; adb shell svc power stayon true
for s in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global $s 0; done
adb shell getprop ro.build.fingerprint | tee "$OUT/device.txt"

# adb root restarts adbd, which kills a running `adb logcat`; do it once, now.
adb root >/dev/null 2>&1; sleep 2; adb wait-for-device
adb logcat -c
adb logcat -v time > "$OUT/logcat.txt" 2>/dev/null &
LOGCAT_PID=$!

app() { # app <cmd> [key value]...
    local cmd=$1; shift; local args=()
    while [ $# -ge 2 ]; do args+=(--es "$1" "$2"); shift 2; done
    adb shell am start -n "$PKG/.ui.MainActivity" --es cmd "$cmd" "${args[@]}" >/dev/null
}
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
mark() { adb shell log -t sshovel-m2 "=== $*"; echo "--- $*"; }
# since <mark> <regex> <timeout>: waits for regex in logcat after the given mark line.
wait_log() {
    local m=$1 re=$2 t=$3
    for _ in $(seq 1 "$t"); do
        awk -v m="=== $m" 'index($0, m) {on=1} on' "$OUT/logcat.txt" | grep -E -m1 "$re" && return 0
        sleep 1
    done
    return 1
}
# fetch <mark> <url>: prints the app's fetch result line.
fetch() {
    mark "$1"; app fetch url "$2"
    wait_log "$1" "sshovel/Debug.*fetch $2 ->" 40 | sed 's/.*fetch /fetch /' || echo "fetch $2 -> no result"
}
# ok_count <file>: fetches that got the 204; median_ms reads only those.
ok_count() { grep -c -- '-> 204 ' "$1"; }
median_ms() { grep -- '-> 204 ' | grep -o '[0-9]*ms' | tr -d ms | sort -n | awk '{a[NR]=$1} END {print (NR ? a[int((NR+1)/2)] : -1)}'; }

say "Install"
adb install -r -g "$APK" || die "install failed"
adb shell appops set "$PKG" ACTIVATE_VPN allow   # VPN consent without the dialog (explainer: M5)
adb shell am start -n "$PKG/.ui.MainActivity" >/dev/null; sleep 3; shot 1-off

PUBLIC=http://connectivitycheck.gstatic.com/generate_204
say "Public site before connecting (5 fetches)"
for i in 1 2 3 4 5; do fetch "pub-before-$i" "$PUBLIC"; done | tee "$OUT/public-before.txt"
before=$(median_ms < "$OUT/public-before.txt")

say "Connect"
mark connect; app connect
if wait_log connect "sshovel/State.*On\(" 60; then result PASS "connects to test-env"; else
    wait_log connect "sshovel/State" 1; grep "sshovel/" "$OUT/logcat.txt" | tail -n 30; die "never reached On"; fi
sleep 2; shot 2-on
adb shell dumpsys connectivity | grep -E "VPN|10\.77\.0\.0/24|198\.18" | head -n 20 > "$OUT/vpn-network.txt"

say "Intranet through the tunnel"
r=$(fetch wiki-1 http://wiki.corp.test/); echo "$r"
echo "$r" | grep -q ' 200 .*title="Welcome to nginx!"' && result PASS "app loads http://wiki.corp.test/ ($r)" \
    || result FAIL "app fetch of wiki.corp.test: $r"

say "Public site while connected (5 fetches)"
for i in 1 2 3 4 5; do fetch "pub-during-$i" "$PUBLIC"; done | tee "$OUT/public-during.txt"
during=$(median_ms < "$OUT/public-during.txt")
echo "median before=${before}ms during=${during}ms"
if [ "$(ok_count "$OUT/public-before.txt")" -lt 5 ] || [ "$(ok_count "$OUT/public-during.txt")" -lt 5 ]; then
    result FAIL "public fetches failed ($(ok_count "$OUT/public-before.txt")/5 before, $(ok_count "$OUT/public-during.txt")/5 connected got 204; see public-*.txt)"
elif [ "$during" -le $(( before * 2 + 50 )) ]; then
    result PASS "public sites unaffected (median ${before}ms before, ${during}ms connected; not routed into the TUN)"
else
    result FAIL "public fetch slower or failing (median ${before}ms before, ${during}ms connected)"
fi

say "Chrome"
if adb shell pm list packages | grep -q com.android.chrome; then
    adb shell pm grant com.android.chrome android.permission.POST_NOTIFICATIONS 2>/dev/null || true
    adb shell 'echo "chrome --disable-fre --no-default-browser-check --no-first-run" > /data/local/tmp/chrome-command-line'
    adb shell am set-debug-app --persistent com.android.chrome
    adb shell am start -a android.intent.action.VIEW -d http://wiki.corp.test/ com.android.chrome >/dev/null
    seen=
    for _ in $(seq 1 10); do
        sleep 3
        adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
        adb exec-out cat /sdcard/ui.xml > "$OUT/chrome-ui.xml"
        grep -q "Welcome to nginx" "$OUT/chrome-ui.xml" && { seen=1; break; }
    done
    shot 3-chrome-wiki
    if [ -n "$seen" ]; then result PASS "Chrome loads http://wiki.corp.test/ (3-chrome-wiki.png)"
    else result FAIL "Chrome page doesn't show the wiki (see 3-chrome-wiki.png, chrome-ui.xml)"; fi
    adb shell am start -n "$PKG/.ui.MainActivity" >/dev/null
else
    result SKIP "Chrome not installed on this image; the app's own fetch covers wiki.corp.test"
fi

say "Wi-Fi → mobile data"
mark wifi-off; adb shell svc wifi disable
if wait_log wifi-off "sshovel/State.*Reconnecting" 30 && wait_log wifi-off "sshovel/State.*On\(" 60; then
    r=$(fetch wiki-cell http://wiki.corp.test/)
    echo "$r" | grep -q ' 200 ' && result PASS "Wi-Fi → mobile data reconnects, wiki loads ($r)" || result FAIL "after Wi-Fi → mobile data: $r"
else
    result FAIL "no reconnect after disabling Wi-Fi"
fi
shot 4-cellular
say "Mobile data → Wi-Fi"
mark wifi-on; adb shell svc wifi enable
if wait_log wifi-on "sshovel/State.*Reconnecting" 45 && wait_log wifi-on "sshovel/State.*On\(" 60; then
    r=$(fetch wiki-wifi http://wiki.corp.test/)
    echo "$r" | grep -q ' 200 ' && result PASS "mobile data → Wi-Fi reconnects, wiki loads ($r)" || result FAIL "after mobile data → Wi-Fi: $r"
else
    result FAIL "no reconnect after enabling Wi-Fi"
fi

say "Notification and disconnect"
adb shell cmd statusbar expand-notifications; sleep 2; shot 5-notification; adb shell cmd statusbar collapse
mark disconnect; app disconnect
wait_log disconnect "sshovel/State.*Off" 30 && result PASS "disconnects" || result FAIL "no Off after disconnect"
sleep 2; shot 6-off-again

say "Summary"
{
    echo "sshovel M2 run $(date -u +%FT%TZ)"
    echo "SDK: $(cat /opt/sdk-versions); device: $(cat "$OUT/device.txt" | tr -d '\r')"
    echo
    printf '%s\n' "${RESULTS[@]}"
    echo
    echo "## State transitions"
    grep "sshovel/State\|sshovel-m2" "$OUT/logcat.txt" | sed 's/^[0-9-]* //'
} > "$OUT/summary.txt"
cat "$OUT/summary.txt"
