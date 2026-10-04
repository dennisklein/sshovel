<!-- SPDX-FileCopyrightText: 2026 Dennis Klein -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Contributing to sshovel

Thanks for helping. Before you change behavior, read `docs/ARCHITECTURE.md` (how it works),
`docs/DESIGN_BRIEF.md` (states, flows, copy) and the design handoff in `docs/design/` (visuals). If a
change has to depart from one of them, update that document in the same change. `CLAUDE.md` has the
commands and the project rules in short form.

## License

sshovel is licensed under the **GNU General Public License v3.0 or later** (`LICENSE`). By
contributing, you agree that your contribution is licensed the same way. There is no contributor
license agreement: you keep your copyright, and "or later" stays possible because every
contribution carries the same terms.

## Developer Certificate of Origin

Every commit must be signed off. A sign-off certifies the
[Developer Certificate of Origin 1.1](https://developercertificate.org/): that you wrote the change,
or otherwise have the right to submit it under the project's license. Add the trailer with
`git commit -s`:

```
Signed-off-by: Your Name <you@example.org>
```

Use your real name and an address you can be reached at. Commits without a sign-off can't be merged.

## Files and headers

Every new source file (Go, Kotlin, Gradle scripts, XML, shell, Markdown outside `docs/`) starts with
SPDX headers in that file type's comment syntax:

<!-- REUSE-IgnoreStart -->
```
// SPDX-FileCopyrightText: 2026 Your Name
// SPDX-License-Identifier: GPL-3.0-or-later
```
<!-- REUSE-IgnoreEnd -->

Files that can't carry a comment (binaries, JSON, generated files) get an entry in `REUSE.toml`.
`reuse lint` must pass; `./gradlew check` runs it.

## Dependencies

Everything shipped in the APK must be compatible with GPL-3.0 (ARCHITECTURE §12):

| Status | Licenses |
|---|---|
| Allowed | Apache-2.0, BSD-2-Clause, BSD-3-Clause, MIT, ISC, Zlib, MPL-2.0, LGPL-2.1-or-later, LGPL-3.0, GPL-3.0(-or-later), GPL-2.0-or-later, OFL-1.1 (fonts), Unicode-DFS/Unicode-3.0 |
| Forbidden | GPL-2.0-only, SSPL, BUSL, Commons Clause, any "non-commercial" or "no-derivatives" license, the JSON License, proprietary SDKs (Google Play Services, Firebase, ML Kit, crash reporters, analytics) |
| Needs the maintainer's approval | AGPL-3.0, and anything not listed above |

- Use only the dependencies listed in `docs/IMPLEMENTATION_PLAN.md` §3. Propose an addition in an
  issue or in the pull request, with what it's for and its license.
- Test-only libraries (JUnit is EPL-1.0) stay in test configurations and never reach the APK.
- `./gradlew checkLicenses` fails on a dependency whose license isn't allowed, on both the Android
  side (AboutLibraries) and the Go side (`go-licenses`). The Open-source licenses screen is
  generated from the same data, so a new dependency shows up there by itself.

## Code from elsewhere

Don't paste code from other projects unless its license is on the allowed list. Keep its original
copyright and SPDX lines (add yours below them if you change it), and list the file in
`docs/THIRD_PARTY.md`.

## Before you open a pull request

```bash
cd core && go test -race ./... && go vet ./... && staticcheck ./...
./gradlew check            # unit tests, lint, checkLicenses, reuseLint, page alignment
./gradlew :app:connectedDebugAndroidTest   # with an emulator or device attached
```

`tools/android-env/run.sh shell` gives you all of the tools (SDK, NDK, emulator, go-licenses,
reuse) in a container, with the test intranet from `test-env/` running.

Some rules reviewers will hold you to:

- Never log, persist in plaintext, or put into config JSON private key bytes, passphrases, or
  Keystore material. Host names and destinations go only to the in-memory Diagnostics buffers, never
  to logcat in release builds (`LogAuditTest` checks the obvious cases).
- Never add a way to accept a changed host key without an explicit action in the profile editor.
- UI: Material 3 components, colors from the theme, text in `strings.xml`, and a light and dark
  `@Preview` for every screen and state.

## Reporting security issues

Please don't open a public issue for a vulnerability. Report it privately to the maintainer
(GitHub's "Report a vulnerability" under the repository's Security tab, where enabled).
