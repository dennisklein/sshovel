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

## Status: complete

All six §11 items were verified on 2026-09-25 using `spikes/env/run.sh` on a Linux/KVM host. The
emulator was API 36 `google_apis` x86_64 (build BE2A.250530.026.F3), with a second
`google_apis_ps16k` emulator for the 16 KB page-size run. The toolchain was NDK r30
(30.0.16248370), AGP 9.4.1 and Go 1.26.3. Raw logs, screenshots and `summary.txt` are in
`spikes/out/`. The decisions are recorded in ARCHITECTURE §7 and §11 on `main`.

| §11 item | Result | Decision |
|---|---|---|
| 1. FGS type | `systemExempted` and `specialUse` both let `startForeground` succeed from the app, the tile, the consent activity, and an Always-on boot start. | **`systemExempted`**. |
| 2. Tile start path | `onClick` → `startForegroundService` works with the app's process killed (importance 125 during the click). Without consent: `startActivityAndCollapse` → consent activity → system dialog → the VPN comes up. **Lock screen (PIN):** SystemUI showed the bouncer and never called `onClick`. | Keep `unlockAndRun` for "require unlock"; check the lock screen on a physical device in M5. |
| 3. `exported` | With `exported="false"` and with `"true"`: the app is listed under Settings → VPN, and Always-on starts the service at boot (`action=android.net.VpnService`). | **`exported="false"`** plus `BIND_VPN_SERVICE`. |
| 4. `DnsResolver.rawQuery` on the underlying network | With a `0.0.0.0/0` VPN up: answered (rcode 0, 267 ms; 21 ms with strict Private DNS), and **0** DNS packets reached the TUN. The control on the VPN network went into the TUN to `10.99.0.53:53` and timed out. Android also probed TCP/853 on the VPN DNS address. | Confirmed as designed; RST on 853 (§4) is needed. |
| 5. gVisor on gomobile, 16 KB | The AAR builds with NDK r30. Every `.so` (arm64, x86_64) in the AAR and APK has 16 KB `LOAD` alignment with no extra flags, and `zipalign -P 16` passes. On the 16 KB kernel (`PAGE_SIZE=16384`) the library loads and runs a gVisor stack. | Confirmed. `gomobile bind` needs the pinned `gobind` on `PATH`. |
| 6. Keystore signer vs OpenSSH | A Keystore P-256 key (`NONEwithECDSA`, 72-byte DER) authenticated as `tester` against **OpenSSH 10.2**, and `direct-tcpip` to `10.77.0.20:80` worked. The emulator has no StrongBox, and the key's `securityLevel` is 0 (software). | Confirmed. The hardware badge comes from `KeyInfo.getSecurityLevel()`. |

## Other findings

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
4. if the SDK offers a 16 KB page-size image (`google_apis_ps16k`), boots it and loads the Go
   library there. A misaligned `.so` fails to load on a 16 KB kernel, so this is the runtime proof
   for spike 1.

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
