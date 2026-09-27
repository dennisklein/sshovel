#!/bin/bash
# SPDX-FileCopyrightText: 2026 Dennis Klein
# SPDX-License-Identifier: GPL-3.0-or-later
#
# M4 acceptance (IMPLEMENTATION_PLAN §6), inside the toolbox (see run.sh), with the debug build's
# test-env profile (suffix corp.test, search domain corp.test, route 10.77.0.0/24):
#   1. intranet names resolve through the tunnel, public names through the underlying network,
#      as the DNS diagnostics log shows; a short name resolves via the search domain
#   2. an excluded subnet bypasses the tunnel
#   3. "Only selected apps" limits the tunnel to those apps (and "All except selected" the inverse)
#   4. a validation error blocks saving an overlapping tun subnet
# Results: tools/android-env/out/ (summary.txt first).
set -uo pipefail

OUT=/work/tools/android-env/out
rm -rf "$OUT"; mkdir -p "$OUT"
exec > >(tee "$OUT/run.log") 2>&1
source /work/tools/android-env/lib.sh
trap cleanup EXIT
P=debug-test-env
WIKI=http://wiki.corp.test/
PUBLIC=http://connectivitycheck.gstatic.com/generate_204

connect() {
    mark "connect-$1"; app connect profile "$P"
    wait_log "connect-$1" 'sshovel/State.*(On\(|NeedsAttention)' 60 | grep -q 'On('
}
disconnect() {
    mark "disconnect-$1"; app disconnect
    wait_log "disconnect-$1" 'sshovel/State.*Off' 30 >/dev/null
}
# dns_log <mark>: the DNS diagnostics buffer, "route name qtype rcode answers latency" per line.
dns_log() {
    mark "$1"; app dns-log
    wait_log "$1" 'sshovel/Debug.*: dns-log end' 20 >/dev/null
    since "$1" | grep 'sshovel/Debug.*: dns-log ' | grep -v 'dns-log end' | sed 's/.*: dns-log //'
}
ok() { grep -q ' 200 .*title="Welcome to nginx!"'; }

say "Toolchain"; toolchain
say "Wait for test-env's host key"; wait_hostkey
echo "sdk.dir=$ANDROID_HOME" > /work/local.properties
say "./gradlew check assembleDebug"
if gradle check :app:assembleDebug > "$OUT/gradle-check.log" 2>&1; then
    result PASS "gradle check (unit tests, lint, license tasks, reuseLint, verifyPageAlignment)"
else
    tail -n 60 "$OUT/gradle-check.log"; die "gradle check failed (gradle-check.log)"
fi

say "Boot emulator"; boot_emulator; start_logcat
say "Install"; install_app
adb shell am start -n "$PKG/.ui.MainActivity" >/dev/null; sleep 5
have_chrome=0; chrome_setup && have_chrome=1

# --- Split DNS ---------------------------------------------------------------------------

say "Split DNS"
connect split || die "test-env profile didn't connect"
app dns-clear; sleep 1
r=$(fetch split-wiki "$WIKI"); echo "$r"; echo "$r" | ok || result FAIL "wiki through the tunnel: $r"
r=$(fetch split-public "$PUBLIC"); echo "$r"
dns_log split-log | tee "$OUT/dns-split.txt"
if grep -q '^tunnel wiki\.corp\.test\.\? A NOERROR 10\.77\.0\.20' "$OUT/dns-split.txt" &&
   grep -q '^direct connectivitycheck\.gstatic\.com\.\? A NOERROR' "$OUT/dns-split.txt" &&
   ! grep -q '^tunnel connectivitycheck' "$OUT/dns-split.txt" && ! grep -q '^direct wiki' "$OUT/dns-split.txt"; then
    result PASS "split DNS: wiki.corp.test via the tunnel, connectivitycheck.gstatic.com direct (DNS log: dns-split.txt)"
else
    result FAIL "split DNS log doesn't show tunnel/direct as expected (dns-split.txt)"
fi

say "Search domain"
app dns-clear; sleep 1
r=$(fetch search http://wiki/); echo "$r"
dns_log search-log | tee "$OUT/dns-search.txt"
echo "$r" | ok && grep -q '^tunnel wiki\.corp\.test' "$OUT/dns-search.txt" &&
    result PASS "http://wiki/ loads: the search domain corp.test completes the short name" ||
    result FAIL "short name via search domain: $r"
shot 1-on
disconnect split

# --- Excluded subnet -------------------------------------------------------------------------

say "Excluded subnet"
r=$(debug ex-set profile-exclude profile "$P" cidrs 10.77.0.20/32); echo "$r"
if [ "$r" = "profile-exclude ok" ] && connect excluded; then
    r=$(fetch ex-wiki "$WIKI"); echo "$r"
    dns_log ex-log | tee "$OUT/dns-excluded.txt"
    # The name still resolves through the tunnel; the connection to the excluded host doesn't enter it.
    if ! echo "$r" | grep -q ' 200 ' && grep -q '^tunnel wiki\.corp\.test\.\? A NOERROR 10\.77\.0\.20' "$OUT/dns-excluded.txt"; then
        result PASS "excluded subnet 10.77.0.20/32 bypasses the tunnel ($r)"
    else
        result FAIL "excluded subnet: $r"
    fi
    disconnect excluded
else
    result FAIL "couldn't set an excluded subnet and connect: $r"
fi
debug ex-reset profile-exclude profile "$P"   # no cidrs extra = none

# --- App modes ---------------------------------------------------------------------------------

say "Only selected apps (Chrome)"
r=$(debug inc-set profile-apps profile "$P" mode include packages com.android.chrome); echo "$r"
if [ "$r" = "profile-apps ok" ] && connect include; then
    r=$(fetch inc-app "$WIKI"); echo "$r"
    app_ok=1; echo "$r" | grep -q ' 200 ' && app_ok=0
    chrome_ok=skip
    if [ "$have_chrome" = 1 ]; then
        chrome_open "$WIKI?m4=include"; chrome_shows "Welcome to nginx" && chrome_ok=1 || chrome_ok=0
        shot 2-include-chrome
    fi
    if [ "$app_ok" = 1 ] && [ "$chrome_ok" != 0 ]; then
        result PASS "Only selected apps: Chrome loads the wiki (${chrome_ok/1/yes}), sshovel itself is outside the tunnel ($r)"
    else
        result FAIL "Only selected apps: app fetch '$r', Chrome shows wiki: $chrome_ok"
    fi
    adb shell am start -n "$PKG/.ui.MainActivity" >/dev/null
    disconnect include
else
    result FAIL "couldn't set include mode and connect: $r"
fi

say "All except selected (Chrome)"
r=$(debug exc-set profile-apps profile "$P" mode exclude packages com.android.chrome); echo "$r"
if [ "$r" = "profile-apps ok" ] && connect exclude; then
    r=$(fetch exc-app "$WIKI"); echo "$r"
    chrome_blocked=skip
    if [ "$have_chrome" = 1 ]; then
        chrome_open "$WIKI?m4=exclude"; chrome_shows "Welcome to nginx" 4 && chrome_blocked=0 || chrome_blocked=1
        shot 3-exclude-chrome
    fi
    if echo "$r" | ok && [ "$chrome_blocked" != 0 ]; then
        result PASS "All except selected: sshovel loads the wiki, Chrome doesn't (${chrome_blocked/1/yes})"
    else
        result FAIL "All except selected: app fetch '$r', Chrome blocked: $chrome_blocked"
    fi
    adb shell am start -n "$PKG/.ui.MainActivity" >/dev/null
    disconnect exclude
else
    result FAIL "couldn't set exclude mode and connect: $r"
fi
debug apps-reset profile-apps profile "$P" mode all

# --- Validation --------------------------------------------------------------------------------

say "Validation blocks an overlapping tun subnet"
r=$(debug tun-overlap profile-tun profile "$P" cidr 10.77.0.0/24 dnsip 10.77.0.53); echo "$r"
list=$(debug tun-check profile-list); echo "$list"
# core/config reports the overlap on the route it collides with (routes[0] here).
if echo "$r" | grep -q '^profile-tun invalid .*TUN_OVERLAPS_ROUTE@routes\[0\]'; then
    result PASS "saving tun subnet 10.77.0.0/24 (overlaps route 10.77.0.0/24) is refused: ${r#profile-tun invalid }"
else
    result FAIL "overlapping tun subnet: $r"
fi
connect after-validation && result PASS "the profile still connects with its original tun subnet" ||
    result FAIL "profile no longer connects after the refused edit"
disconnect after-validation

say "Summary"
write_summary M4
