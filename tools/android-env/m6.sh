#!/bin/bash
# SPDX-FileCopyrightText: 2026 Dennis Klein
# SPDX-License-Identifier: GPL-3.0-or-later
#
# M6 acceptance (IMPLEMENTATION_PLAN §6), inside the toolbox (see run.sh):
#   - gradle check, and the instrumented tests: Compose UI tests for the profile editor's
#     validation and the host key mismatch screen (can't be dismissed), plus the M3 key tests
#   - onboarding end to end on a fresh install: welcome → key created on the device → its public
#     key installed on test-env → server added → server verified and trusted → connection test
#     → Quick Settings tile added through Android's own prompt → Connect now → On
#   - screenshots of the screens, light and dark, in the brand scheme (wallpaper colors off) so
#     they can be compared with the handoff (docs/design)
#   - About shows the legal notices; the licenses screen has the Android and Go lists, Go included
# Results: tools/android-env/out/ (summary.txt first, then the PNGs).
set -uo pipefail

OUT=/work/tools/android-env/out
rm -rf "$OUT"; mkdir -p "$OUT"
exec > >(tee "$OUT/run.log") 2>&1
source /work/tools/android-env/lib.sh
trap cleanup EXIT
AUTH_KEYS=/work/test-env/keys/acceptance_authorized_keys

home() { adb shell am start -n "$PKG/.ui.MainActivity" >/dev/null; sleep 2; }
open() { mark "open-$1-$RANDOM"; app open screen "$1" ${2:+arg "$2"}; sleep "${3:-2}"; }
back() { adb shell input keyevent KEYCODE_BACK; sleep 1; }
state_after() { wait_log "$1" "sshovel/State.*$2" "${3:-60}" >/dev/null; }
# The AVD has a hardware keyboard; with this off Android shows no soft keyboard, so nothing on
# screen is hidden behind one. Never press back to close a keyboard: when none is showing, back
# leaves the onboarding step instead.
no_soft_keyboard() { adb shell settings put secure show_ime_with_hard_keyboard 0; }
# type_into <label> <text>: focuses the field labelled <label> and types at its end (no spaces).
type_into() { tap_text "$1" && sleep 1 && adb shell input keyevent KEYCODE_MOVE_END && adb shell input text "$2"; }
# add_chip <label> <text>: taps an "Add …" field, types, and submits with Enter.
add_chip() { tap_text "$1" && sleep 1 && adb shell input text "$2" && adb shell input keyevent KEYCODE_ENTER && sleep 1; }

say "Toolchain"; toolchain
say "Wait for test-env's host key"; wait_hostkey
echo "sdk.dir=$ANDROID_HOME" > /work/local.properties
say "./gradlew check assembleDebug assembleDebugAndroidTest"
if gradle check :app:assembleDebug :app:assembleDebugAndroidTest > "$OUT/gradle-check.log" 2>&1; then
    result PASS "gradle check (unit tests, lint, license tasks, reuseLint, verifyPageAlignment)"
else
    tail -n 60 "$OUT/gradle-check.log"; die "gradle check failed (gradle-check.log)"
fi

say "Boot emulator"; boot_emulator; start_logcat; no_soft_keyboard

# --- Instrumented tests ------------------------------------------------------------------------

say "connectedDebugAndroidTest"
if gradle :app:connectedDebugAndroidTest > "$OUT/android-test.log" 2>&1; then
    n=$(grep -ho 'tests="[0-9]*"' /work/app/build/outputs/androidTest-results/connected/debug/*.xml 2>/dev/null | grep -o '[0-9]*' | awk '{s+=$1} END {print s}')
    result PASS "instrumented tests: Compose UI tests (editor validation, mismatch screen can't be dismissed) and key tests (${n:-?} tests)"
else
    # Which tests failed and why, from the JUnit XML (the HTML report can't be attached).
    for x in /work/app/build/outputs/androidTest-results/connected/debug/*.xml; do
        tr '\n' ' ' < "$x" | grep -o '<testcase [^>]*>[^<]*<failure[^>]*>[^<]\{0,600\}' |
            sed -e 's/<testcase [^>]*name="\([^"]*\)" classname="\([^"]*\)"[^>]*>/\n\2.\1:/' -e 's/<failure[^>]*>//'
    done > "$OUT/android-test-failures.txt"
    cat "$OUT/android-test-failures.txt"
    result FAIL "instrumented tests failed (android-test.log, app/build/reports/androidTests)"
fi
cp -r /work/app/build/reports/androidTests "$OUT/androidTests-report" 2>/dev/null

# --- Onboarding end to end ----------------------------------------------------------------------

say "Fresh install without the seeded test-env profile"
adb uninstall "$PKG" >/dev/null 2>&1
adb install -r -g "$APK" || die "install failed"
# adb shell joins its arguments into one remote command line: keep each run-as call simple.
adb shell run-as "$PKG" mkdir -p files && adb shell run-as "$PKG" touch files/no-seed ||
    die "run-as failed (debuggable build?)"
adb shell appops set "$PKG" ACTIVATE_VPN allow   # the consent flow is M5's; here Connect now goes straight on
app wallpaper value false; sleep 1                # the brand scheme, as in the handoff
home

onb_ok=1
step() { # step <name> <condition...>: runs the condition, screenshots, records the first failure
    local name=$1; shift
    if "$@"; then shot "$name"; else shot "$name"; [ "$onb_ok" = 1 ] && fail_at=$name; onb_ok=0; fi
}
say "O1 welcome (opens by itself on first run)"
step o1-welcome shows "Reach your intranet from any app" 20
tap_text "Get started"
say "O2 create key"
step o2-create shows "Create a key"
tap_text "Create key"
step o2-created shows "Key created" 30
tap_text "Next"
say "O3 install the public key on test-env"
step o3-install shows "Install the public key"
r=$(debug o3-keys key-list); echo "$r"
line=$(echo "$r" | grep -o '| .*' | head -n1 | sed 's/^| //')
if [ -n "$line" ]; then
    echo "$line" >> "$AUTH_KEYS"; echo "installed: $line"
else
    onb_ok=0; fail_at=o3-key-line
fi
tap_text "Show QR code" && sleep 2 && shot o3-qr && back
tap_text "I’ve added it"
say "O4 add the server"
step o4-server shows "Add your server"
o4_ok=1
type_into "Profile name" "Office" || o4_ok=0
type_into "Host" "10.0.2.2" || o4_ok=0
type_into "22" "22" || o4_ok=0            # port 22 → 2222
type_into "Username" "tester" || o4_ok=0
add_chip "Add subnet" "10.77.0.0/24" || o4_ok=0
type_into "Intranet DNS server" "10.77.0.53" || o4_ok=0
add_chip "Add suffix" "corp.test" || o4_ok=0
[ "$o4_ok" = 1 ] || { onb_ok=0; fail_at=${fail_at:-o4-fields}; }
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml > "$OUT/o4-ui.xml"
sleep 1; shot o4-filled
tap_text "Next"
say "O5 verify the server"
step o5-verify shows "Trust this server" 30
tap_text "Trust this server"
say "O6 connection test"
if shows "Connection works" 60; then shot o6-test-ok; else shot o6-test; onb_ok=0; fail_at=${fail_at:-o6-test}; fi
tap_text "Next"
say "O7 add the tile through Android's prompt"
step o7-tile shows "Add the Quick Settings tile"
tap_text "Add tile"; sleep 3; shot o7-system-prompt
tap_text "Add tile"            # SystemUI's own dialog
if shows "Tile added" 20; then shot o8-tile-added; else shot o8-tile; onb_ok=0; fail_at=${fail_at:-o8-tile}; fi
tiles=$(adb shell settings get secure sysui_qs_tiles)
echo "$tiles" | grep -q "TunnelTileService" || { onb_ok=0; fail_at=${fail_at:-tile-not-in-settings}; }
tap_text "Next"
say "O9 done → Connect now"
step o9-done shows "Setup complete"
mark onb-connect
tap_text "Connect now"
if state_after onb-connect 'On\(' 60; then sleep 2; shot o10-home-on; else onb_ok=0; fail_at=${fail_at:-connect}; fi
[ "$onb_ok" = 1 ] &&
    result PASS "onboarding end to end: key created, installed, server added, verified, tested (Connection works), tile added via requestAddTileService, Connect now → On" ||
    result FAIL "onboarding stopped at ${fail_at:-?} (see o*.png)"

# --- Screens, light and dark ---------------------------------------------------------------------

P=$(debug list-p profile-list | head -n1 | awk '{print $2}')
K=$(debug list-k key-list | grep -v ' end ' | head -n1 | awk '{print $2}')
echo "profile $P key $K"
# A second profile for the list (H1) and the switch dialog.
debug add-lab profile-add name Lab key "$K" >/dev/null

say "Screens in light and dark"
for t in light dark; do
    app theme value "$t"; sleep 1
    open home; shot "h3-on-$t"
    open profile "$P" 3; shot "p6-read-only-$t"
    open home
    mark "off-$t"; app disconnect; state_after "off-$t" 'Off' 20; sleep 1; shot "h1-off-$t"
    open profile "$P" 3; shot "p2-profile-$t"
    open profile "" 3; shot "p1-new-$t"; back
    open home
    open keys; shot "k1-keys-$t"
    open key "$K"; shot "k2-key-$t"
    open settings; shot "g5-settings-$t"
    open about; shot "about-$t"
    open license; shot "license-$t"
    open licenses 4; shot "licenses-$t"
    open home
done
app theme value system

say "Host key mismatch (S4)"
rm -f "$HOSTKEYS"/ssh_host_*
for _ in $(seq 1 20); do sleep 1; [ -f "$HOSTKEYS/ssh_host_ed25519_key.pub" ] && [ -f "$HOSTKEYS/ssh_host_ecdsa_key.pub" ] && break; done
sleep 2
mark s4; app connect profile "$P"
if state_after s4 'NeedsAttention\(code=HOST_KEY_MISMATCH' 60 && shows "Received now" 20; then
    shot s4-mismatch
    back                                             # back is "Disconnect" (handoff S4)
    state_after s4 'Off' 10 || shows "Connect" 10
    shows "Server identity changed" 3 && result FAIL "back left the mismatch screen showing" ||
        result PASS "host key changed → the mismatch screen opens by itself; it offers only Disconnect / Review in profile; back = Disconnect"
else
    shot s4; result FAIL "no mismatch screen after the host key changed"
fi
debug s4-forget forget profile "$P" >/dev/null
debug s4-trust trust profile "$P" >/dev/null

say "About and licenses"
open about
if shows "free software under the GNU GPL v3 or later" 10 && shows "ABSOLUTELY NO WARRANTY" 3 && shows "View license" 3 && shows "github.com/dennisklein/sshovel/tree/" 3; then
    open license
    shows "GNU GENERAL PUBLIC LICENSE" 10 && result PASS "About: copyright, free software / no warranty, View license (bundled GPL text), Source code link" ||
        result FAIL "View license didn't render the bundled LICENSE"
else
    result FAIL "About lacks a legal notice (about-light.png)"
fi
unzip -p "$APK" assets/go_licenses.json > "$OUT/go_licenses.json" 2>/dev/null
unzip -p "$APK" res/raw/aboutlibraries.json > "$OUT/aboutlibraries.json" 2>/dev/null ||
    unzip -l "$APK" | grep -i aboutlibraries | head
go_n=$(grep -o '"module":' "$OUT/go_licenses.json" | wc -l)
go_self=$(grep -c '"module":"Go go' "$OUT/go_licenses.json")
report_n=$(cd /work/core && GOOS=android GOARCH=arm64 go-licenses report ./mobile --ignore github.com/dennisklein/sshovel 2>/dev/null | grep -c .)
android_n=$(grep -o '"uniqueId"' "$OUT/aboutlibraries.json" 2>/dev/null | wc -l)
open licenses 4
# Scroll down (slow drags, no fling) until the Go section is on screen.
ui_go=0
for _ in $(seq 1 60); do
    adb shell input swipe 500 1800 500 400 600; sleep 0.3
    shows "gvisor.dev/gvisor" 1 && { ui_go=1; break; }
done
shot licenses-go
echo "go entries $go_n (go-licenses report $report_n + Go itself $go_self), android libraries $android_n, Go section on screen $ui_go"
[ "$go_n" -eq $((report_n + 1)) ] && [ "$go_self" = 1 ] && [ "$android_n" -gt 0 ] && [ "$ui_go" = 1 ] &&
    result PASS "Open-source licenses: $android_n Android libraries (AboutLibraries) and $go_n Go entries (go-licenses report + Go itself), each with its text" ||
    result FAIL "licenses: Go entries $go_n vs report $report_n + Go ($go_self), Android $android_n, Go section shown $ui_go"

say "Summary"
write_summary M6
