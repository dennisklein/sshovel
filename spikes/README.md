<!--
SPDX-FileCopyrightText: 2026 Dennis Klein
SPDX-License-Identifier: GPL-3.0-or-later
-->

# M0: platform spikes

Throwaway code for verifying ARCHITECTURE §11 before building on it. It is not merged into
`main`. Only `test-env/`, which lives at the repo root on this branch, carries forward into M1.

| Path | What |
|---|---|
| `core/` | Go module. `mobile/` is a gomobile surface covering spike 1 (`Hello`: gVisor stack on an fd) and spike 4 (`TestAuth`: SSH auth with a platform-backed ECDSA signer). `build-aar.sh` builds the AAR; `check-alignment.sh` checks 16 KB alignment. |
| `android/` | Plain-Java spike app, with no AndroidX so AGP is the only dependency. It has one button per check (also scriptable over adb), the VpnService, the tile and the consent activity. |
| `env/` | Docker toolbox (SDK, NDK, emulator, JDK, Go) plus `run.sh`, which runs every device spike automatically |
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

Requirements: a **Linux x86_64** machine with virtualization enabled (`/dev/kvm` exists), Docker
with the compose plugin (v2.20+), about 20 GB of free disk, and internet access. Nothing else is
needed, since the SDK, NDK, emulator, JDK and Go all live in the container.

```bash
git checkout spikes
spikes/env/run.sh            # all variants, ~30-45 min the first time (image build ~15 min)
spikes/env/run.sh A          # or a single variant
```

`run.sh` builds the toolbox image (`spikes/env/Dockerfile`) and starts `test-env`. It then runs
`spikes/env/run-spikes.sh` inside the container, which:

1. builds `core.aar` with `go tool gomobile bind` and checks 16 KB alignment of the AAR and APK
   (spike 1)
2. builds three APK variants: **A** `systemExempted` + `exported=true`, **B** `specialUse` +
   `exported=true`, and **C** `systemExempted` + `exported=false`
3. boots a headless API 36 emulator (`google_apis`, x86_64) and drives every scenario with no taps
   needed:
   - Go hello (spike 1)
   - tile clicks through `cmd statusbar click-tile`: without consent, with the consent dialog
     accepted through uiautomator, with the app process killed, and from the lock screen (spike 2)
   - `DnsResolver.rawQuery` on the underlying vs. the default network while a black-hole VPN is up,
     also with strict Private DNS (spike 3)
   - Keystore key generation, writing its line into `test-env/keys/authorized_keys`, and SSH auth
     plus `direct-tcpip` to the wiki (spike 4)
   - Always-on configured and the emulator rebooted, to see whether the system starts the service
     (spike 3, variants A and C)

Results land in `spikes/out/`:

- `summary.txt`: every spike log line plus relevant system errors, per variant
- `alignment.txt`
- `<variant>/logcat.txt`
- screenshots

`test-env/keys/authorized_keys` is restored afterwards. To send the results back, commit and push
them (the APKs are git-ignored):

```bash
git add spikes/out && git commit -m "M0 spike results" && git push
```

If you want to click through by hand instead, the app has one button per check. In the container,
run `emulator -avd spike` without `-no-window`, or install `spikes/out/A.apk` on any API 36 device.
Watch `adb logcat -s sshovel-spike`.

Caveat: `cmd statusbar click-tile` goes through the same SystemUI click path as a finger tap. Only
a manual tap on a real device would rule out any difference in background-start exemptions, and
that's worth a quick check once the real tile exists (M5).

## Verified here (reproduce)

```bash
cd spikes/core && GOTOOLCHAIN=auto go test -race -v ./...   # needs /usr/sbin/sshd and java; tests skip otherwise
```
