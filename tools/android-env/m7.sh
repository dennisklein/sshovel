#!/bin/bash
# SPDX-FileCopyrightText: 2026 Dennis Klein
# SPDX-License-Identifier: GPL-3.0-or-later
#
# M7 acceptance (IMPLEMENTATION_PLAN §6), inside the toolbox (see run.sh):
#   - gradle check, and the instrumented tests, among them: every @Preview at font scale 2
#     without cut-off text (FontScaleTest), the Accessibility Test Framework's checks (the engine
#     of Accessibility Scanner) on every screen preview (AccessibilityChecksTest), and the hero's
#     TalkBack announcements through the connect flow (HeroAnnouncementsTest)
#   - DESIGN_BRIEF §6 flow 6: an intranet page fails → Diagnostics shows the DNS entry and the
#     connection's failure reason (test-env: 10.77.0.20:443 is refused by PermitOpen, 10.77.0.21
#     has no host, git.corp.test resolves outside the routed subnet)
#   - the On-state warning card's "View diagnostics" opens the Connections tab; Share as text file
#   - screenshots of Diagnostics (light/dark) and of the main screens at 200 % font
#   - predictive back: the back gesture leaves every sub-screen and closes a sheet
# A TalkBack walkthrough by ear stays a check on a device (IMPLEMENTATION_PLAN §7).
# Results: tools/android-env/out/ (summary.txt first, then the PNGs).
set -uo pipefail

OUT=/work/tools/android-env/out
rm -rf "$OUT"; mkdir -p "$OUT"
exec > >(tee "$OUT/run.log") 2>&1
source /work/tools/android-env/lib.sh
trap cleanup EXIT
P=debug-test-env

home() { adb shell am start -n "$PKG/.ui.MainActivity" >/dev/null; sleep 2; }
open() { mark "open-$1-$RANDOM"; app open screen "$1" ${2:+arg "$2"}; sleep "${3:-2}"; }
state_after() { wait_log "$1" "sshovel/State.*$2" "${3:-60}" >/dev/null; }

say "Toolchain"; toolchain
say "Wait for test-env's host key"; wait_hostkey
echo "sdk.dir=$ANDROID_HOME" > /work/local.properties
say "./gradlew check assembleDebug assembleDebugAndroidTest"
if gradle check :app:assembleDebug :app:assembleDebugAndroidTest > "$OUT/gradle-check.log" 2>&1; then
    result PASS "gradle check (unit tests incl. the error catalog against core/errcode, lint, license tasks, reuseLint, verifyPageAlignment)"
else
    tail -n 60 "$OUT/gradle-check.log"; die "gradle check failed (gradle-check.log)"
fi

say "Boot emulator"; boot_emulator; start_logcat
# Gesture navigation, for the predictive back checks below.
adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural >/dev/null 2>&1; sleep 2

# --- Instrumented tests ------------------------------------------------------------------------

say "connectedDebugAndroidTest"
XML=/work/app/build/outputs/androidTest-results/connected/debug
if gradle :app:connectedDebugAndroidTest > "$OUT/android-test.log" 2>&1; then
    count() { grep -ho "testsuite name=\"[^\"]*$1\"[^>]*tests=\"[0-9]*\"" "$XML"/*.xml 2>/dev/null | grep -o 'tests="[0-9]*"' | grep -o '[0-9]*' | awk '{s+=$1} END {print s+0}'; }
    n=$(grep -ho 'tests="[0-9]*"' "$XML"/*.xml 2>/dev/null | grep -o '[0-9]*' | awk '{s+=$1} END {print s}')
    result PASS "instrumented tests (${n:-?}): font scale 2 on every preview ($(count FontScaleTest)), accessibility checks on every screen preview ($(count AccessibilityChecksTest)), hero announcements, editor validation, mismatch screen, keys"
else
    # Which tests failed and why, from the JUnit XML (the HTML report can't be attached).
    for x in "$XML"/*.xml; do
        tr '\n' ' ' < "$x" | grep -o '<testcase [^>]*>[^<]*<failure[^>]*>[^<]\{0,900\}' |
            sed -e 's/<testcase [^>]*name="\([^"]*\)" classname="\([^"]*\)"[^>]*>/\n\2.\1:/' -e 's/<failure[^>]*>//'
    done > "$OUT/android-test-failures.txt"
    cat "$OUT/android-test-failures.txt"
    result FAIL "instrumented tests failed (android-test-failures.txt, android-test.log)"
fi
cp -r /work/app/build/reports/androidTests "$OUT/androidTests-report" 2>/dev/null

# --- Flow 6: an intranet page fails → Diagnostics explains ------------------------------------

say "Install with the test-env profile, connect"
install_app
app wallpaper value false; sleep 1   # the brand scheme, as in the handoff
home; sleep 3                        # seeds the test-env profile as the default
mark connect; app connect profile "$P"
state_after connect 'On\(' 60 || die "no On with the test-env profile"
debug clear diag-clear >/dev/null

say "Flow 6: wiki over https is refused, api has no host, git resolves outside"
r1=$(fetch ok http://wiki.corp.test/); echo "$r1"
r2=$(fetch refused https://wiki.corp.test/); echo "$r2"
r3=$(fetch unreachable http://api.corp.test/); echo "$r3"
r4=$(fetch outside http://git.corp.test/); echo "$r4"
sleep 2
mark dump; app diag-dump
wait_log dump "sshovel/Debug.*diag-dump end" 30 >/dev/null
since dump | grep "diag-dump" | sed 's/.*diag-dump //' > "$OUT/diagnostics-dump.txt"
cat "$OUT/diagnostics-dump.txt"
d=$OUT/diagnostics-dump.txt
ok6=1
grep -q "^dns tunnel wiki.corp.test. NOERROR 10.77.0.20 outside=false" "$d" || { ok6=0; echo "missing: wiki DNS entry"; }
grep -q "^dns tunnel git.corp.test. NOERROR 203.0.113.40 outside=true" "$d" || { ok6=0; echo "missing: git flagged outside routed subnets"; }
grep -q "^event WARN DNS git.corp.test → 203.0.113.40 is outside routed subnets" "$d" || { ok6=0; echo "missing: the flagged-DNS warning event"; }
refused=$(grep "^failed 10.77.0.20:443 FORWARDING_DENIED" "$d" | head -n1)
[ -n "$refused" ] || { ok6=0; echo "missing: 10.77.0.20:443 refused by server policy"; }
echo "$refused" | grep -q "host=wiki.corp.test" || { ok6=0; echo "the refused connection isn't labelled with wiki.corp.test"; }
grep -Eq "^failed 10.77.0.21:80 (DEST_UNREACHABLE|DEST_TIMEOUT)" "$d" || { ok6=0; echo "missing: 10.77.0.21:80 unreachable or timed out"; }
grep -q "^event WARN Tunnel server refused to forward to 10.77.0.20:443" "$d" || { ok6=0; echo "missing: the Tunnel warning event"; }
app_label=$(echo "$refused" | sed -n 's/.* app=\([^ ]*\) .*/\1/p')
[ "$ok6" = 1 ] && result PASS "flow 6: Diagnostics has the DNS entries (git.corp.test flagged outside routed subnets) and the failures with reasons: 10.77.0.20:443 refused by server policy (app=$app_label, host wiki.corp.test), 10.77.0.21:80 unreachable (diagnostics-dump.txt)" ||
    result FAIL "flow 6: Diagnostics is missing entries (diagnostics-dump.txt)"
[ "$app_label" != "null" ] && [ -n "$app_label" ] && result PASS "connections name their app ($app_label) via getConnectionOwnerUid" ||
    result FAIL "the refused connection has no app (getConnectionOwnerUid)"

say "Warning card → View diagnostics → Connections"
open home "" 3; shot g0-home-warning
if shows "Forwarding not allowed" 5 && tap_text "View diagnostics" && sleep 3 && shows "Refused by server policy" 10; then
    shot g3-connections-from-warning
    result PASS "On with FORWARDING_DENIED: Home's warning card → View diagnostics → Connections shows \"Refused by server policy\""
else
    shot g3-connections-from-warning
    result FAIL "warning card → View diagnostics → Connections (g0-home-warning.png, g3-connections-from-warning.png)"
fi

say "Share as text file"
open diagnostics 0 3
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
if adb exec-out cat /sdcard/ui.xml | grep -q 'content-desc="Share as text file"'; then
    b=$(adb exec-out cat /sdcard/ui.xml | tr '>' '\n' | grep -F 'content-desc="Share as text file"' | head -n1 |
        sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')
    set -- $b; adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); sleep 3
    shot g-share-sheet
    f=$(adb shell run-as "$PKG" ls cache/diagnostics 2>/dev/null | tr -d '\r' | head -n1)
    lines=$(adb shell run-as "$PKG" cat "cache/diagnostics/$f" 2>/dev/null | grep -c . || true)
    adb shell input keyevent KEYCODE_BACK; sleep 1
    echo "$f" | grep -Eq '^sshovel-diagnostics-[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{4}\.txt$' && [ "${lines:-0}" -gt 5 ] &&
        result PASS "Share as text file: the share sheet opens with $f ($lines lines)" ||
        result FAIL "Share as text file: no export in cache/diagnostics ($f, $lines lines; g-share-sheet.png)"
else
    result FAIL "Diagnostics has no Share action (nothing to share?)"
fi

# --- Screenshots ---------------------------------------------------------------------------

for t in light dark; do
    app theme value "$t"; sleep 1
    open diagnostics 0 3; shot "g1-events-$t"
    open diagnostics 1 3; shot "g2-dns-$t"
    open diagnostics 2 3; shot "g3-connections-$t"
    open home "" 2
done
app theme value system; sleep 1

say "200 % font"
adb shell settings put system font_scale 2.0; sleep 2
for s in home diagnostics settings keys about; do open "$s" "" 3; shot "fs200-$s"; done
open profile "$P" 3; shot fs200-profile
open home "" 2
adb shell settings put system font_scale 1.0; sleep 2
result INFO "200 % font screenshots: fs200-*.png (FontScaleTest checks every preview for cut-off text)"

# --- Predictive back -----------------------------------------------------------------------

# back_gesture: a swipe in from the left edge, held halfway for a screenshot of the
# predictive animation ($1-mid.png), then released.
W=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -n1 | cut -dx -f1)
H=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -n1 | cut -dx -f2)
back_gesture() {
    local y=$(( H / 2 ))
    adb shell input motionevent DOWN 1 "$y"
    for x in $(( W / 10 )) $(( W / 5 )) $(( W * 3 / 10 )); do adb shell input motionevent MOVE "$x" "$y"; done
    shot "$1-mid"
    adb shell input motionevent UP $(( W * 3 / 10 )) "$y"
    sleep 2
}
say "Predictive back"
pb_ok=1; pb_list=""
for s in diagnostics settings keys about license licenses; do
    open home "" 2
    open "$s" "" 3
    back_gesture "pb-$s"
    if shows "Profiles" 5; then pb_list="$pb_list $s"; else pb_ok=0; shot "pb-$s-after"; echo "back gesture didn't leave $s"; fi
done
open home "" 2; open profile "$P" 3; back_gesture pb-profile
shows "Profiles" 5 && pb_list="$pb_list profile" || { pb_ok=0; shot pb-profile-after; echo "back gesture didn't leave the profile editor"; }
# A sheet: the gesture closes the create-key sheet and stays on Keys.
open home "" 2; open keys create 3; shot pb-sheet-open
back_gesture pb-sheet
if shows 'content-desc="Import key"' 3 && ! shows "Key name" 2; then pb_list="$pb_list create-key-sheet"; else pb_ok=0; shot pb-sheet-after; echo "back gesture didn't close the create-key sheet"; fi
[ "$pb_ok" = 1 ] && result PASS "predictive back: the gesture leaves every sub-screen and closes a sheet ($pb_list; *-mid.png show the animation)" ||
    result FAIL "predictive back (passed:$pb_list; see pb-*-after.png)"

open home "" 2
mark disconnect; app disconnect; state_after disconnect 'Off' 30
open diagnostics 0 3; shot g4-after-session   # events from the last session are kept
debug clear2 diag-clear >/dev/null
open diagnostics 0 3; shot g4-off-empty

adb shell dumpsys package "$PKG" | grep -m1 versionName | tr -d ' ' > "$OUT/version.txt"
adb shell getprop ro.build.fingerprint > "$OUT/device.txt"
write_summary M7
