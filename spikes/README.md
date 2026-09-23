<!--
SPDX-FileCopyrightText: 2026 <Copyright holder>
SPDX-License-Identifier: GPL-3.0-or-later
-->

# M0: platform spikes

Throwaway code for verifying ARCHITECTURE §11 before building on it. It is not merged into
`main`. Only `test-env/`, which lives at the repo root on this branch, carries forward into M1.

| Path | What |
|---|---|
| `core/` | Go module. `mobile/` is a gomobile surface covering spike 1 (`Hello`: gVisor stack on an fd) and spike 4 (`TestAuth`: SSH auth with a platform-backed ECDSA signer). `build-aar.sh` builds the AAR; `check-alignment.sh` checks 16 KB alignment. |
| `android/` | Plain-Java spike app, with no AndroidX so AGP is the only dependency. It has one button per check, the VpnService, the tile and the consent activity. |
| `../test-env/` | docker compose intranet from IMPLEMENTATION_PLAN §5 |

## Status

The spikes were run in a cloud container that has **no KVM** (so no emulator). Its network policy
also blocks `dl.google.com` (Android SDK, NDK, AGP, AndroidX), the Docker Hub blob CDN, and the
Alpine CDN. Everything that doesn't need those was verified there. The rest is ready to run with
the runbook below.

| §11 item | Spike | Result |
|---|---|---|
| 5. gVisor on gomobile, 16 KB alignment | 1 | **Partial.** The gVisor `@go` branch builds for android/arm64 and android/amd64. A real stack (fdbased NIC, promiscuous + spoofing, default route, TCP forwarder) runs under `go test -race`. `gobind` generates clean Java bindings. **Pending:** `gomobile bind` (needs the NDK), loading on API 36, and the alignment check. |
| 6. `NewSignerFromSigner` + Kotlin ECDSA signer vs. OpenSSH 9.x | 4 | **Passed with a JVM stand-in.** A JCA `NONEwithECDSA` signer (the same call PlatformBridge makes on AndroidKeyStore) authenticated against stock **OpenSSH 9.6p1** through `crypto.Signer` → `ssh.NewSignerFromSigner`. `direct-tcpip` through it works. **Pending:** the same with a real Keystore key on the emulator. |
| 1. FGS type | 2 | **Pending (device).** Build variants `-PfgsType=systemExempted` and `-PfgsType=specialUse`. |
| 2. Tile start path, consent and no consent | 2 | **Pending (device).** |
| 3. `VpnService` `exported` value | 2/3 | **Pending (device).** Build variants `-PvpnExported=true` and `-PvpnExported=false`. |
| 4. `DnsResolver.rawQuery` not captured by our VPN | 3 | **Pending (device).** Includes a control query on the VPN network. |

## Findings so far

1. **gVisor `@go` requires Go ≥ 1.26.3** (`go` directive in its go.mod; the toolchain resolved
   to Go 1.26.8). Pinned: `gvisor.dev/gvisor v0.0.0-20260923023802-c84204b5f2fd`.
2. **Pin gomobile in `go.mod` with the `tool` directive** (`go get -tool golang.org/x/mobile/cmd/gomobile`)
   and build with `go tool gomobile bind`. Without it, `go mod tidy` drops `x/mobile` and `gomobile
   bind` fails ("golang.org/x/mobile/bind is not found"). It also pins the binding generator for
   reproducible release builds (ARCHITECTURE §12 item 4). *Proposed spec change:* use
   `go tool gomobile …` in CLAUDE.md and IMPLEMENTATION_PLAN §4.
3. **gomobile maps Go `int` to Java `long`.** The §8 contract already uses `int32` for fds, so there's
   no spec change. Keep avoiding bare `int` in `core/mobile`.
4. **`restrict,port-forwarding` does *not* block route discovery.** OpenSSH's `restrict` disables
   port, agent and X11 forwarding, PTY allocation and `~/.ssh/rc`. It doesn't disable `exec`. The test
   ran `ip -4 route show` over an exec channel under that exact line. The design chat assumed the
   opposite, so handoff screen D3 ("server doesn't allow commands") is an edge case (`ForceCommand`,
   `command=`, no shell), not the default outcome. No spec change needed; note for M6 copy.
5. **`restrict` without `port-forwarding` yields `OpenChannelError{Reason: Prohibited}`**
   ("administratively prohibited"). That confirms the `FORWARDING_DENIED` classification in §4.
   A missing key yields `unable to authenticate … no supported methods remain`, which maps to `AUTH_FAILED`.
6. **Minimal servers may have neither `ip` nor `netstat`.** The Ubuntu 24.04 container used here
   had neither until iproute2 was installed. *Proposal:* add `cat /proc/net/route` (always present on
   Linux, hex-encoded) as a third fallback in §6 route discovery.

## Runbook for the device spikes

Prerequisites: Docker, JDK 17+, Go (any recent version; `GOTOOLCHAIN=auto` fetches 1.26.x),
Android SDK with platform 36, NDK r28+, and an **API 36 emulator** (Google APIs, x86_64).

```bash
export ANDROID_HOME=~/Android/Sdk ANDROID_NDK_HOME=$ANDROID_HOME/ndk/<r28+ version>

# 0. Fake intranet
docker compose -f test-env/compose.yaml up --build -d

# 1. AAR + alignment (prints OK/FAIL per .so)
spikes/core/build-aar.sh

# App (default variant: systemExempted, exported=true). Bump AGP in
# spikes/android/build.gradle.kts if 9.0.0 isn't available.
cd spikes/android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:installDebug
../core/check-alignment.sh app/build/outputs/apk/debug/app-debug.apk
$ANDROID_HOME/build-tools/*/zipalign -c -P 16 -v 4 app/build/outputs/apk/debug/app-debug.apk | tail -1

# Capture everything (leave running)
adb logcat -s sshovel-spike | tee m0.log
```

In the **sshovel spikes** app (every result is a `SPIKEn` line in the log):

1. **Spike 1:** tap *Go hello*. Expect `gVisor netstack up … android/amd64`.
2. **Spike 2 (tile):** tap *Allow notifications*, then *Add tile* and accept.
   - a. First run has no consent yet. Press Home, open Quick Settings and tap the tile. Expect
     `startActivityAndCollapse … OK`, then the system dialog; accept it. Expect
     `startForeground OK with type …`.
   - b. Swipe the app away from Recents. Tap the tile on, then off. Expect
     `startForegroundService(START) OK` / `startService(STOP) OK`, or a logged exception.
   - c. Lock the screen and tap the tile from the lock screen.
   - Repeat a and b after `./gradlew :app:installDebug -PfgsType=specialUse`. To reset consent,
     uninstall first.
3. **Spike 3 (Always-on / exported):** open Settings → Network & internet → VPN → *sshovel spikes* ⚙.
   Is Always-on offered? Turn it on, run `adb reboot`, and look for `onStartCommand … origin=null-intent`
   (or `action=android.net.VpnService`). Repeat with `-PvpnExported=false`.
4. **Spike 3 (DNS):** set the route to `0.0.0.0/0` and tap *Start VPN*. The TUN black-holes everything.
   - *Query via underlying network* should give `answer rcode=0 … DNS packets seen on our TUN during query: 0`.
   - *Control: query via default network* should time out or fail, with more than 0 DNS packets on the TUN.
   - Optional: set Private DNS to strict (`dns.google`) and repeat.
5. **Spike 4 (Keystore):** stop the VPN. Tap *Generate key*, copy the `authorized_keys` line from
   `m0.log` into `test-env/keys/authorized_keys`, then tap *Test SSH auth*. Expect
   `SPIKE4 OK: authenticated as tester with ecdsa-sha2-nistp256; server SSH-2.0-OpenSSH_… direct-tcpip to 10.77.0.20:80 OK`,
   plus the key's `securityLevel`.

Send back `m0.log`, the alignment output, and a note of what the system UI did at each step
(dialogs and crashes). Decisions and ARCHITECTURE updates for items 1–5 follow from those.

## Verified here (reproduce)

```bash
cd spikes/core && GOTOOLCHAIN=auto go test -race -v ./...   # needs /usr/sbin/sshd and java; tests skip otherwise
```
