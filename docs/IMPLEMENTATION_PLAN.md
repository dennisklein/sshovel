# sshovel — implementation plan

Audience: Claude Code. License: **GPL-3.0-or-later** (see `ARCHITECTURE.md` §12). Work milestone by milestone and stop for review after each one. Behavior
comes from `ARCHITECTURE.md`, states and copy from `DESIGN_BRIEF.md`, and visuals from the Claude
Design handoff in `docs/design/`.

## 1. Repository layout

```
/
├── CLAUDE.md
├── LICENSE                       verbatim GPL-3.0 text (FSF)
├── REUSE.toml                    license info for files that can't carry SPDX headers
├── CONTRIBUTING.md               DCO sign-off, license notes (M8)
├── docs/                         ARCHITECTURE.md, IMPLEMENTATION_PLAN.md, DESIGN_BRIEF.md, design/,
│                                 THIRD_PARTY.md (copied code + approved license exceptions)
├── core/                         Go module (module path: github.com/dennisklein/sshovel/core)
│   ├── go.mod
│   ├── mobile/                   gomobile-facing API only (ARCHITECTURE §8)
│   ├── engine/                   state machine, reconnect, stats
│   ├── netstack/                 gVisor stack, TCP/UDP forwarders
│   ├── sshx/                     dial+protect, auth, host key, keepalive, route discovery
│   ├── dnsproxy/                 split DNS, DoTCP pool, truncation
│   ├── config/                   profile schema + validation
│   └── internal/testutil/        in-process sshd, fake DNS, netstack peer
├── app/                          Android application module
│   └── src/main/java/…/sshovel/
│       ├── ui/                   theme/, components/, screens/<feature>/, navigation/
│       ├── tunnel/               TunnelController, TunnelState, SshovelVpnService, NetworkMonitor, PlatformBridge
│       ├── tile/                 TunnelTileService
│       ├── keys/                 KeystoreKeys, ImportedKeyVault, public-key formatting
│       ├── data/                 Profile models (@Serializable), ProfileRepository, SettingsRepository
│       ├── diagnostics/          ring buffers, export
│       └── AppContainer.kt       manual DI
├── test-env/                     docker compose intranet (§5)
├── gradle/libs.versions.toml
└── settings.gradle.kts, build.gradle.kts
```

## 2. Toolchain

Use the latest stable version of each at implementation time. Record exact versions in
`libs.versions.toml` and `go.mod`.

- **Go:** latest stable. gVisor tracks recent Go releases; its `@go` branch needs Go ≥ 1.26.3 (M0).
- **gomobile:** pinned in `core/go.mod` with the `tool` directive
  (`go get -tool golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind`) and run as
  `go tool gomobile`. Without the pin, `go mod tidy` drops `x/mobile` and `gomobile bind` fails
  (M0 finding); the pin also makes release builds reproducible.
- **Java package / applicationId:** `com.github.dennisklein.sshovel` (gomobile classes under
  `com.github.dennisklein.sshovel.core`).
- **Android SDK:** `compileSdk = 36`, `targetSdk = 36`, `minSdk = 36`.
- **NDK:** r28 or newer, which produces 16 KB-aligned ELF by default for C/C++.
- **JDK:** 17 or newer. Latest stable AGP and Kotlin 2.x, with the Compose compiler Gradle plugin.

## 3. Dependencies

**Go (`core/go.mod`)**

| Module | Use | License |
|---|---|---|
| `golang.org/x/crypto/ssh` | SSH client, signer wrapping, test server | BSD-3-Clause |
| `gvisor.dev/gvisor` **@go branch** | `pkg/tcpip/stack`, `link/fdbased`, `transport/tcp`, `transport/udp`, `adapters/gonet`, `network/ipv4` | Apache-2.0 |
| `github.com/miekg/dns` | DNS parsing, truncation, test resolver | BSD-3-Clause |
| `golang.org/x/mobile` | `gomobile bind` (build-time) | BSD-3-Clause |

Use `xjasonlyu/tun2socks` (MIT) as reference code for gVisor wiring. Don't depend on it. If you
adapt code from it, follow ARCHITECTURE §12 item 6.

**Android (`gradle/libs.versions.toml`)**

| Library | Use | License |
|---|---|---|
| Compose BOM, `material3`, `ui`, `ui-tooling-preview` | UI | Apache-2.0 |
| `activity-compose` | `setContent`, edge-to-edge | Apache-2.0 |
| `lifecycle-viewmodel-compose`, `lifecycle-runtime-compose` | ViewModels, `collectAsStateWithLifecycle` | Apache-2.0 |
| `navigation-compose` (type-safe routes) | Navigation (Navigation 3 is acceptable if stable) | Apache-2.0 |
| `datastore` (typed DataStore with a kotlinx.serialization serializer) | Profiles, settings | Apache-2.0 |
| `kotlinx-serialization-json` | Profile JSON shared with Go | Apache-2.0 |
| `kotlinx-coroutines-android` | Concurrency | Apache-2.0 |
| `com.google.zxing:core` | QR code for public keys | Apache-2.0 |
| `com.mikepenz:aboutlibraries-core`, `aboutlibraries-compose-m3` + its Gradle plugin | Open-source licenses screen, license allow-list check | Apache-2.0 |
| Test: JUnit 4/5, `kotlinx-coroutines-test`, Turbine, Compose UI test | Tests | EPL-2.0 (JUnit 5) / Apache-2.0; test-only, not shipped |

**Build tools** (not shipped): `github.com/google/go-licenses` (Apache-2.0) for collecting and checking
Go licenses, and `reuse` from FSFE for SPDX/REUSE linting.

Icons: import the Material Symbols named in the design handoff as vector drawables. Don't pull in
`material-icons-extended`.

**Dependency rule:** don't add anything beyond this list without writing down why in the milestone
report. Every shipped dependency must use a license on the allowed list in ARCHITECTURE §12. No
proprietary SDKs: no Google Play Services, Firebase, analytics, or crash reporting. JUnit 4 is
EPL-1.0 and JUnit 5 is EPL-2.0. Neither is GPL-compatible for distribution, which is fine because
tests are never shipped. Keep them strictly in test configurations.

## 4. Build integration (Go → AAR)

Add a Gradle task `buildGoCore` in `app/build.gradle.kts`, wired as a dependency of `preBuild`. It
runs:

```bash
cd core && go tool gomobile bind \
  -target=android/arm64,android/amd64 \
  -androidapi 26 \
  -javapkg=com.github.dennisklein.sshovel.core \
  -o ../app/libs/core.aar ./mobile
```

- `-androidapi` sets the NDK API level for the native code only. The app's minSdk stays 36. Raise it
  if the installed NDK supports it.
- Declare the Go sources as task inputs so the task is incremental.
- Add `app/libs/core.aar` to `.gitignore`.
- Add a check task `verifyPageAlignment` that fails the build if any `.so` in the AAR/APK has an ELF
  `LOAD` segment alignment below 16 KB. Use `llvm-objdump -p` from the NDK, or run
  `zipalign -c -P 16 -v 4` on the APK. If Go's linker doesn't produce 16 KB alignment by default,
  add `-ldflags="-extldflags=-Wl,-z,max-page-size=16384"`.

**License tasks** (wire all of them into `check`, and make release builds depend on them):

- **`collectGoLicenses`**
  1. Run `GOOS=android GOARCH=arm64 go-licenses save ./mobile --ignore github.com/dennisklein/sshovel --save_path=<build>/go-licenses`
     and `go-licenses report ./mobile --ignore github.com/dennisklein/sshovel` to produce the Go dependency list.
  2. Add Go's own `$(go env GOROOT)/LICENSE` explicitly.
  3. Convert the result into AboutLibraries-compatible JSON, or a small custom JSON, packaged as an
     app asset so the licenses screen can show Android and Go dependencies together.
- **`checkLicenses`**
  - Android: AboutLibraries strict mode with the allowed-license list from ARCHITECTURE §12.
  - Go: `GOOS=android GOARCH=arm64 go-licenses check ./mobile --ignore github.com/dennisklein/sshovel --allowed_licenses=Apache-2.0,BSD-2-Clause,BSD-3-Clause,MIT,ISC,MPL-2.0`.
    `--ignore` skips our own GPL module.
  - The task fails on anything unknown.
- **`reuseLint`:** runs `reuse lint`. If `reuse` isn't installed, fall back to a script that checks
  for SPDX headers in `*.go`, `*.kt`, `*.kts`, `*.xml`, and `*.sh`.
- **`SOURCE_URL`:** set `BuildConfig.SOURCE_URL` from the git tag, for example
  `https://<forge>/<owner>/sshovel/tree/v1.2.3`. Untagged builds point to the commit hash.

## 5. Test environment (`test-env/`)

A fake intranet reachable only through the jump host:

```yaml
# test-env/compose.yaml
services:
  jumphost:
    build: ./jumphost            # Alpine + openssh-server; AllowTcpForwarding yes; key-only auth;
                                 # user "tester"; authorized_keys mounted from ./keys/authorized_keys
    ports: ["2222:22"]
    networks: [public, intranet]
  dns:
    image: alpine:3
    command: >
      sh -c "apk add --no-cache dnsmasq &&
             dnsmasq -k --no-resolv --log-queries
             --address=/wiki.corp.test/10.77.0.20
             --address=/api.corp.test/10.77.0.21"
    networks: { intranet: { ipv4_address: 10.77.0.53 } }
  wiki:
    image: nginx:alpine
    networks: { intranet: { ipv4_address: 10.77.0.20 } }
networks:
  public: {}
  intranet:
    internal: true
    ipam: { config: [ { subnet: 10.77.0.0/24 } ] }
```

Emulator profile for manual testing:

- host `10.0.2.2` (the emulator's alias for the development machine), port `2222`, user `tester`
- routes `10.77.0.0/24`
- DNS server `10.77.0.53`, suffix `corp.test`

Success means `http://wiki.corp.test` loads in the emulator's browser, while public sites still load
directly.

## 6. Milestones

Each milestone ends with: all tests green, `./gradlew lint` clean, and a short report covering what
was done, deviations from the spec and why, open risks, and screenshots where the UI changed.

### M0 — Platform spikes (throwaway code in a `spikes/` branch or directory)

Verify every item in `ARCHITECTURE.md` §11 and report the findings. Minimum demos:

1. A Hello-world gomobile AAR with gVisor imported builds and loads on an API 36 emulator. Page
   alignment is verified.
2. A bare `VpnService` + `TileService` toggles from the tile while the app is backgrounded. Record
   the foreground service type that works.
3. `DnsResolver.rawQuery` on the best non-VPN network works while our VPN is up.
4. An ECDSA P-256 Keystore key signs an SSH auth via `ssh.NewSignerFromSigner` against the
   `test-env` jump host.

**Acceptance:** a written report, with a decision recorded for each item and the architecture
updated where reality differed.

### M1 — Go core, headless

Implement `config`, `sshx`, `netstack`, `dnsproxy`, `engine`, and `mobile`. Required tests (all in
Go, with no Android involved):

- **In-process SSH server** (`testutil`): an `x/crypto/ssh` server that handles `direct-tcpip` by
  dialing the requested target, can reject with `Prohibited`, and can run `exec` for route discovery.
- **netstack peer:** a second gVisor stack connected to the engine's stack through a `channel`
  link endpoint pair, acting as "apps". Tests cover TCP echo through the tunnel; RST on
  prohibited/unreachable; half-close; 100 concurrent flows; 50 MB transfer integrity.
- **DNS:**
  - suffix classification
  - reverse-lookup classification
  - pipelining with ID rewriting under concurrency
  - TC truncation and TCP retry
  - AAAA hiding
  - SERVFAIL when the tunnel is down
  - RST on port 853
  - `resolvedOutsideRoutes` flagging
- **Engine:**
  - state transitions
  - backoff timing (with a fake clock)
  - no retry on auth or host-key errors
  - `NetworkChanged` short-circuits the backoff
  - keepalive failure detection
- **Host key:** unverified, match, and mismatch cases; `FetchHostKey` aborts before auth.
- `go vet`, `staticcheck`, and `go test -race` pass.

Licensing groundwork (applies to all code from here on):

- Add `LICENSE` with the verbatim GPL-3.0 text, downloaded from
  `https://www.gnu.org/licenses/gpl-3.0.txt`. Never retype or reconstruct it.
- Add `REUSE.toml`.
- Add SPDX headers to every new file (ARCHITECTURE §12 item 2).
- Get `go-licenses check` passing for `core/`.

**Acceptance:** `go test -race ./...` passes, and `reuse lint` and the Go license check pass. Also provide a `cmd/sshovel-cli` debug tool (Linux, using
a TUN device) that runs the engine against `test-env`, so the core can be exercised without Android.

### M2 — Android skeleton + VPN with a hardcoded profile

App module, theme scaffold (placeholder tokens), `AppContainer`, `TunnelController`,
`SshovelVpnService`, `PlatformBridge`, `NetworkMonitor`, and the ongoing notification. Use a debug-only
hardcoded profile pointing at `test-env` with an imported test key. Also wire up the license tasks
from §4: `collectGoLicenses`, `checkLicenses`, `reuseLint`, and `SOURCE_URL`.

**Acceptance:** On the emulator, `wiki.corp.test` loads in Chrome, and public sites work with no
change in speed. Switching the emulator between Wi-Fi and cellular reconnects automatically.
`./gradlew check` runs and passes the license tasks. Adding a dependency with a forbidden license
(test it on a throwaway branch) fails the build.

### M3 — Keys and host key verification

`KeystoreKeys` (with StrongBox fallback and hardware-backed detection), `ImportedKeyVault`,
`AuthorizedKeyLine`, and `FetchHostKey`, plus the pinning flow. Profiles are persisted in DataStore.

**Acceptance:**

- A generated key authenticates against `test-env`.
- An imported passphrase-protected Ed25519 key works after import, without asking for the
  passphrase again.
- Replacing the jump host's host key produces `HOST_KEY_MISMATCH`, and the tunnel never comes up.
- Instrumented tests cover the vault's encrypt/decrypt round trip.

### M4 — Split DNS and routing options

Upstream DNS via `PlatformBridge.QueryUpstreamDNS`, search domains, excluded routes, app
include/exclude modes, and config validation surfaced in Kotlin.

**Acceptance:** With suffix `corp.test`, intranet names resolve through the tunnel and public names
through the carrier or Wi-Fi resolver (visible in the diagnostics DNS log). *Only selected apps* mode
limits the tunnel to the chosen apps. A validation error blocks saving an overlapping tun subnet.

### M5 — Tile, Always-on, consent, revoke

`TunnelTileService` covering every state in `DESIGN_BRIEF.md` §7, `VpnConsentActivity` with the
explainer, `onRevoke`, Always-on start with the default profile, lockdown ordering, the "Require
unlock" setting, and the tile long-press → app.

**Acceptance:** Every flow in `DESIGN_BRIEF.md` §6 items 2–5 works on the emulator. Enabling Always-on
in system settings connects at boot. Starting another VPN app moves sshovel to
`VPN_REVOKED`.

### M6 — Full UI from the design handoff

Implement all screens in `DESIGN_BRIEF.md` §5 using the Claude Design handoff: tokens → `Theme.kt`
(dynamic color with brand fallback), the component inventory → `ui/components/`, and screens →
`ui/screens/`. ViewModels expose immutable UI state and take events. Business logic doesn't live in
composables.

**Acceptance:**

- Every screen and state from the handoff has a `@Preview` (light and dark).
- Screenshots match the handoff.
- Onboarding, including `requestAddTileService`, works end to end.
- Compose UI tests cover the profile editor validation and the host-key mismatch screen, including
  that it can't be dismissed.
- **Settings → About** shows the GPL Appropriate Legal Notices from ARCHITECTURE §12 item 3:
  - copyright line
  - the free-software / no-warranty statement
  - "View license", which renders the bundled `LICENSE` in-app
  - "Source code", which opens `BuildConfig.SOURCE_URL`
- **"Open-source licenses"** lists every Android and Go dependency with its full license text.
  Go's own license is included.
- If the design handoff doesn't cover these elements, build them from the handoff's existing
  components (list items, top app bar, body text) and note it in the report.

### M7 — Diagnostics, accessibility, polish

The Diagnostics screen (events, DNS, connections) with share/export, the flagged-DNS warnings, and
error catalog strings wired to every error code.

**Acceptance:**

- TalkBack walkthrough of the connect flow announces every state change.
- 200 % font scale shows no clipping on any screen.
- Accessibility Scanner reports no issues on main screens.
- Predictive back works on every sub-screen and sheet.

### M8 — Hardening and release prep

R8/minify rules for gomobile classes, `allowBackup=false`, strict mode in debug, and a log audit (no
secrets). Battery check: an idle connected tunnel for 1 h. Produce a release build signed with a
debug keystore placeholder, and write `docs/RELEASE_NOTES.md` and `docs/SERVER_SETUP.md` (the
recommended `sshd_config` and `authorized_keys` setup).

GPL release compliance:

- Write `CONTRIBUTING.md` with DCO sign-off, the license, and the dependency-license rules.
- Complete `docs/THIRD_PARTY.md` (copied or adapted code and any approved exceptions, or "none").
- Document a reproducible release procedure in `docs/RELEASE.md`:
  1. tag the commit
  2. build from a clean checkout of the tag with the pinned toolchain versions
  3. check that the About screen's source link resolves to that tag
- Optional: add F-Droid-compatible metadata (fastlane `metadata/android/…` structure). F-Droid
  accepts the app as long as it has no proprietary dependencies, which the license checks already
  guarantee.

**Acceptance:** A release APK installs and passes the manual test matrix (§7). The release APK's
About screen shows the correct version, legal notices, and a source link to the matching tag. The
licenses screen matches `go-licenses report` plus the AboutLibraries output. `reuse lint` passes.

## 7. Manual test matrix (run at M5 and M8)

| Scenario | Expected |
|---|---|
| Tile on/off × locked/unlocked × "require unlock" on/off | Matches spec |
| Wi-Fi ↔ cellular while downloading through the tunnel | Reconnecting then On; the download fails cleanly, and new requests work |
| Airplane mode for 2 min, then off | NETWORK_LOST, then auto-reconnect |
| Server `AllowTcpForwarding no` | Connected + FORWARDING_DENIED warning; flows RST |
| Key removed from `authorized_keys` | AUTH_FAILED, no retry loop |
| Host key rotated | HOST_KEY_MISMATCH, no tunnel |
| Private DNS strict mode on | Diagnostics shows no tunneled queries; the UI explains why |
| Another VPN app started | VPN_REVOKED |
| Reboot with Always-on enabled | Connects with the default profile |
| Routed subnet overlaps the local Wi-Fi LAN | Warning shown in the profile editor |

## 8. Definition of done

All milestones accepted; the manual matrix passes; `go test -race ./...`, unit, and instrumented tests
pass; lint is clean; `checkLicenses` and `reuse lint` pass; no TODOs without a linked issue; docs
updated to match what was built.
