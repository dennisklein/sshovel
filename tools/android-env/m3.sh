#!/bin/bash
# SPDX-FileCopyrightText: 2026 Dennis Klein
# SPDX-License-Identifier: GPL-3.0-or-later
#
# M3 acceptance (IMPLEMENTATION_PLAN §6), inside the toolbox (see run.sh):
#   1. ./gradlew check assembleDebug, and the instrumented tests (vault round trip)
#   2. a key generated on the device authenticates against test-env, after the
#      host key is verified through the app's "Verify server" dialog
#   3. an imported passphrase-protected Ed25519 key works after an app restart,
#      without the passphrase
#   4. replacing the jump host's host key gives HOST_KEY_MISMATCH and no tunnel;
#      only "forget" + verify accepts the new key
# Results: tools/android-env/out/ (summary.txt first).
set -uo pipefail

OUT=/work/tools/android-env/out
rm -rf "$OUT"; mkdir -p "$OUT"
exec > >(tee "$OUT/run.log") 2>&1
source /work/tools/android-env/lib.sh
trap cleanup EXIT
AUTH_KEYS=/work/test-env/keys/acceptance_authorized_keys
: > "$AUTH_KEYS"

# connect_and_wiki <mark> <profile>: connects, waits for On, fetches the wiki.
connect_and_wiki() {
    mark "$1"; app connect profile "$2"
    wait_log "$1" 'sshovel/State.*(On\(|NeedsAttention)' 60 | grep -q 'On(' || return 1
    fetch "$1-wiki" http://wiki.corp.test/ | grep -q ' 200 .*title="Welcome to nginx!"'
}
disconnect() {
    mark "disconnect-$1"; app disconnect
    wait_log "disconnect-$1" 'sshovel/State.*Off' 30 >/dev/null
}

# --- Build and instrumented tests -------------------------------------------------

say "Toolchain"; toolchain
say "Wait for test-env's host key"; wait_hostkey

echo "sdk.dir=$ANDROID_HOME" > /work/local.properties
say "./gradlew check assembleDebug"
if gradle check :app:assembleDebug > "$OUT/gradle-check.log" 2>&1; then
    result PASS "gradle check (unit tests, lint, license tasks, reuseLint, verifyPageAlignment)"
else
    tail -n 60 "$OUT/gradle-check.log"; die "gradle check failed (gradle-check.log)"
fi

say "Boot emulator"; boot_emulator

say "Instrumented tests"
if gradle :app:connectedDebugAndroidTest > "$OUT/android-test.log" 2>&1; then
    result PASS "instrumented tests: vault encrypt/decrypt round trip with the Keystore key, Keystore signing"
else
    tail -n 40 "$OUT/android-test.log"; result FAIL "instrumented tests (android-test.log)"
fi
cp -r /work/app/build/outputs/androidTest-results "$OUT/" 2>/dev/null || true
grep -ho 'testcase name="[^"]*" classname="[^"]*"' /work/app/build/outputs/androidTest-results/connected/debug/*.xml 2>/dev/null |
    sed 's/testcase name="\([^"]*\)" classname=".*\.\([^".]*\)"/  \2.\1/'

start_logcat
say "Install"
install_app
adb shell am start -n "$PKG/.ui.MainActivity" >/dev/null; sleep 3; shot 1-home

# --- Generated key ------------------------------------------------------------------

say "Generated key"
r=$(debug gen-create key-create name m3-generated); echo "$r"
gen_key=$(echo "$r" | awk '{print $2}'); gen_security=$(echo "$r" | awk '{print $3}')
echo "$r" | cut -d' ' -f4- >> "$AUTH_KEYS"
[ -n "$gen_key" ] || die "key-create failed: $r"
r=$(debug gen-profile profile-add name m3-generated key "$gen_key"); echo "$r"
gen=$(echo "$r" | awk '{print $2}')
[ -n "$gen" ] || die "profile-add failed: $r"

mark gen-unverified; app connect profile "$gen"
if wait_log gen-unverified 'sshovel/State.*NeedsAttention\(code=HOST_KEY_UNVERIFIED' 60 >/dev/null && no_tun; then
    result PASS "an unpinned profile stops at HOST_KEY_UNVERIFIED before any tunnel"
else
    result FAIL "unpinned profile did not stop at HOST_KEY_UNVERIFIED"
fi
sleep 2; shot 2-unverified

say "Verify server through the app's dialog"
if tap_text "Verify server" && sleep 4 && shot 3-verify-dialog && mark gen-trusted && tap_text "Trust this server"; then
    if wait_log gen-trusted 'sshovel/State.*On\(' 60 >/dev/null; then
        echo "server host keys: $(host_fps | tr '\n' ' ')"
        r=$(fetch gen-wiki http://wiki.corp.test/); echo "$r"
        echo "$r" | grep -q ' 200 .*title="Welcome to nginx!"' &&
            result PASS "a generated Keystore key ($gen_security) authenticates; wiki loads ($r)" ||
            result FAIL "generated key connected but wiki fetch failed: $r"
        sleep 1; shot 4-generated-on
    else
        result FAIL "no On after trusting the server"
    fi
else
    result FAIL "couldn't drive the Verify server dialog (see 2-unverified.png, 3-verify-dialog.png)"
fi
disconnect gen

# --- Imported passphrase-protected Ed25519 key ------------------------------------------

say "Imported key"
PASS_PHRASE=m3-correct-horse
ssh-keygen -q -t ed25519 -N "$PASS_PHRASE" -C m3-import -f "$OUT/m3_import_key"
b64=$(base64 -w0 "$OUT/m3_import_key")
r=$(debug imp-wrong key-import name m3-imported key "$b64" passphrase wrong-passphrase); echo "$r"
[ "$r" = "key-import error KEY_PASSPHRASE" ] && result PASS "wrong passphrase → KEY_PASSPHRASE" ||
    result FAIL "wrong passphrase gave: $r"
r=$(debug imp-ok key-import name m3-imported key "$b64" passphrase "$PASS_PHRASE"); echo "$r"
imp_key=$(echo "$r" | awk '{print $2}')
[ -n "$imp_key" ] && [ "$imp_key" != error ] || die "import failed: $r"
cat "$OUT/m3_import_key.pub" >> "$AUTH_KEYS"
want_fp=$(ssh-keygen -lf "$OUT/m3_import_key.pub" | awk '{print $2}')
echo "$r" | grep -qF "$want_fp" && echo "fingerprint matches $want_fp" || result FAIL "imported fingerprint differs from $want_fp"
r=$(debug imp-profile profile-add name m3-imported key "$imp_key"); echo "$r"
imp=$(echo "$r" | awk '{print $2}')
r=$(debug imp-trust trust profile "$imp"); echo "$r"

# On disk: no plaintext key, no passphrase.
data=/data/data/$PKG
if ! adb shell "test -s $data/no_backup/vault/$imp_key.bin && test -s $data/files/datastore/sshovel.json"; then
    result FAIL "vault entry or store file missing"
elif adb shell "cat $data/no_backup/vault/$imp_key.bin" | grep -qa "OPENSSH\|$PASS_PHRASE" ||
   adb shell "cat $data/files/datastore/sshovel.json" | grep -qa "PRIVATE KEY\|$PASS_PHRASE"; then
    result FAIL "key material or passphrase found in app storage"
else
    result PASS "vault entry is ciphertext; the store holds no key material or passphrase"
fi

say "Restart the app, then connect without a passphrase"
adb shell am force-stop "$PKG"; sleep 2
if connect_and_wiki imp-connect "$imp"; then
    result PASS "imported passphrase-protected Ed25519 key connects after an app restart, no passphrase asked; wiki loads"
else
    result FAIL "imported key did not connect or wiki failed"
fi
shot 5-imported-on
disconnect imp
grep -q "$PASS_PHRASE" "$OUT/logcat.txt" && result FAIL "passphrase appears in logcat" ||
    result PASS "passphrase never appears in logcat"

# --- Host key replaced ---------------------------------------------------------------------

say "Replace the jump host's host key"
old_fps=$(host_fps | sort | tr '\n' ' ')
rm -f "$HOSTKEYS"/ssh_host_*
for _ in $(seq 1 20); do
    sleep 1
    [ -f "$HOSTKEYS/ssh_host_ed25519_key.pub" ] && [ -f "$HOSTKEYS/ssh_host_ecdsa_key.pub" ] &&
        [ "$(host_fps | sort | tr '\n' ' ')" != "$old_fps" ] && break
done
sleep 2
echo "old: $old_fps"; echo "new: $(host_fps | tr '\n' ' ')"

mark mismatch; app connect profile "$gen"
if wait_log mismatch 'sshovel/State.*NeedsAttention\(code=HOST_KEY_MISMATCH' 60; then
    if since mismatch | grep -q 'sshovel/State.*On(' || ! no_tun; then
        result FAIL "HOST_KEY_MISMATCH reported, but a tunnel came up"
    else
        result PASS "replaced host key → HOST_KEY_MISMATCH; the tunnel never came up"
    fi
else
    result FAIL "no HOST_KEY_MISMATCH after replacing the host key"
fi
sleep 2; shot 6-mismatch

r=$(debug mm-trust trust profile "$gen"); echo "$r"
[ "$r" = "trust refused already-pinned" ] && result PASS "the changed key can't be trusted while a pin exists" ||
    result FAIL "trust with an existing pin gave: $r"
tap_text "Disconnect" || true

say "Forget the pinned key (explicit user action), verify, connect"
debug mm-forget forget profile "$gen"
r=$(debug mm-retrust trust profile "$gen"); echo "$r"
if connect_and_wiki mm-connect "$gen"; then
    result PASS "after Forget pinned key + verify, the new host key connects"
else
    result FAIL "no connection after forget + verify"
fi
disconnect mm

say "Summary"
write_summary M3
