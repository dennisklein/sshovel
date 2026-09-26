# CLAUDE.md — sshovel

sshovel is an Android 16+ split-tunnel VPN that forwards intranet traffic over SSH (sshuttle-style).
It has a Go core (`core/`, via gomobile) and a Kotlin + Jetpack Compose + Material 3 app (`app/`).
License: **GPL-3.0-or-later**.

## Specs (read before changing behavior)

- `docs/ARCHITECTURE.md`: how it works. This is the source of truth for behavior and the Go↔Kotlin contract.
- `docs/IMPLEMENTATION_PLAN.md`: milestones, dependencies, and acceptance criteria.
- `docs/DESIGN_BRIEF.md`: states, flows, copy, and the error catalog.
- `docs/design/`: Claude Design handoff. This is the source of truth for visuals, tokens, and components.

If the code must deviate from a spec, update the spec in the same change and call it out in the
milestone report.

## Commands

```bash
# Go core
cd core && go test -race ./... && go vet ./... && staticcheck ./...
# Linux debug CLI against test-env (see test-env/README.md)
sudo test-env/cli-netns.sh setup && sudo test-env/cli-netns.sh run -profile … -key … up
# gomobile execs gobind from PATH; go install inside core/ installs the version pinned in go.mod
cd core && go install golang.org/x/mobile/cmd/gobind && PATH="$(go env GOPATH)/bin:$PATH" \
    go tool gomobile bind -target=android/arm64,android/amd64 -androidapi 26 \
    -javapkg=com.github.dennisklein.sshovel.core -o ../app/libs/core.aar ./mobile

# Android (buildGoCore runs automatically before preBuild)
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
./gradlew lint
./gradlew verifyPageAlignment
./gradlew checkLicenses collectGoLicenses reuseLint   # also run by ./gradlew check
reuse lint                                           # REUSE/SPDX compliance
# go-licenses must be built with the core's toolchain:
#   cd core && GOTOOLCHAIN=go1.26.3 go install github.com/google/go-licenses@v1.6.0
cd core && GOOS=android GOARCH=arm64 go-licenses check ./mobile --ignore github.com/dennisklein/sshovel \
    --allowed_licenses=Apache-2.0,BSD-2-Clause,BSD-3-Clause,MIT,ISC,MPL-2.0

# Fake intranet for manual testing (emulator reaches it at 10.0.2.2:2222)
docker compose -f test-env/compose.yaml up --build
# Toolbox (SDK, NDK, emulator, go-licenses, reuse) + test-env + milestone acceptance on the emulator
tools/android-env/run.sh m2        # results in tools/android-env/out/summary.txt
tools/android-env/run.sh shell     # interactive shell in the toolbox
```

## Rules

- **Work in milestones.** Stop at the end of each one with a short report (done / deviations / risks /
  screenshots). Don't start the next milestone unprompted.
- **Secrets.** Never log, persist in plaintext, or put into config JSON any private key bytes,
  passphrases, or Keystore material. Imported key bytes cross the Go boundary only as the dedicated
  `[]byte` argument, and are zeroed after parsing. The one exception is `mobile.ImportKey`'s
  result, the passphrase-free key, which goes straight into the vault and is zeroed on both sides.
- **Host keys.** Never add a code path that accepts a changed host key without an explicit user
  action in the profile editor.
- **gomobile boundary.** `core/mobile` exposes only gomobile-safe types. Complex data crosses as
  JSON matching the schema in ARCHITECTURE §8. Keep business logic out of `mobile/`.
- **Dependencies.** Use only those listed in IMPLEMENTATION_PLAN §3. Justify any addition in the
  milestone report. gVisor comes from its Go branch (`gvisor.dev/gvisor@go`).
- **Licensing (GPL-3.0-or-later; details in ARCHITECTURE §12):**
<!-- REUSE-IgnoreStart -->
  - Every new source file starts with SPDX headers:
    `SPDX-FileCopyrightText: 2026 Dennis Klein` and
    `SPDX-License-Identifier: GPL-3.0-or-later`.
<!-- REUSE-IgnoreEnd -->
  - Only add dependencies whose license is on the §12 allowed list. Never add proprietary SDKs
    (Play Services, Firebase, analytics, crash reporters). Test-only deps like JUnit (EPL) must stay
    in test configurations.
  - Don't paste code from elsewhere unless its license is allowed. Keep its original copyright/SPDX
    lines and record it in `docs/THIRD_PARTY.md`.
  - `LICENSE` must be the verbatim FSF text, downloaded, never regenerated or edited.
  - Don't remove or weaken the About screen's legal notices, license viewer, or source link.
- **Compose:**
  - Material 3 components only.
  - Colors come from `MaterialTheme.colorScheme` roles, plus the state accents defined in `ui/theme`.
    Never hardcode hex in screens.
  - Composables are stateless where possible; ViewModels hold state.
  - Every screen/state gets a `@Preview` in light and dark.
  - Edge-to-edge with correct insets.
  - Support predictive back.
- **Strings.** All user-facing text goes in `strings.xml`, using the copy from DESIGN_BRIEF §8 and the
  design handoff.
- **Tests.**
  - New Go logic needs tests that use the in-process sshd, netstack peer, and fake DNS in
    `core/internal/testutil`.
  - Run `go test -race`.
  - Don't mock gVisor or x/crypto/ssh; use the real ones in-process.
- **Android specifics:**
  - minSdk/targetSdk 36, compileSdk 37.
  - Protect the SSH socket via `Platform.Protect`.
  - Use `DnsResolver.rawQuery` on the underlying network for direct DNS.
  - Never call `addAllowedApplication` and `addDisallowedApplication` on the same builder.
