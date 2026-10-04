<!-- SPDX-FileCopyrightText: 2026 Dennis Klein -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Third-party material in this repository

Code or assets copied or adapted from elsewhere (CLAUDE.md, Licensing). Libraries pulled in as
dependencies are listed in the app's "Open-source licenses" screen instead.

| File | Source | License | Changes |
|---|---|---|---|
| `app/src/main/res/drawable/ic_*.xml` except `ic_sshovel*.xml` and `ic_launcher_*.xml` (the icons named in the design handoff §3; each file names its symbol) | Material Symbols Outlined, [google/material-design-icons](https://github.com/google/material-design-icons) `symbols/android/<name>/materialsymbolsoutlined/<name>_24px.xml` (`_fill1_24px.xml` for the `*_filled.xml` variants; `ic_remove_circle_outline.xml` is the symbol `do_not_disturb_on`) | Apache-2.0 (Google LLC) | Removed the AppCompat `android:tint` attribute |
| `app/src/main/res/font/roboto_mono_regular.ttf`, `roboto_mono_medium.ttf` | Roboto Mono, [googlefonts/RobotoMono](https://github.com/googlefonts/RobotoMono) `fonts/ttf/RobotoMono-Regular.ttf`, `RobotoMono-Medium.ttf` (design handoff §1.3) | OFL-1.1 (The Roboto Mono Project Authors; `LICENSES/OFL-1.1.txt`, annotated in `REUSE.toml`) | None; renamed to Android resource names |

The fonts and icons ship in the APK, so their licenses are also shown in Settings → About →
Open-source licenses ("Fonts and icons").

## Copied or adapted code

None. No source code in this repository is copied or adapted from another project; the rows above
are the only third-party files. (ARCHITECTURE §12 allows adapting `xjasonlyu/tun2socks` under MIT;
it wasn't needed: `core/netstack` uses gVisor's own forwarders.)

## Approved license exceptions

None. Every shipped dependency is on the allowed list in ARCHITECTURE §12, as `checkLicenses`
enforces. The one library outside that list, JUnit 4 (EPL-1.0), is test-only
(`testImplementation`, and pulled in by the `androidTestImplementation` test libraries) and never
reaches the APK.
