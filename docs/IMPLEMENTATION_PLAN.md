# sshovel — implementation plan

Audience: Claude Code. License: **GPL-3.0-or-later** (see `ARCHITECTURE.md` §12). Work milestone by milestone and stop for review after each one. Behavior
comes from `ARCHITECTURE.md`, states and copy from `DESIGN_BRIEF.md`, and visuals from the Claude
Design handoff in `docs/design/`.

## 1. Repository layout

```
/
├── CLAUDE.md
├── LICENSE                       verbatim GPL-3.0 text (FSF)
├── LICENSES/GPL-3.0-or-later.txt byte-identical copy of LICENSE, where REUSE looks for it
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
│   ├── errcode/                  error/warning codes shared with Kotlin
│   ├── cmd/sshovel-cli/          Linux debug CLI (TUN) for test-env
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
├── tools/android-env/            Docker toolbox (SDK, NDK, emulator) + scripted milestone acceptance
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
- **Android SDK:** `compileSdk = 37`, `targetSdk = 36`, `minSdk = 36`. compileSdk only sets which
  API headers the build sees; it doesn't change where the app installs or how it behaves at runtime.
  It was 36 until M2, when Compose 1.12 (BOM 2026.08.00+) and Lifecycle 2.11 turned out to require
  37. Lint's `NewApi` check still guards against calling API 37-only methods on 36 devices.
- **ABIs:** `arm64-v8a` and `x86_64` only, matching `gomobile bind -target` (§4). The app sets
  `ndk.abiFilters` so dependencies' 32-bit native libraries aren't packaged either.
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
cd core && go install golang.org/x/mobile/cmd/gobind   # pinned version; gomobile execs it from PATH
PATH="$(go env GOPATH)/bin:$PATH" go tool gomobile bind \
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
  `LOAD` segment alignment below 16 KB. (M2: `verifyCorePageAlignment` checks `core.aar` before
  every build; per-variant `verify<Variant>PageAlignment` tasks check the packaged APKs;
  `verifyPageAlignment` runs them all.) Use `llvm-objdump -p` from the NDK, or run
  `zipalign -c -P 16 -v 4` on the APK. If Go's linker doesn't produce 16 KB alignment by default,
  add `-ldflags="-extldflags=-Wl,-z,max-page-size=16384"`. (M0: with NDK r30 the default output is
  already 16 KB-aligned, so no flag is needed.)

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
- **Tool versions:** build `go-licenses` with the core's Go toolchain
  (`cd core && GOTOOLCHAIN=go1.26.3 go install github.com/google/go-licenses@v1.6.0`). A binary built
  with an older Go can't map the newer standard library to modules and fails every package (M1).
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
    build: { context: ./jumphost, network: host }
                                 # Alpine + openssh-server; AllowTcpForwarding yes; key-only auth;
                                 # user "tester"; authorized_keys mounted from ./keys/authorized_keys
    ports: ["2222:22"]
    networks: [public, intranet]
  dns:
    build: { context: ./dns, network: host }
                                 # Alpine + dnsmasq installed at build time, answering
                                 # wiki.corp.test → 10.77.0.20, api.corp.test → 10.77.0.21
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

Builds use host networking so package downloads work on hosts with systemd-resolved
(`127.0.0.53` isn't reachable from the default bridge). The `dns` image installs dnsmasq at build
time, because at runtime it sits only on the `internal` network, which has no internet access.

Debug builds carry a hardcoded profile for this environment (M2, removed from the UI's reach once
profiles exist in M6). The build generates the client key (`:app:debugTestKey` →
`test-env/keys/debug_client_key`, authorized through `debug_authorized_keys`, both gitignored) and
pins the host key test-env created on first start (`test-env/hostkeys/`), so start test-env once
before building. Deleting `test-env/hostkeys/` rotates the host key; rebuild to pin the new one.

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

Run it with `tools/android-env/run.sh m3`. To replace the host key, the script deletes the files
in `test-env/hostkeys/`; the jump host regenerates them and reloads sshd (M3). The M3 UI for host
keys is minimal: a "Verify server" dialog and trusted/received fingerprints on Home. M6 replaces
it with the handoff's S3/S4 screens.

### M4 — Split DNS and routing options

Upstream DNS via `PlatformBridge.QueryUpstreamDNS`, search domains, excluded routes, app
include/exclude modes, and config validation surfaced in Kotlin.

**Acceptance:** With suffix `corp.test`, intranet names resolve through the tunnel and public names
through the carrier or Wi-Fi resolver (visible in the diagnostics DNS log). *Only selected apps* mode
limits the tunnel to the chosen apps. A validation error blocks saving an overlapping tun subnet.

Run it with `tools/android-env/run.sh m4`. The DNS diagnostics log is the in-memory `DnsLog`
(ARCHITECTURE §5); the M7 Diagnostics screen shows it, and debug builds also dump it with the adb
`dns-log` command. Validation reaches Kotlin as `ValidationIssue`s from `ValidateConfig`;
`ProfileRepository.save` refuses errors. Mapping issues to the handoff's field messages
(`err_cidr_*`, `err_tunnel_overlap`, …) is part of the M6 profile editor.

### M5 — Tile, Always-on, consent, revoke

`TunnelTileService` covering every state in `DESIGN_BRIEF.md` §7, `VpnConsentActivity` with the
explainer, `onRevoke`, Always-on start with the default profile, lockdown ordering, the "Require
unlock" setting, and the tile long-press → app.

**Acceptance:** Every flow in `DESIGN_BRIEF.md` §6 items 2–5 works on the emulator. Enabling Always-on
in system settings connects at boot. Starting another VPN app moves sshovel to
`VPN_REVOKED`.

Run it with `tools/android-env/run.sh m5`. It taps the tile with `cmd statusbar click-tile` (the
same SystemUI path as a finger, M0), accepts Android's VPN dialog through uiautomator, sets
Always-on (and lockdown) through `settings put secure always_on_vpn_*` and reboots, and uses a
throwaway VPN app (`tools/android-env/othervpn`, built by the script, never shipped) for the
revoke. The "Require unlock" setting is stored and enforced by the tile; its UI toggle arrives
with the M6 Settings screen, and the lock-screen behaviour stays a physical-device check (§7).

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

Run it with `tools/android-env/run.sh m6`. It runs `connectedDebugAndroidTest` (the Compose UI
tests in `app/src/androidTest/.../ui`), then installs the app fresh without the debug build's
test-env profile (a `no-seed` marker made with `run-as`) and walks onboarding through uiautomator:
the key is created on the emulator, its `authorized_keys` line (from the debug `key-list`
command) goes into test-env, the server is verified, trusted, and tested with the Go core's
`TestConnection` (added in M6, ARCHITECTURE §6), and the tile is added through Android's own
`requestAddTileService` dialog. It then screenshots the screens in light and dark with wallpaper
colors off (the handoff's brand scheme) through the debug `open` command, forces a host key
change for S4, and checks About and the licenses screen against `go-licenses report`.

Implementation notes (M6):

- Screens live in `ui/screens/<feature>`, components in `ui/components`, navigation in
  `ui/SshovelApp.kt` (type-safe routes, `ui/nav/Routes.kt`).
- The profile editor keeps a string draft (`ProfileDraft`) and maps the Go core's
  `ValidateConfig` issues to fields; a subnet covered by another is a *warning* (it doesn't block
  saving), as `ValidateConfig` reports it (ARCHITECTURE §8), shown with the warning accent.
- The acceptance run never presses back to close the keyboard (with none showing, back leaves
  the onboarding step); it scrolls each field above the bottom actions before tapping it.
- The Diagnostics entry (`troubleshoot`) and the "View diagnostics" actions arrived with the
  Diagnostics screen in M7.

### M7 — Diagnostics, accessibility, polish

The Diagnostics screen (events, DNS, connections) with share/export, the flagged-DNS warnings, and
error catalog strings wired to every error code.

**Acceptance:**

- TalkBack walkthrough of the connect flow announces every state change.
- 200 % font scale shows no clipping on any screen.
- Accessibility Scanner reports no issues on main screens.
- Predictive back works on every sub-screen and sheet.

Run it with `tools/android-env/run.sh m7`. The instrumented tests carry most of the acceptance:
`FontScaleTest` sets the device's font scale to 2 (so dialogs scale too), renders every
`@Preview`, measures each text again at its node's width and fails when the node got less height
than its text needs, a line is cut without an ellipsis, or text runs past the window's edge; `AccessibilityChecksTest`
runs the Accessibility Test Framework, the checks behind Accessibility Scanner, over every screen
preview (test-only `ui-test-junit4-accessibility`, Apache-2.0); `HeroAnnouncementsTest` drives the
hero through the connect flow and checks each announcement and its politeness. A TalkBack
walkthrough by ear stays a device check (§7). On the emulator the script walks DESIGN_BRIEF §6
flow 6 against test-env, whose jump host forwards only to `10.77.0.20:80`, `10.77.0.53:53` and
`10.77.0.21:80` (`PermitOpen`) and whose DNS resolves `git.corp.test` outside the routed subnet:
`https://wiki.corp.test` is refused by server policy, `http://api.corp.test` is unreachable, and
Diagnostics shows each with its reason, app and name (debug `diag-dump`). It then follows the
warning card's "View diagnostics", shares the export, takes screenshots (light, dark, 200 % font)
and does the back gesture on every sub-screen and on a sheet, with a screenshot halfway through.

Implementation notes (M7):

- Diagnostics keeps three in-memory buffers per process (ARCHITECTURE §7): events (Go's
  `Platform.Log` plus the service's own System events), DNS queries, and connections. Open flows
  get live bytes from the new `Engine.FlowsJSON()` only while the Connections tab is visible;
  the app behind a flow comes from `getConnectionOwnerUid`, so the core now reports a failed
  flow before its RST.
- `ErrorCatalogTest` reads `core/errcode/errcode.go` and fails if a Go code has no Kotlin
  constant or no copy, so the catalog stays wired to every code.
- Strict Private DNS (ARCHITECTURE §5, "Known interference") is explained on the DNS tab and
  logged as a DNS warning when the tunnel comes up.

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

Run (M8): `tools/android-env/run.sh m8 [idle minutes]` (default 60). It builds the release APK
with `tools/android-env/release.sh` from a clean checkout of `v<versionName>` (HEAD's tag, or a
scratch tag in a clone that is never pushed) and runs everything with that APK, which has no debug
commands and logs no states: states come from the app's notifications, the TUN interface and the
screen. It walks onboarding, checks About and the license data (the APK's Go list against
`go-licenses report`, its Android list against the AboutLibraries output), then the §7 matrix:
tile off/on, a refused forward (test-env's `PermitOpen`, which sshd answers exactly like
`AllowTcpForwarding no`), Wi-Fi ↔ mobile during a throttled download (`test-env/wiki`), airplane
mode for 2 min, strict Private DNS, the tile on a PIN lock screen with "Require unlock" off and on,
reboot with Always-on, an idle hour with the screen off (CPU from `/proc`, `batterystats`),
another VPN, the key removed, the host key rotated (and recovered through the editor), and a
subnet that overlaps the emulator's Wi-Fi. Last, a logcat audit of the release app's uid, a debug
session counting StrictMode violations, and the instrumented tests.

Implementation notes (M8):

- Release logs name only exception classes; host names, destinations and JSON stay in the
  Diagnostics buffers. `LogAuditTest` fails on a non-debug `Log` call that interpolates anything
  else or passes a throwable.
- StrictMode (`detectAll`, log only) runs in debug builds.
- Release signing reads `sshovel.signing.*` Gradle properties; without them the release build is
  signed with the debug key (the placeholder this milestone asks for).
- Reproducible builds: the Go library is built with `-trimpath` (gomobile's random work directory
  was compiled in), and `dependenciesInfo` is off (an encrypted, per-build blob in the signing
  block; F-Droid rejects it too). Two builds of the same tag gave the same APK hash. The toolbox
  takes `SSHOVEL_NDK` to rebuild with the NDK a release recorded.
- Keys created on a device without TEE or StrongBox get their own create-key note (software
  keystore), as onboarding already did.
- The profile editor warns when a subnet overlaps the current Wi-Fi or Ethernet network
  (ARCHITECTURE §10, §7 matrix); it was missing. The copy is new (DESIGN_BRIEF §8 deviations).

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
