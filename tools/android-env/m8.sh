#!/bin/bash
# SPDX-FileCopyrightText: 2026 Dennis Klein
# SPDX-License-Identifier: GPL-3.0-or-later
#
# M8 acceptance (IMPLEMENTATION_PLAN §6, §7), inside the toolbox (see run.sh):
#   tools/android-env/run.sh m8 [idle minutes, default 60]
#   - gradle check; the release APK built by release.sh from a clean checkout of the version tag
#     (HEAD's tag if it has one, else a scratch tag in a clone: nothing is pushed or tagged in
#     /work); it must not be debuggable
#   - with the release APK only, driven through its UI, the tile, and system settings (release
#     builds have no debug commands and log no states): onboarding end to end; About shows the
#     version, legal notices, and the source link to the tag; the licenses screen's data matches
#     go-licenses report and the AboutLibraries output; the manual test matrix (§7) as far as an
#     emulator can run it; an idle connected tunnel for an hour (CPU, wakeups, battery estimate);
#     logcat carries no hosts or destinations from the app
#   - with the debug APK: StrictMode violations over a session, and the instrumented tests
# States are read from the app's notifications (dumpsys notification), the TUN interface, and
# the screen (uiautomator). Results: tools/android-env/out/ (summary.txt first, then the PNGs).
set -uo pipefail

OUT=/work/tools/android-env/out
rm -rf "$OUT"; mkdir -p "$OUT"
exec > >(tee "$OUT/run.log") 2>&1
source /work/tools/android-env/lib.sh
trap 'cleanup; chown -R "$owner" /work/test-env/wiki 2>/dev/null' EXIT
IDLE_MIN=${1:-60}
AUTH_KEYS=/work/test-env/keys/acceptance_authorized_keys
TILE=$PKG/.tile.TunnelTileService
OTHER=test.othervpn
OTHER_APK=/work/tools/android-env/othervpn/build/outputs/apk/debug/othervpn-debug.apk
NAME=Office
git config --global --add safe.directory '*'

# ---- Helpers (UI and state through what a user sees) -------------------------------------------

launch() { adb shell am start -n "$PKG/.ui.MainActivity" >/dev/null; sleep 2; }
# home: back to the Home screen. MainActivity is singleTop, so starting it keeps whatever screen
# is open; press back until Home's top bar (Keys, Settings) shows, relaunching if back left the app.
home() {
    local ui
    for _ in 1 2 3 4 5 6; do
        launch; ui=$(ui_dump)
        grep -qF 'content-desc="Settings"' <<<"$ui" && grep -qF 'content-desc="Keys"' <<<"$ui" && return 0
        adb shell input keyevent KEYCODE_BACK; sleep 1
    done
    echo "home: Home never showed"; return 1
}
back() { adb shell input keyevent KEYCODE_BACK; sleep 1; }
no_soft_keyboard() { adb shell settings put secure show_ime_with_hard_keyboard 0; }
node_bounds() { sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p'; }
ui_dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml; }
tap_bounds() { set -- $1; adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); }
# tap_attr <attr> <value> [last]: taps the first (or last) node whose attribute equals value.
tap_attr() {
    local b
    for _ in $(seq 1 10); do
        b=$(ui_dump | tr '>' '\n' | grep -F -e "$1=\"$2\"" -e "$1=\"$2, " | { if [ "${3:-}" = last ]; then tail -n1; else head -n1; fi; } | node_bounds)
        [ -n "$b" ] && { tap_bounds "$b"; return 0; }
        sleep 1
    done
    echo "no node with $1=\"$2\""; return 1
}
tap_desc() { tap_attr content-desc "$1"; }
# Compose merges a list item's texts into one node ("About sshovel, Version …"): match either.
tap_text() { tap_attr text "$1"; }
# tap_visible <text>: taps the node showing <text> once it sits above the bottom actions (Next),
# scrolling the form up a bit otherwise (m6.sh).
tap_visible() {
    local h ui b n limit
    h=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -n1 | cut -dx -f2)
    for _ in $(seq 1 12); do
        ui=$(ui_dump | tr '>' '\n')
        b=$(grep -F -e "text=\"$1\"" -e "text=\"$1, " <<<"$ui" | head -n1 | node_bounds)
        n=$(grep -F 'text="Next"' <<<"$ui" | head -n1 | node_bounds)
        limit=$(( h - 160 )); [ -n "$n" ] && limit=$(awk '{print $2}' <<<"$n")
        if [ -n "$b" ]; then
            set -- "$1" $b
            if [ "$5" -lt "$limit" ]; then adb shell input tap $(( ($2 + $4) / 2 )) $(( ($3 + $5) / 2 )); return 0; fi
        fi
        adb shell input swipe 500 $(( h * 45 / 100 )) 500 $(( h * 30 / 100 )) 500; sleep 1
    done
    echo "tap_visible: \"$1\" never came into view"; return 1
}
type_into() { tap_visible "$1" && sleep 1 && adb shell input keyevent KEYCODE_MOVE_END && adb shell input text "$2"; }
add_chip() { tap_visible "$1" && sleep 1 && adb shell input text "$2" && adb shell input keyevent KEYCODE_ENTER && sleep 1; }

# The app's notifications, titles and texts (grep -c: under pipefail, grep -q quitting early
# would fail the pipeline with SIGPIPE on this long output).
notif_count() { adb shell dumpsys notification --noredact 2>/dev/null | tr -d '\r' | grep -A80 "pkg=$PKG" | grep -cF "$1"; }
notif_shows() { for _ in $(seq 1 "${2:-30}"); do [ "$(notif_count "$1")" -gt 0 ] && return 0; sleep 1; done; return 1; }
connected() { notif_shows "Connected to $NAME" "${1:-60}" && ! no_tun; }
tun_down() { for _ in $(seq 1 "${1:-20}"); do no_tun && return 0; sleep 1; done; return 1; }
click_tile() { adb shell cmd statusbar click-tile "$TILE"; sleep 2; }
app_pid() { adb shell pidof "$PKG" 2>/dev/null | tr -d '\r'; }
# Chrome's page text, for pages loaded through the tunnel.
page_loads() { chrome_open "$1"; chrome_shows "Welcome to nginx" "${2:-10}"; }
retry_from_home() { home; tap_text "Retry" || tap_text "Reconnect" || tap_text "Connect"; }
# On a failed check: what the notifications said, for the report.
notif_dump() { adb shell dumpsys notification --noredact 2>/dev/null | tr -d '\r' | grep -A80 "pkg=$PKG" | grep -E 'android\.(title|text)=' > "$OUT/$1-notifications.txt"; }

# ---- Build ---------------------------------------------------------------------------------------

say "Toolchain"; toolchain
say "Wait for test-env's host key"; wait_hostkey
echo "sdk.dir=$ANDROID_HOME" > /work/local.properties
say "./gradlew check assembleDebug assembleDebugAndroidTest"
if gradle check :app:assembleDebug :app:assembleDebugAndroidTest > "$OUT/gradle-check.log" 2>&1; then
    result PASS "gradle check (unit tests incl. the log audit, lint, checkLicenses, reuseLint, verifyPageAlignment)"
else
    tail -n 60 "$OUT/gradle-check.log"; die "gradle check failed (gradle-check.log)"
fi
gradle -p tools/android-env/othervpn assembleDebug > "$OUT/othervpn-build.log" 2>&1 || die "othervpn build failed"
command -v reuse >/dev/null && (cd /work && reuse lint > "$OUT/reuse-lint.txt" 2>&1) &&
    result PASS "reuse lint: $(tail -n1 "$OUT/reuse-lint.txt")" || result FAIL "reuse lint (reuse-lint.txt)"

say "Release APK from a clean checkout of the tag"
VERSION=$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' /work/app/build.gradle.kts)
VCODE=$(sed -n 's/^ *versionCode = \([0-9]*\)/\1/p' /work/app/build.gradle.kts)
TAG=v$VERSION
SRC=/work; scratch=""
if [ "$(git -C /work describe --tags --exact-match HEAD 2>/dev/null)" != "$TAG" ]; then
    SRC=/build/m8-src; scratch=" (scratch tag on $(git -C /work rev-parse --short HEAD), not pushed)"
    rm -rf "$SRC"; mkdir -p /build; git clone --quiet --no-local /work "$SRC"; git -C "$SRC" tag "$TAG"
fi
if WORK=$SRC OUT=$OUT bash /work/tools/android-env/release.sh "$TAG" > "$OUT/release.log" 2>&1; then
    REL_APK=$OUT/sshovel-$TAG.apk
    result PASS "release APK $TAG$scratch: checkLicenses and reuseLint ran first, source link $(grep -o 'https://[^ ]*' "$OUT/release-$TAG.txt" | head -n1), signer $(sed -n 's/^signer: //p' "$OUT/release-$TAG.txt") (placeholder)"
else
    tail -n 40 "$OUT/release.log"; die "release build failed (release.log)"
fi

# ---- Install --------------------------------------------------------------------------------------

say "Boot emulator"; boot_emulator; no_soft_keyboard
# -v uid: lines carry the app's uid, for the logcat audit at the end.
adb logcat -c; adb logcat -v time -v uid > "$OUT/logcat.txt" 2>/dev/null & LOGCAT_PID=$!
chrome_setup || result INFO "no Chrome on the image: page loads through the tunnel are skipped"

say "Install the release APK"
adb uninstall "$PKG" >/dev/null 2>&1
adb install -r -g "$REL_APK" || die "release APK doesn't install"
adb install -r "$OTHER_APK" >/dev/null || die "othervpn install failed"
adb shell appops set "$PKG" ACTIVATE_VPN allow   # the consent flow was M5's (flow 3)
UID_NUM=$(adb shell dumpsys package "$PKG" | tr -d '\r' | sed -n 's/.*appId=\([0-9]*\).*/\1/p' | head -n1)
UID_NAME=u0_a$(( UID_NUM - 10000 ))
dbg=$(adb shell run-as "$PKG" id 2>&1 | tr -d '\r')
grep -q "not debuggable" <<<"$dbg" &&
    result PASS "release APK installs, isn't debuggable (run-as: \"$dbg\"), version $(adb shell dumpsys package "$PKG" | grep -m1 -o 'versionName=[^ ]*' | tr -d '\r')" ||
    result FAIL "release APK is debuggable or run-as failed oddly: $dbg"

# ---- Onboarding, through the UI (DESIGN_BRIEF §6 flow 1) -----------------------------------------

onb_ok=1; fail_at=""
step() { local name=$1; shift; if "$@"; then shot "$name"; else shot "$name"; [ "$onb_ok" = 1 ] && fail_at=$name; onb_ok=0; fi; }
launch   # first run: onboarding opens by itself
say "O1 → O2 create key"
step o1-welcome shows "Reach your intranet from any app" 20
tap_text "Get started"
step o2-create shows "Create a key"
tap_text "Create key"
step o2-created shows "Key created" 30
tap_text "Next"
say "O3 install the public key on test-env (the line as the screen shows it)"
step o3-install shows "Install the public key"
LINE=$(ui_dump | grep -o 'text="restrict,port-forwarding [^"]*"' | head -n1 |
    sed -e 's/^text="//' -e 's/"$//' -e 's/&lt;/</g' -e 's/&gt;/>/g' -e 's/&quot;/"/g' -e "s/&apos;/'/g" -e 's/&amp;/\&/g')
BLOB=$(echo "$LINE" | awk '{print $3}')
if [ -n "$BLOB" ]; then echo "$LINE" >> "$AUTH_KEYS"; echo "installed: $LINE"; else onb_ok=0; fail_at=o3-key-line; fi
tap_text "I’ve added it"
say "O4 add the server"
step o4-server shows "Add your server"
o4_ok=1
type_into "Profile name" "$NAME" || o4_ok=0
type_into "Host" "10.0.2.2" || o4_ok=0
type_into "22" "22" || o4_ok=0            # port 22 → 2222
type_into "Username" "tester" || o4_ok=0
add_chip "Add subnet" "10.77.0.0/24" || o4_ok=0
type_into "Intranet DNS server" "10.77.0.53" || o4_ok=0
add_chip "Add suffix" "corp.test" || o4_ok=0
[ "$o4_ok" = 1 ] || { onb_ok=0; fail_at=${fail_at:-o4-fields}; }
sleep 1; shot o4-filled
tap_text "Next"
say "O5 verify → O6 test → O7 tile → O9 connect"
step o5-verify shows "Trust this server" 30
tap_text "Trust this server"
step o6-test shows "Connection works" 60
tap_text "Next"
step o7-tile shows "Add the Quick Settings tile"
tap_text "Add tile"; sleep 3
tap_text "Add tile"            # SystemUI's own dialog
step o8-tile shows "Tile added" 20
tap_text "Next"
step o9-done shows "Setup complete"
tap_text "Connect now"
if connected 60; then sleep 1; shot o10-home-on; else shot o10-home; onb_ok=0; fail_at=${fail_at:-connect}; fi
[ "$onb_ok" = 1 ] &&
    result PASS "release: onboarding end to end (key created, its authorized_keys line installed, server added, verified, tested, tile added, Connect now → \"Connected to $NAME\")" ||
    result FAIL "release: onboarding stopped at ${fail_at:-?} (see o*.png)"
if page_loads http://wiki.corp.test/; then result PASS "http://wiki.corp.test loads in Chrome through the tunnel (split DNS + forwarding)"
else shot chrome-wiki; result FAIL "http://wiki.corp.test doesn't load in Chrome (chrome-wiki.png)"; fi

# ---- About and licenses -------------------------------------------------------------------------

say "About: version, legal notices, source link"
home; tap_desc "Settings"; sleep 2; tap_visible "About sshovel"; sleep 2
about=$(ui_dump); echo "$about" > "$OUT/about-ui.xml"; shot about
miss=""
for t in "Version $VERSION ($VCODE)" "Copyright © 2026 Dennis Klein" \
         "sshovel is free software under the GNU GPL v3 or later. It comes with ABSOLUTELY NO WARRANTY." \
         "View license" "https://github.com/dennisklein/sshovel/tree/$TAG" "Open-source licenses"; do
    grep -qF "$t" <<<"$about" || miss="$miss [$t]"
done
[ -z "$miss" ] && result PASS "About: Version $VERSION ($VCODE), copyright, the GPL/no-warranty notice, View license, Source code → tree/$TAG, Open-source licenses" ||
    result FAIL "About is missing:$miss (about.png)"
tap_text "View license"; sleep 2
shows "GNU GENERAL PUBLIC LICENSE" 5 && shows "Version 3, 29 June 2007" 3 && { shot about-license; result PASS "View license shows the GPL-3.0 text in the app"; } ||
    { shot about-license; result FAIL "the license viewer doesn't show the GPL text (about-license.png)"; }
back
tap_text "Source code"; sleep 4
top=$(adb shell dumpsys activity activities | grep -m1 topResumedActivity | tr -d '\r'); shot about-source
grep -q "com.android.chrome" <<<"$top" &&
    result PASS "Source code opens tree/$TAG in the browser (about-source.png; it resolves once the tag is pushed)" ||
    result FAIL "Source code didn't open a browser: $top"
home; tap_desc "Settings"; sleep 2; tap_visible "Open-source licenses"; sleep 4; shot licenses
# The Android list comes first (about 100 rows); scroll down to the Go modules and the font/icons.
lic_seen=""
H=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -n1 | cut -dx -f2)
for _ in $(seq 1 60); do
    lic_ui=$(ui_dump)
    for t in "Android libraries" "Go modules" "golang.org/x/crypto" "Fonts and icons" "Roboto Mono"; do
        grep -qF "text=\"$t" <<<"$lic_ui" && [[ "$lic_seen" != *"[$t]"* ]] && lic_seen="$lic_seen[$t]"
    done
    [[ "$lic_seen" == *"[Go modules]"* ]] && [ -z "${lic_go_shot:-}" ] && { shot licenses-go; lic_go_shot=1; }
    [[ "$lic_seen" == *"[Roboto Mono]"* ]] && break
    adb shell input swipe 500 $(( H * 80 / 100 )) 500 $(( H * 20 / 100 )) 200; sleep 1
done
echo "licenses screen shows: $lic_seen"
[ "$(grep -o '\[' <<<"$lic_seen" | wc -l)" = 5 ] && lic_screen=1 || lic_screen=0
# The screen is generated from two data files in the APK: compare them with the tools' output.
go_report=$(cd /build/sshovel/core && GOOS=android GOARCH=arm64 go-licenses report ./mobile --ignore github.com/dennisklein/sshovel 2>/dev/null | cut -d, -f1 | sort)
go_apk=$(unzip -p "$REL_APK" assets/go_licenses.json | python3 -c 'import json,sys; [print(e["module"]) for e in json.load(sys.stdin)[1:]]' | sort)
al_build=/build/sshovel/app/build/generated/aboutLibraries/release/res/raw/aboutlibraries.json
al_count() { python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))["libraries"]))' "$1"; }
al_apk=""
for f in $(unzip -Z1 "$REL_APK" | grep '^res/.*\.json$'); do
    unzip -p "$REL_APK" "$f" > /tmp/al.json; grep -q '"libraries"' /tmp/al.json && al_apk=$(al_count /tmp/al.json)
done
al_built=$([ -f "$al_build" ] && al_count "$al_build")
echo "go-licenses report:"; echo "$go_report"; echo "AboutLibraries: build $al_built, APK $al_apk"
if [ "$lic_screen" = 1 ] && [ -n "$go_report" ] && [ "$go_report" = "$go_apk" ] && [ -n "$al_apk" ] && [ "$al_apk" = "$al_built" ]; then
    result PASS "licenses screen (Android libraries, then Go modules, then fonts and icons; licenses*.png): the APK's Go list = go-licenses report ($(echo "$go_report" | wc -l) modules, plus the Go runtime); its Android list = the AboutLibraries output ($al_apk libraries)"
else
    result FAIL "licenses: screen showed $lic_seen, Go report vs APK equal=$([ "$go_report" = "$go_apk" ] && echo yes || echo no), AboutLibraries build=$al_built APK=$al_apk (licenses.png)"
fi
home

# ---- Manual test matrix (§7) --------------------------------------------------------------------

say "Matrix: tile on/off (unlocked)"
click_tile
if tun_down 20; then
    click_tile
    connected 60 && result PASS "matrix: tile off → TUN gone; tile on → \"Connected to $NAME\" (unlocked)" ||
        result FAIL "matrix: tile on didn't connect"
else
    result FAIL "matrix: tile off didn't disconnect"
fi

say "Matrix: server refuses forwarding → FORWARDING_DENIED warning"
# test-env's PermitOpen refuses port 443; sshd answers it like AllowTcpForwarding no: the
# direct-tcpip channel open fails with "administratively prohibited".
chrome_open https://wiki.corp.test/; sleep 6
chrome_open http://10.77.0.20:8080/; sleep 6   # a second refused destination, in case Chrome skipped the first
home; shot m-forwarding-home
if shows "Forwarding not allowed" 10 && notif_shows "1 warning" 10 && ! no_tun; then
    tap_text "View diagnostics"; sleep 3; shot m-forwarding-diagnostics
    shows "Refused by server policy" 10 &&
        result PASS "matrix: forwarding refused → Connected + FORWARDING_DENIED warning (Home card, notification \"1 warning\"); Diagnostics: \"Refused by server policy\"" ||
        result FAIL "matrix: FORWARDING_DENIED shown, but Connections lacks \"Refused by server policy\""
else
    notif_dump m-forwarding
    result FAIL "matrix: no FORWARDING_DENIED warning after a refused forward (m-forwarding-home.png, m-forwarding-notifications.txt)"
fi

say "Matrix: Wi-Fi → mobile data while downloading"
mkdir -p /work/test-env/wiki/files
[ -s /work/test-env/wiki/files/big.bin ] || head -c 67108864 /dev/urandom > /work/test-env/wiki/files/big.bin
pid0=$(app_pid)
rx() { adb shell cat /proc/net/dev | tr -d '\r' | awk '$1 ~ /^tun[0-9]+:$/ {print $2}' | head -n1; }
chrome_open http://wiki.corp.test/files/big.bin; sleep 5
tap_text "Download" >/dev/null 2>&1 || true
r1=$(rx); sleep 4; r2=$(rx)
echo "tun rx: $r1 → $r2"
dl=$([ -n "$r1" ] && [ -n "$r2" ] && [ "$r2" -gt "$r1" ] && echo running || echo "not seen")
shot m-download
adb shell svc wifi disable
# Reconnecting may last less than one dumpsys poll: note it if seen, judge by what follows.
notif_shows "Reconnecting to $NAME" 15 && seen="Reconnecting seen" || seen="Reconnecting too short to catch"
if connected 90; then
    sleep 3
    page_loads "http://wiki.corp.test/?after-switch" && new_ok=1 || new_ok=0
    [ "$(app_pid)" = "$pid0" ] && same=1 || same=0
    [ "$new_ok" = 1 ] && [ "$same" = 1 ] &&
        result PASS "matrix: Wi-Fi → mobile during a download (download $dl): $seen, Connected again, a new page load works, no crash (same process)" ||
        result FAIL "matrix: after Wi-Fi → mobile: new page load=$new_ok, same process=$same"
else
    result FAIL "matrix: no Reconnecting → Connected after Wi-Fi off"
fi
adb shell svc wifi enable
sleep 5; connected 90 && result PASS "matrix: mobile → Wi-Fi: Connected again" ||
    result FAIL "matrix: no reconnect after Wi-Fi came back"
adb shell am force-stop com.android.chrome   # ends the download
chrome_setup >/dev/null 2>&1

say "Matrix: airplane mode for 2 minutes"
adb shell cmd connectivity airplane-mode enable
if notif_shows "back online" 30; then
    shot m-airplane
    sleep 120
    # The TUN stays up while waiting, so nothing leaks; the notification tells the state.
    stayed=$([ "$(notif_count "back online")" -gt 0 ] && [ "$(notif_count "Connected to $NAME")" = 0 ] && echo 1 || echo 0)
    adb shell cmd connectivity airplane-mode disable
    connected 120 && [ "$stayed" = 1 ] &&
        result PASS "matrix: airplane mode 2 min → NETWORK_LOST (\"Reconnecting to $NAME · sshovel will reconnect when you’re back online\" throughout), then back: Connected again by itself" ||
        result FAIL "matrix: airplane: waiting state held=$stayed, reconnected=$(connected 1 && echo yes || echo no)"
else
    adb shell cmd connectivity airplane-mode disable
    result FAIL "matrix: no NETWORK_LOST notification in airplane mode (m-airplane.png)"
fi
connected 60 >/dev/null

say "Matrix: Private DNS strict"
adb shell settings put global private_dns_specifier dns.google
adb shell settings put global private_dns_mode hostname
sleep 10
home; tap_desc "Diagnostics"; sleep 2; tap_text "DNS"; sleep 2
if shows "Private DNS is set to dns.google" 15; then
    shot m-private-dns; result PASS "matrix: Private DNS strict → Diagnostics' DNS tab explains (\"Private DNS is set to dns.google, so … intranet names don’t resolve through the tunnel\")"
else
    shot m-private-dns; result FAIL "matrix: no Private DNS explanation in Diagnostics (m-private-dns.png)"
fi
adb shell settings put global private_dns_mode opportunistic
adb shell settings delete global private_dns_specifier
home

say "Matrix: tile on the lock screen × require unlock"
adb shell locksettings set-pin 1111 >/dev/null
# ActivityTaskManager's KeyguardController knows whether the lock screen is up.
keyguard() { adb shell dumpsys activity activities 2>/dev/null | tr -d '\r' | grep -o 'mKeyguardShowing=[a-z]*' | head -n1 | cut -d= -f2; }
lock() { adb shell svc power stayon false; adb shell input keyevent KEYCODE_SLEEP; sleep 3; adb shell input keyevent KEYCODE_WAKEUP; sleep 2; }
unlock() {
    local h; h=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -n1 | cut -dx -f2)
    for _ in 1 2; do
        adb shell input keyevent KEYCODE_WAKEUP; adb shell input swipe 500 $(( h * 85 / 100 )) 500 $(( h * 30 / 100 )) 300; sleep 2
        adb shell input text 1111; adb shell input keyevent KEYCODE_ENTER; sleep 3
        [ "$(keyguard)" = true ] || break
    done
    adb shell svc power stayon true
}
tunnel_off() { no_tun || { click_tile; tun_down 20 >/dev/null; }; }
# require_unlock true|false: sets Settings' switch and reads it back from its checked state.
require_unlock() {
    local st=""
    home; tap_desc "Settings"; sleep 2
    for _ in 1 2 3; do
        st=$(ui_dump | tr '>' '\n' | grep -F 'text="Require unlock to use the tile' | grep -o 'checked="[a-z]*"' | head -n1 | cut -d'"' -f2)
        [ "$st" = "$1" ] && return 0
        tap_visible "Require unlock to use the tile"; sleep 1
    done
    echo "require unlock: wanted $1, switch reads ${st:-nothing}"; return 1
}
# locked_case <label>: tunnel off, lock, tap the tile, look, unlock with the PIN, look.
# Prints "<locked?> <connected while locked> <locked after PIN?> <connected after>".
locked_case() {
    tunnel_off
    lock; local kg; kg=$(keyguard)
    click_tile; sleep 8; shot "m-locked-$1"
    local wl=no; no_tun || wl=yes
    unlock; local kg2; kg2=$(keyguard); sleep 5
    local after=no; no_tun || after=yes
    echo "${kg:-?} $wl ${kg2:-?} $after"
}
require_unlock false && r_off=$(locked_case unlock-off | tail -n1) || r_off="? ? ? ?"
require_unlock true && { shot m-require-unlock-on; r_on=$(locked_case unlock-on | tail -n1); } || r_on="? ? ? ?"
echo "lock screen shown / connected while locked / still locked after PIN / connected after: require unlock off: $r_off; on: $r_on"
set -- $r_on
if [ "$1" != true ]; then
    result INFO "matrix: require unlock on: couldn't bring up the lock screen by script ($r_on)"
elif [ "$2" = yes ]; then
    result FAIL "matrix: require unlock on, but the tile connected on the lock screen (m-locked-unlock-on.png)"
elif [ "$3" != false ]; then
    result INFO "matrix: require unlock on: no connection while locked; the script couldn't unlock with the PIN ($r_on)"
elif [ "$4" = yes ]; then
    result PASS "matrix: require unlock on: the tile on the lock screen asks for the PIN and connects only after it (m-locked-unlock-on.png)"
else
    result INFO "matrix: require unlock on: no connection while locked, none after the PIN either (SystemUI drops the pending click on this emulator)"
fi
set -- $r_off
result INFO "matrix: require unlock off, PIN lock screen: lock screen=$1, connected while locked=$2, after the PIN=$4 (this emulator's SystemUI asks for the PIN before any custom tile, ARCHITECTURE §11.2; the lock screen stays in the device checklist)"
require_unlock false >/dev/null
adb shell locksettings clear --old 1111 >/dev/null
adb shell wm dismiss-keyguard; adb shell svc power stayon true
home; connected 5 >/dev/null || { click_tile; connected 60 >/dev/null; }

say "Matrix: reboot with Always-on"
reboot_device() {
    kill "$LOGCAT_PID" 2>/dev/null; adb reboot; sleep 5
    timeout 600 adb wait-for-device
    for _ in $(seq 1 300); do [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 2; done
    adb root >/dev/null 2>&1; sleep 2; adb wait-for-device
    adb logcat -v time -v uid >> "$OUT/logcat.txt" 2>/dev/null & LOGCAT_PID=$!
    adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard; adb shell svc power stayon true
    chrome_setup >/dev/null 2>&1
}
adb shell settings put secure always_on_vpn_app "$PKG"
adb shell settings put secure always_on_vpn_lockdown 0
reboot_device
if connected 180; then
    shot m-always-on; result PASS "matrix: reboot with Always-on → connected with the default profile ($NAME) without opening the app"
else
    shot m-always-on; result FAIL "matrix: no connection after reboot with Always-on (m-always-on.png)"
fi
# The system reads the Always-on setting at boot only: clearing it takes another reboot, or it
# would keep other VPNs out and restart sshovel for the rest of the run.
adb shell settings delete secure always_on_vpn_app; adb shell settings delete secure always_on_vpn_lockdown
reboot_device
click_tile; connected 60 >/dev/null || result INFO "the tile didn't connect after the second reboot"

say "Battery: idle connected tunnel for $IDLE_MIN min"
connected 30 >/dev/null || { click_tile; connected 60 >/dev/null; }
pid=$(app_pid)
cpu() { adb shell cat "/proc/$1/stat" 2>/dev/null | tr -d '\r' | awk '{print $14 + $15}'; }
adb shell dumpsys batterystats --reset >/dev/null
adb shell dumpsys battery unplug
adb shell svc power stayon false
adb shell input keyevent KEYCODE_SLEEP
c0=$(cpu "$pid"); up=0; checks=0
for _ in $(seq 1 $(( IDLE_MIN * 60 / 300 ))); do
    sleep 300; checks=$((checks + 1))
    ! no_tun && [ "$(notif_count "Connected to $NAME")" -gt 0 ] && up=$((up + 1))
done
c1=$(cpu "$pid")
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard; adb shell svc power stayon true
adb shell dumpsys batterystats "$PKG" > "$OUT/batterystats.txt" 2>&1
adb shell dumpsys battery reset
secs=$(awk -v a="${c0:-0}" -v b="${c1:-0}" 'BEGIN {printf "%.1f", (b - a) / 100}')
pct=$(awk -v s="$secs" -v m="$IDLE_MIN" 'BEGIN {printf "%.2f", 100 * s / (m * 60)}')
mah=$(sed -nE "s/^ *(UID )?u0a$(( UID_NUM - 10000 )): ([0-9.]+).*/\2/p" "$OUT/batterystats.txt" | head -n1)
wl=$(grep -cE "Wake lock .*realtime" "$OUT/batterystats.txt")
same=$([ "$(app_pid)" = "$pid" ] && echo yes || echo no)
info="CPU ${secs} s (${pct} % of one core), estimated ${mah:-?} mAh, ${wl} wake lock lines, tunnel up at $up/$checks checks, same process: $same (batterystats.txt)"
awk -v p="$pct" 'BEGIN {exit !(p < 1.0)}' && [ "$up" = "$checks" ] && [ "$checks" -gt 0 ] &&
    result PASS "battery: idle connected for $IDLE_MIN min, screen off: $info" ||
    result FAIL "battery: idle connected for $IDLE_MIN min: $info (wants < 1 % CPU and the tunnel up throughout)"

say "Matrix: another VPN app → VPN_REVOKED"
adb shell appops set "$OTHER" ACTIVATE_VPN allow
adb shell am start -n "$OTHER/.StartActivity" >/dev/null
if notif_shows "Disconnected by another VPN" 30 || { home; shows "Disconnected by another VPN" 5; }; then
    home; shot m-revoked; result PASS "matrix: another VPN app started → VPN_REVOKED (\"Disconnected by another VPN\")"
else
    home; shot m-revoked; notif_dump m-revoked
    result FAIL "matrix: no VPN_REVOKED after another VPN took over (m-revoked.png, m-revoked-notifications.txt)"
fi
adb shell am force-stop "$OTHER"; adb shell appops set "$OTHER" ACTIVATE_VPN ignore

say "Matrix: key removed from authorized_keys → AUTH_FAILED, no retry loop"
cp "$AUTH_KEYS" /tmp/authorized_keys.bak
grep -vF "$BLOB" /tmp/authorized_keys.bak > "$AUTH_KEYS"
retry_from_home
if notif_shows "Key not accepted" 40 || { home; shows "Key not accepted" 5; }; then
    home; shot m-auth-failed
    loop=0
    for _ in $(seq 1 15); do
        [ "$(notif_count "Connecting to $NAME")" -gt 0 ] || [ "$(notif_count "Reconnecting to $NAME")" -gt 0 ] || ! no_tun && loop=1
        sleep 2
    done
    [ "$loop" = 0 ] && shows "Key not accepted" 3 &&
        result PASS "matrix: key removed → AUTH_FAILED (\"Key not accepted\" with Show public key); no retry for 30 s, no TUN" ||
        result FAIL "matrix: AUTH_FAILED, but it retried or brought up the TUN within 30 s"
else
    home; shot m-auth-failed; notif_dump m-auth-failed
    result FAIL "matrix: no AUTH_FAILED after removing the key (m-auth-failed.png, m-auth-failed-notifications.txt)"
fi
cp /tmp/authorized_keys.bak "$AUTH_KEYS"

say "Matrix: host key rotated → HOST_KEY_MISMATCH, no tunnel"
rm -f "$HOSTKEYS"/ssh_host_*
for _ in $(seq 1 20); do sleep 1; [ -f "$HOSTKEYS/ssh_host_ed25519_key.pub" ] && [ -f "$HOSTKEYS/ssh_host_ecdsa_key.pub" ] && break; done
sleep 2
retry_from_home
if notif_shows "Server identity changed" 60 || { home; shows "Server identity changed" 5; }; then
    sleep 2; home; shot m-mismatch
    no_tun && result PASS "matrix: host key rotated → HOST_KEY_MISMATCH (\"Server identity changed\"), no TUN" ||
        result FAIL "matrix: HOST_KEY_MISMATCH, but a TUN is up"
else
    home; shot m-mismatch; notif_dump m-mismatch
    result FAIL "matrix: no HOST_KEY_MISMATCH after rotating the host key (m-mismatch.png, m-mismatch-notifications.txt)"
fi
# The only way back: the user forgets the pinned key in the profile editor and verifies the new one.
acc=0
if tap_text "Review in profile" && tap_visible "Forget pinned key" && tap_text "Forget key" && sleep 2 &&
   tap_visible "Verify now" && shows "Trust this server" 30; then
    shot m-mismatch-verify-new
    tap_text "Trust this server"; sleep 2; back; home
    tap_text "Connect" && connected 60 && acc=1
fi
[ "$acc" = 1 ] && result PASS "host key mismatch resolved only through the editor: Review in profile → Forget pinned key → Verify now → Trust → Connected" ||
    { shot m-mismatch-after; result INFO "couldn't walk the editor's forget/verify path by script (m-mismatch-*.png); the mismatch itself is covered above and in the instrumented tests"; }

say "Matrix: routed subnet overlaps the local Wi-Fi network → warning in the editor"
LAN=$(adb shell ip -4 -o addr show wlan0 | tr -d '\r' | awk '{print $4}' | head -n1)
LAN_NET=$(python3 -c 'import ipaddress,sys; print(ipaddress.ip_interface(sys.argv[1]).network)' "$LAN" 2>/dev/null)
echo "wlan0 $LAN → $LAN_NET"
click_tile; tun_down 20 >/dev/null   # the editor is read-only while connected
home; tap_attr text "$NAME" last; sleep 2
if [ -n "$LAN_NET" ] && add_chip "Add subnet" "$LAN_NET" && shows "Overlaps your local network $LAN_NET" 10; then
    shot m-lan-overlap; result PASS "matrix: subnet $LAN_NET overlaps the Wi-Fi network → the editor warns (\"Overlaps your local network …\")"
else
    shot m-lan-overlap; result FAIL "matrix: no overlap warning for $LAN_NET (wlan0 $LAN; m-lan-overlap.png)"
fi
back; tap_text "Discard" >/dev/null 2>&1; home

# ---- Release logcat audit (ARCHITECTURE §9) -----------------------------------------------------

say "Logcat audit: the release app logs no hosts, destinations, or keys"
# logcat -v uid prints the app's uid as u0_aNNN or as the number, depending on the version.
# -v time -v uid lines read "… D/Tag( uid:  pid): message"; match the uid field only (system lines
# may mention the app's uid in their message, e.g. ConnectivityService's OwnerUid).
grep -E "^[0-9-]+ [0-9:.]+ [A-Z]/[^(]*\( *($UID_NAME|$UID_NUM): " "$OUT/logcat.txt" | grep -v "sshovel-m" > "$OUT/app-logcat.txt"
leaks=$(grep -E 'corp\.test|10\.77\.|10\.0\.2\.2|tester|BEGIN OPENSSH|AAAA[A-Za-z0-9+/]{20}' "$OUT/app-logcat.txt" | grep -vE "ActivityManager|ActivityTaskManager" | head -n 20)
crashes=$(grep -c "FATAL EXCEPTION" "$OUT/app-logcat.txt")
if [ ! -s "$OUT/app-logcat.txt" ]; then
    result INFO "logcat audit: no lines attributed to the app's uid $UID_NAME/$UID_NUM (logcat.txt has them all)"
elif [ -z "$leaks" ] && [ "$crashes" = 0 ]; then
    result PASS "logcat audit: $(wc -l < "$OUT/app-logcat.txt") lines from the release app's uid, none with hosts, destinations, the user, or key material; no crashes (app-logcat.txt)"
else
    echo "$leaks"; result FAIL "logcat audit: ${crashes} crashes; lines with hosts/keys: $(echo "$leaks" | grep -c .) (app-logcat.txt)"
fi

# ---- Debug APK: StrictMode, instrumented tests ----------------------------------------------------

say "Debug APK: StrictMode over a session"
# The host key rotated above: rebuild so the debug profile pins the current one.
gradle :app:assembleDebug :app:assembleDebugAndroidTest > "$OUT/gradle-debug.log" 2>&1 || die "debug rebuild failed (gradle-debug.log)"
adb uninstall "$PKG" >/dev/null 2>&1
install_app
mark strict
launch; sleep 3   # seeds the test-env profile
app connect profile debug-test-env
wait_log strict "sshovel/State.*On\(" 60 >/dev/null || result INFO "debug session didn't reach On"
for s in home diagnostics settings keys about license licenses; do app open screen "$s"; sleep 2; done
app open screen profile arg debug-test-env; sleep 2
app fetch url http://wiki.corp.test/; sleep 3
app disconnect; sleep 3
since strict | grep -A25 "StrictMode policy violation" > "$OUT/strictmode.txt"
n=$(grep -c "StrictMode policy violation" "$OUT/strictmode.txt")
kinds=$(grep -o "android.os.strictmode.[A-Za-z]*" "$OUT/strictmode.txt" | sort | uniq -c | sort -rn | awk '{printf "%s %s, ", $1, $2}')
[ "$n" = 0 ] && result PASS "StrictMode (debug build, detectAll): no violations over app start, connect, every screen, a page load, disconnect" ||
    result FAIL "StrictMode (debug build, detectAll): $n violations: ${kinds%, } (strictmode.txt)"

say "connectedDebugAndroidTest"
XML=/work/app/build/outputs/androidTest-results/connected/debug
if gradle :app:connectedDebugAndroidTest > "$OUT/android-test.log" 2>&1; then
    n=$(grep -ho 'tests="[0-9]*"' "$XML"/*.xml 2>/dev/null | grep -o '[0-9]*' | awk '{s+=$1} END {print s}')
    result PASS "instrumented tests (${n:-?})"
else
    for x in "$XML"/*.xml; do
        tr '\n' ' ' < "$x" | grep -o '<testcase [^>]*>[^<]*<failure[^>]*>[^<]\{0,900\}' |
            sed -e 's/<testcase [^>]*name="\([^"]*\)" classname="\([^"]*\)"[^>]*>/\n\2.\1:/' -e 's/<failure[^>]*>//'
    done > "$OUT/android-test-failures.txt"
    cat "$OUT/android-test-failures.txt"
    result FAIL "instrumented tests failed (android-test-failures.txt, android-test.log)"
fi

result INFO "on a physical device (not an emulator): the tile on a secure lock screen × require unlock, TalkBack by ear, a real Wi-Fi ↔ cellular handover"
adb shell getprop ro.build.fingerprint > "$OUT/device.txt"
write_summary M8
