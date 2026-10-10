# shellcheck shell=bash
# SPDX-FileCopyrightText: 2026 Dennis Klein
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Shared helpers for the milestone acceptance scripts (m3.sh and later), sourced
# inside the toolbox. Set OUT before sourcing. m2.sh predates this file and
# keeps its own copies.

PKG=com.github.dennisklein.sshovel
APK=/work/app/build/outputs/apk/debug/app-debug.apk
HOSTKEYS=/work/test-env/hostkeys

owner=$(stat -c %u:%g /work)
cleanup() {
    adb emu kill >/dev/null 2>&1 || true
    [ -n "${LOGCAT_PID:-}" ] && kill "$LOGCAT_PID" 2>/dev/null
    for p in "$OUT" /work/.gradle /work/.kotlin /work/build /work/app/build /work/app/libs /work/local.properties \
             /work/test-env/keys /work/test-env/hostkeys; do
        [ -e "$p" ] && chown -R "$owner" "$p"
    done
}

RESULTS=()
result() { RESULTS+=("$(printf '%-4s %s' "$1" "$2")"); echo "RESULT: $1 $2"; }
say() { printf '\n=== %s\n' "$*"; }
die() { echo "FATAL: $*"; result FAIL "$*"; exit 1; }
gradle() { (cd /work && ./gradlew --no-daemon --console=plain "$@"); }

toolchain() {
    cat /opt/sdk-versions; emulator -version 2>/dev/null | head -n1; java -version 2>&1 | head -n1; (cd /work/core && go version)
    go-licenses --help >/dev/null 2>&1 && echo "go-licenses OK"; reuse --version | head -n1
}

wait_hostkey() {
    for _ in $(seq 1 60); do [ -f "$HOSTKEYS/ssh_host_ed25519_key.pub" ] && break; sleep 1; done
    [ -f "$HOSTKEYS/ssh_host_ed25519_key.pub" ] || die "test-env jump host never wrote its host key"
    ssh-keygen -lf "$HOSTKEYS/ssh_host_ed25519_key.pub"
}

# host_fps: the fingerprints of all current jump host keys, one per line.
host_fps() { for k in "$HOSTKEYS"/*.pub; do ssh-keygen -lf "$k" | awk '{print $2}'; done; }

boot_emulator() {
    timeout 3 bash -c '</dev/tcp/127.0.0.1/2222' || die "test-env jump host not reachable on 127.0.0.1:2222"
    emulator -version 2>/dev/null | head -n1
    # Software rendering, first choice first. Emulator releases have changed which of these work
    # headless (37.2.12 segfaults with swiftshader_indirect, boots with swangle_indirect); if the
    # emulator dies before Android has booted, try the next. adb lists the emulator long before
    # boot, so wait for the boot.
    local gpu pid booted=0
    for gpu in swangle_indirect swiftshader_indirect guest; do
        echo "emulator -gpu $gpu" | tee -a "$OUT/emulator.log"
        emulator -avd sshovel -no-window -no-audio -no-boot-anim -no-snapshot -gpu "$gpu" -accel on \
            >> "$OUT/emulator.log" 2>&1 &
        pid=$!
        for _ in $(seq 1 450); do
            kill -0 "$pid" 2>/dev/null || break
            [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && { booted=1; break; }
            sleep 2
        done
        [ "$booted" = 1 ] && break
        if kill -0 "$pid" 2>/dev/null; then tail -n 30 "$OUT/emulator.log"; die "emulator -gpu $gpu didn't boot in 15 min (emulator.log)"; fi
        echo "emulator -gpu $gpu exited before boot:"; tail -n 8 "$OUT/emulator.log"
        adb kill-server >/dev/null 2>&1
    done
    [ "$booted" = 1 ] || die "the emulator crashed before boot with every GPU mode (emulator.log)"
    echo "emulator -gpu $gpu" > "$OUT/emulator-gpu.txt"
    sleep 10
    adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard; adb shell svc power stayon true
    for s in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global $s 0; done
    adb shell getprop ro.build.fingerprint | tee "$OUT/device.txt"
    # adb root restarts adbd, which kills a running `adb logcat`: do it before logcat starts.
    adb root >/dev/null 2>&1; sleep 2; adb wait-for-device
}

start_logcat() {
    adb logcat -c
    adb logcat -v time > "$OUT/logcat.txt" 2>/dev/null &
    LOGCAT_PID=$!
}

# app <cmd> [key value]...: a DebugCommands command (values must not contain spaces).
app() {
    local cmd=$1; shift; local args=()
    while [ $# -ge 2 ]; do args+=(--es "$1" "$2"); shift 2; done
    adb shell am start -n "$PKG/.ui.MainActivity" --es cmd "$cmd" "${args[@]}" >/dev/null
}
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
# Progress goes to stderr: helpers that print results (debug, fetch) must return only those.
# Marks end in " ===" so that "connect-exclude" doesn't also match "connect-excluded".
mark() { adb shell log -t sshovel-m -- "=== $* ==="; echo "--- $*" >&2; }
# wait_log <mark> <regex> <timeout>: the first line matching regex after the mark.
# (Not `awk | grep -m1 && …`: under pipefail, grep exiting early fails the pipeline, so the
# loop would keep printing the same match until the timeout.)
wait_log() {
    local m=$1 re=$2 t=$3 hit
    for _ in $(seq 1 "$t"); do
        hit=$(since "$m" | grep -E "$re" | head -n1 || true)
        if [ -n "$hit" ]; then
            echo "$hit"
            return 0
        fi
        sleep 1
    done
    return 1
}
# since <mark>: all log lines after the mark.
since() { awk -v m="=== $1 ===" 'index($0, m) {on=1} on' "$OUT/logcat.txt"; }
# debug <mark> <cmd> [key value]...: runs a DebugCommands command, prints its result text.
debug() {
    local m=$1 cmd=$2; shift 2
    mark "$m"; app "$cmd" "$@"
    wait_log "$m" "sshovel/Debug.*: $cmd " 30 | sed "s/.*sshovel\/Debug([ 0-9]*): //"
}
fetch() {
    mark "$1"; app fetch url "$2"
    wait_log "$1" "sshovel/Debug.*fetch $2 ->" 40 | sed 's/.*fetch /fetch /' || echo "fetch $2 -> no result"
}

# tap_text <text>: taps the centre of the first on-screen node whose text is <text>.
tap_text() {
    local b
    for _ in $(seq 1 10); do
        adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
        b=$(adb exec-out cat /sdcard/ui.xml | tr '>' '\n' | grep -F "text=\"$1\"" | head -n1 |
            sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')
        if [ -n "$b" ]; then
            set -- $b
            adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
            return 0
        fi
        sleep 1
    done
    return 1
}

install_app() {
    adb install -r -g "$APK" || die "install failed"
    adb shell appops set "$PKG" ACTIVATE_VPN allow   # VPN consent without the dialog (explainer: M5)
}

# no_tun: true if no VPN TUN interface (tun0, tun1, …) exists right now. The kernel's own
# IPIP device is called tunl0 and doesn't count.
no_tun() {
    ! adb shell ip -o link 2>/dev/null | grep -Eq '^[0-9]+: tun[0-9]+[:@]'
}

write_summary() {
    {
        echo "sshovel $1 run $(date -u +%FT%TZ)"
        echo "SDK: $(cat /opt/sdk-versions); device: $(tr -d '\r' < "$OUT/device.txt")"
        echo
        printf '%s\n' "${RESULTS[@]}"
        echo
        echo "## State transitions"
        grep "sshovel/State\|sshovel/Service\|sshovel-m\|sshovel/Debug" "$OUT/logcat.txt" | sed 's/^[0-9-]* //'
    } > "$OUT/summary.txt"
    cat "$OUT/summary.txt"
}

# shows <text> [seconds]: waits until a node's text contains <text> (uiautomator dump).
shows() {
    for _ in $(seq 1 "${2:-15}"); do
        adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
        adb exec-out cat /sdcard/ui.xml | grep -qF "$1" && return 0
        sleep 1
    done
    return 1
}

# Chrome without first-run screens or the notification prompt (needs adb root).
chrome_setup() {
    adb shell pm list packages | grep -q com.android.chrome || return 1
    adb shell pm grant com.android.chrome android.permission.POST_NOTIFICATIONS 2>/dev/null || true
    adb shell 'echo "chrome --disable-fre --no-default-browser-check --no-first-run" > /data/local/tmp/chrome-command-line'
    adb shell am set-debug-app --persistent com.android.chrome
}
# chrome_open <url>; chrome_shows <text> [seconds]: polls the UI dump for the text.
chrome_open() { adb shell am start -a android.intent.action.VIEW -d "$1" com.android.chrome >/dev/null; }
chrome_shows() {
    for _ in $(seq 1 "${2:-10}"); do
        sleep 3
        adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
        adb exec-out cat /sdcard/ui.xml | grep -q "$1" && return 0
    done
    return 1
}
