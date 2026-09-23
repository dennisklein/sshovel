# sshovel — engineering handoff

Target: Android 16 (API 36), Jetpack Compose, stable `androidx.compose.material3` (1.3.x/1.4.x). No Material 3 Expressive-only components are used.
Frame: 412 × 915 dp phone, edge-to-edge. Every screen exists in light and dark.

## 0. Files

| File | Contents | Screen ids |
|---|---|---|
| `sshovel 1 Home.dc.html` | §5.1 Home | H0 empty · H1 Off · H2 Connecting · H3 On (+Always-on/Lockdown) · H4 Reconnecting · H5 AUTH_FAILED · H6 HOST_KEY_MISMATCH · H7 switch-profile dialog |
| `sshovel 2 Onboarding.dc.html` | §5.2 | O1 Welcome · O2 Create key · O3 Install public key · O4 Add server · O5 Verify server · O6a test OK · O6b test failed · O7 Add tile · O8 tile added/declined · O9 Done |
| `sshovel 3 Profile and security.dc.html` | §5.3–5.5 | P1 new · P2 existing · P3 validation · P4 DNS/apps/advanced (+tunnel overlap) · P5 unsaved · P6 read-only · P7 forget key · P8 delete · V1 VPN explainer · V2 denied · S3 host key first use · S4 mismatch |
| `sshovel 4 Keys apps subnets.dc.html` | §5.6–5.8 | K1 list · K2 detail · K3 delete blocked · K4 create sheet · K5 import sheet · K5b import errors · K6 QR sheet · A1 app picker · D1 loading · D2 results · D3 errors |
| `sshovel 5 Diagnostics and settings.dc.html` | §5.9–5.10 | G1 Events · G2 DNS · G3 Connections (+menu) · G4 empty · G5 Settings |
| `sshovel 6 System and storyboards.dc.html` | §4, §6, §7 | icons, state indicator, tile, notifications, storyboards SB1–SB6 |
| `handoff/Color.kt` | fallback schemes + state colors, ready to paste | |
| `handoff/strings.xml` | all final copy | |
| `handoff/icons/*.svg` | tile/notification + adaptive icon layers | |

Where a frame is labelled “light / dark” with two different states (O8, K5b, D3, G4), both states exist in both themes; they were split only to save canvas space.

## 1. Tokens

### 1.1 Color
- **Dynamic color on by default** (`dynamicLightColorScheme` / `dynamicDarkColorScheme`). Settings → “Use wallpaper colors” off ⇒ brand fallback.
- **Seed:** `#005EA8`, `SchemeTonalSpot`, contrast 0 (Material Color Utilities). Full role → hex lists are in `Color.kt` (`SshovelLightScheme`, `SshovelDarkScheme`). Summary:

| Role | Light | Dark |
|---|---|---|
| primary / onPrimary | #3A608F / #FFFFFF | #A4C9FE / #00315C |
| primaryContainer / on | #D3E3FF / #1F4876 | #1F4876 / #D3E3FF |
| secondary / onSecondary | #545F71 / #FFFFFF | #BCC7DB / #263141 |
| secondaryContainer / on | #D8E3F8 / #3C4758 | #3C4758 / #D8E3F8 |
| tertiary / onTertiary | #6D5677 / #FFFFFF | #D9BDE3 / #3C2946 |
| tertiaryContainer / on | #F5D9FF / #543F5E | #543F5E / #F5D9FF |
| error / onError | #BA1A1A / #FFFFFF | #FFB4AB / #690005 |
| errorContainer / on | #FFDAD6 / #93000A | #93000A / #FFDAD6 |
| surface / onSurface | #F8F9FF / #191C20 | #111318 / #E1E2E9 |
| onSurfaceVariant | #43474E | #C3C6CF |
| surfaceContainerLowest | #FFFFFF | #0C0E13 |
| surfaceContainerLow | #F2F3FA | #191C20 |
| surfaceContainer | #EDEDF4 | #1D2024 |
| surfaceContainerHigh | #E7E8EE | #272A2F |
| surfaceContainerHighest | #E1E2E9 | #32353A |
| surfaceDim / surfaceBright | #D9DAE0 / #F8F9FF | #111318 / #37393E |
| outline / outlineVariant | #73777F / #C3C6CF | #8D9199 / #43474E |
| inverseSurface / inverseOnSurface / inversePrimary | #2E3035 / #EFF0F7 / #A4C9FE | #E1E2E9 / #2E3035 / #3A608F |
| scrim | #000000 | #000000 |

### 1.2 Connection-state accents (custom, via `LocalStateColors`)
Source colors harmonized to the seed (`blend = true`). With dynamic color, re-harmonize against `colorScheme.primary` at runtime.

| Token | Light | Dark |
|---|---|---|
| stateOn / onStateOn | #006D43 / #FFFFFF | #76DAA1 / #003920 |
| stateOnContainer / onStateOnContainer | #92F7BC / #002111 | #005231 / #92F7BC |
| stateReconnecting / onStateReconnecting | #974806 / #FFFFFF | #FFB68A / #522300 |
| stateReconnectingContainer / on… | #FFDBC8 / #321300 | #743400 / #FFDBC8 |

State → roles (indicator container / content, icon):

| State | Container | Content | Icon (Material Symbols Outlined) |
|---|---|---|---|
| Off | surfaceContainerHighest | onSurfaceVariant | `vpn_key_off` |
| Connecting | primaryContainer | onPrimaryContainer | `CircularProgressIndicator` 16/18 dp, stroke 2 dp |
| On | stateOnContainer | onStateOnContainer | `vpn_lock` (FILL 1) |
| Reconnecting | stateReconnectingContainer | onStateReconnectingContainer | `sync_problem` |
| Needs attention | errorContainer (on plain surfaces) / error (on errorContainer hero) | onErrorContainer / onError | `error` (FILL 1); `gpp_bad` for host-key errors |

Every state also has a text label; color is never the only signal. All pairs above meet WCAG AA (≥ 4.5:1) as body text.

### 1.3 Type
Material 3 default type scale, **no overrides**. System font (Roboto / Google Sans Flex per device).
Monospace: **Roboto Mono** (bundled, `res/font/roboto_mono_regular.ttf`, `roboto_mono_medium.ttf`). Define `MonoFamily` and derive mono styles by `copy(fontFamily = MonoFamily)` from the role in use:

| Usage | Role |
|---|---|
| App bar title | titleLarge (22/28) |
| Onboarding / full-screen headline | headlineMedium (28/36) |
| Hero profile name | headlineMedium |
| Dialog title | headlineSmall (24/32) |
| Section header | titleSmall (14/20, primary) |
| Error / result title in cards | titleMedium (16/24) |
| List headline | bodyLarge |
| Supporting text, body | bodyMedium |
| Helper / caption | bodySmall |
| Buttons, chips, tabs | labelLarge |
| Stat labels | labelMedium |
| Badges (“Default”, route) | labelSmall / labelMedium |
| Fingerprint (large block) | titleMedium + Mono, letterSpacing 0.5sp |
| Hosts, CIDRs, IPs in lists | bodyLarge / bodyMedium + Mono |
| Log lines, package names | bodySmall + Mono |

### 1.4 Spacing (4 dp grid)
`4, 8, 12, 16, 20, 24, 32, 40, 48, 64`.
Screen horizontal padding 16 dp (lists, editor) or 24 dp (onboarding, full-screen explainers). Card inner padding 16–20 dp. Gap between cards 12–16 dp. List item horizontal 16 dp. Bottom action bar 16 dp vertical / 24 dp horizontal. Content max width 600 dp, centered, on widths ≥ 600 dp and in landscape.

### 1.5 Shape
| Token | Size | Used by |
|---|---|---|
| extraSmall | 4 dp | OutlinedTextField, Snackbar, DropdownMenu, “Default” label |
| small | 8 dp | chips, state indicator, key/route badges (6 dp at 24 dp height) |
| medium | 12 dp | Cards, fingerprint/key blocks, banners, app-icon placeholders |
| large | 16 dp | Status hero, grouped result lists, FAB |
| extraLarge | 28 dp | AlertDialog, ModalBottomSheet (top corners), SearchBar |
| full | 50 % | Buttons, Switch, tile mock, avatars |

### 1.6 Elevation
Tonal only (surfaceContainer* roles). Shadow only on FAB (level 3) and menus (level 2).

## 2. Component inventory

| Component | Built from | Variants / states | Dimensions |
|---|---|---|---|
| **StateIndicator** | `Surface(shape=small, color)` + `Row(Icon, Text)`; Connecting uses `CircularProgressIndicator` | 5 states × size Large/Small; `onErrorSurface` flag inverts Needs attention | Large 40 dp h, icon 20, padding 12/16, labelLarge. Small 32 dp h, icon 18, padding 8/12 |
| **StatusHero** | `Card(shape=large)` containing StateIndicator(Large), texts, `LinearProgressIndicator`, stats grid, `Button`/`FilledTonalButton`/`OutlinedButton`/`TextButton` | Off: Filled “Connect”. Connecting: indeterminate progress + step text + Outlined “Cancel”. On: 2×2 stats + Tonal “Disconnect”. Reconnecting: countdown + Filled “Retry now” + Outlined “Disconnect”. Needs attention: container errorContainer, error title/body, error-filled fix button + Text “Retry” (omitted for HOST_KEY_MISMATCH) | Full width − 32; padding 20; gap 16; stats cell padding 12, 1 dp outlineVariant separators |
| **AlwaysOnRow** | `OutlinedCard` + `ListItem` (`shield_lock`) | Always-on / Always-on · Lockdown | min 56 dp |
| **ProfileRow** | `ListItem` with leading `RadioButton` (own 48 dp target) and trailing `chevron_right`; “Default” is a `Surface(extraSmall, border outline)` label | selected / unselected, default / not; disabled while Connecting | 72 dp two-line |
| **SubnetRow** | `ListItem` leading `lan`, headline mono, trailing `IconButton(remove_circle_outline)` | normal / error (leading `error` icon in error color + supporting text) / read-only (no trailing) | 56 dp; 72 dp with error |
| **CidrInput** | `OutlinedTextField` + `FilledTonalIconButton(add)` | empty, typing, error (`isError`, trailing error icon, supportingText) | 56 dp + 48 dp button |
| **Chip field** (suffixes, search domains, onboarding subnets) | `FlowRow` of `InputChip` (mono label, trailing `close`) + `AssistChip(add)` | — | 32 dp chips, 8 dp gaps |
| **FingerprintBlock** | `Surface(medium, surfaceContainerHighest)` + header row + 4-column grid of 4-char groups + `IconButton(content_copy)` | Large (17sp, onboarding/dialog), Compact (15sp, editor), Pair (two `OutlinedCard`s side by side in S4, 2 groups/row, 13sp; “Received now” card has 2 dp error border) | padding 16 |
| **PublicKeyBlock** | `Surface(medium)` + mono text (maxLines 3, expandable via `IconButton(expand_more/less)`) + `AssistChip` row Copy/Share/Show QR code | collapsed / expanded; with/without `restrict,port-forwarding` prefix | — |
| **KeyRow** | `ListItem` three-line: leading 40 dp icon disc, headline name, supporting type + KeyBadge + “date · profiles”, trailing chevron | hardware / imported | ~88 dp |
| **KeyBadge** | `Surface(small)` + Icon + Text | StrongBox (`memory`, secondaryContainer) · Hardware-backed (`verified_user`, secondaryContainer) · Encrypted on device (`lock`, outlined) | 24 dp (rows) / 32 dp (detail) |
| **CheckResultRow** (connection test) | `ListItem` leading icon | passed (`check_circle` stateOn), running (`CircularProgressIndicator` 24 dp), failed (errorContainer inset card with error copy + action), not run (`radio_button_unchecked`) | 64 dp |
| **LogLine** | `Row(Icon 18, Column(meta, message))` | info (`info`, onSurfaceVariant), warning (`warning`, row bg stateReconnectingContainer), error (`error`, row bg errorContainer) | padding 8/16, bodySmall mono |
| **DnsEntryRow** | `Column` + `RouteBadge` + result | ok / error rcode / outside-routed-subnets warning (container stateReconnectingContainer + warning text) | padding 12/16 |
| **RouteBadge** | `Surface(6 dp)` + Icon + Text | Tunnel (`vpn_lock`, primaryContainer) / Direct (`public`, outlined) | 24 dp |
| **ConnectionRow** | `ListItem`-like Row | active (`swap_vert`) / failed (`block`, errorContainer, reason title + next step) | min 64 dp |
| **AppRow** | `ListItem` leading 40 dp app icon, supporting package (mono bodySmall), trailing `Checkbox`; whole row `toggleable` | checked / unchecked | 72 dp |
| **DiscoveredRouteRow** | `ListItem` leading `Checkbox`, headline CIDR mono, supporting interface / label | normal, pre-unchecked with label (default route `warning`, link-local `info`), already added (checked + disabled) | 64 dp |
| **OnboardingScaffold** | `Scaffold` + `TopAppBar`(back, “Step n of 7”, TextButton “Skip”) + `LinearProgressIndicator(progress)` + bottom Row(TextButton, Button) | step 1 has no back/progress | — |
| **SettingsRow** | `ListItem` leading icon, optional trailing `Switch` / `open_in_new` | — | 56/72/88 dp |
| Dialogs | `AlertDialog` (icon optional) | H7, P5, P7, P8, K3, S3, rename, default-profile picker | width 312–340 dp |
| Sheets | `ModalBottomSheet` + `BottomSheetDefaults.DragHandle` | K4, K5, K6, D1–D3 | — |
| Others | `ExtendedFloatingActionButton` (Add profile, Create key), `SnackbarHost`, `PrimaryTabRow` + `Tab` with `Badge`, `SingleChoiceSegmentedButtonRow`, `SearchBar` (A1), `DropdownMenu`, `FilterChip` (G1), `Switch` (with check thumb icon where helpful), `HorizontalDivider` | | |

`NavigationBar` is not used.

## 3. Icons
Style: **Material Symbols Outlined**, weight 400, grade 0, optical size 24; FILL 1 only where noted.

`key` (Keys entry, show public key), `troubleshoot` (Diagnostics entry), `settings`, `add`, `arrow_back`, `close`, `chevron_right`, `expand_more`, `expand_less`, `power_settings_new` (Connect/Disconnect), `refresh` (Retry/Try again), `vpn_key_off` (Off, permission), `vpn_lock` (On, Tunnel route, explainer), `vpn_key` (status bar is system), `sync_problem` (Reconnecting), `error`, `gpp_bad` (host key changed), `policy` (review server identity), `verified_user` (trust, hardware-backed, trusted fingerprint), `fingerprint`, `content_copy`, `share`, `qr_code_2`, `memory` (StrongBox), `lock` (encrypted, read-only, require unlock), `block` (not exportable, excluded, refused), `dns`, `lan`, `public` (Direct, everything else), `shield_lock` (Always-on, “nothing else leaves”), `swap_horiz` (switch profile), `travel_explore` (discover), `remove_circle_outline`, `file_open` (import), `edit`, `delete`, `delete_sweep`, `link` (key in use), `warning`, `info`, `help`, `check`, `check_circle`, `cancel`, `radio_button_unchecked`, `terminal`, `timer_off`, `apps`, `search`, `pause`, `play_arrow`, `more_vert`, `description`, `receipt_long`, `swap_vert`, `arrow_upward`, `arrow_downward`, `arrow_drop_down`, `open_in_new`, `notifications`, `palette`, `restart_alt`, `star`, `wifi`.

Custom (in `handoff/icons/`, convert with Android Studio’s Vector Asset tool):
- `ic_sshovel.svg` — 24 dp monochrome tile + notification small icon (single even-odd path).
- `ic_sshovel_attention.svg` — Needs-attention variant (“!” instead of keyhole).
- `ic_launcher_background.svg`, `ic_launcher_foreground.svg`, `ic_launcher_monochrome.svg` — adaptive icon, 108 dp canvas; glyph is 48 dp, inside the 66 dp safe zone.

## 4. Motion
Use `MaterialTheme.motionScheme` where available; otherwise these M3 tokens. All transitions check `Settings.Global.ANIMATOR_DURATION_SCALE`/“Remove animations”: when 0, swap instantly (no crossfade), keep indeterminate indicators static-segment.

| Transition | Spec |
|---|---|
| Status hero state change | Container color: `animateColorAsState`, **medium2 300 ms, emphasized** (`cubic-bezier(0.2,0,0,1)`). Indicator + detail block: `AnimatedContent` fade-through — out 100 ms `emphasizedAccelerate`, in 200 ms `emphasizedDecelerate` with 8 dp upward slide. Size change: `animateContentSize(spring(dampingRatio=1, stiffness=Medium))`. Stats numbers do not animate. |
| Connecting → On | same as above; no celebratory motion |
| Reconnecting countdown | text-only update each second, no animation |
| AlertDialog enter/exit | platform default (fade + scale 0.8→1, 150 ms `emphasizedDecelerate` in / 75 ms out) |
| ModalBottomSheet | default: slide up, **long2 500 ms emphasizedDecelerate** in; 200 ms `emphasizedAccelerate` out; scrim fade |
| Predictive back (sheets, dialogs, sub-screens) | built-in `PredictiveBackHandler` progress: sheet scales to 0.9 and shifts with the gesture; sub-screens use the system cross-activity/nav animation |
| Read-only banner (P6) | `AnimatedVisibility` expand/shrink vertically, medium2 300 ms |
| Snackbars | default |

## 5. TalkBack
Announcements use `LiveRegionMode.Polite` on the hero’s state text (and `stateDescription` on the tile). Assertive only for Needs attention.

| Change | Announcement |
|---|---|
| → Connecting | “Connecting to {profile}” |
| → On | “Connected to {profile}” |
| → Reconnecting | “Connection lost. Reconnecting to {profile}. Next retry in {n} seconds.” (countdown itself is not announced) |
| → On after reconnect | “Reconnected to {profile}” |
| → Off (user) | “Disconnected” |
| → Needs attention | “Needs attention. {error title}. {primary action} button available.” (assertive) |
| On-state warning appears | “Warning: {warning title}” |
| Test step completes | “{check} passed” / “{check} failed: {error title}” |
| Snackbars | read automatically (“Server trusted”, “Copied”, …) |
| Tile | label “sshovel”, stateDescription = subtitle, role Switch |

Hero semantics: merge the card into one node: “Office, alex at jump dot corp dot example port 22, On for 1 hour 24 minutes, 12 active connections, 48.2 megabytes in, 3.1 megabytes out, 214 tunneled and 1,902 direct DNS queries.” Fingerprints: `contentDescription` spells groups char-by-char with pauses between groups.

Content descriptions for icon-only buttons:

| Icon | Description |
|---|---|
| `key` (home bar) | “Keys” |
| `troubleshoot` | “Diagnostics” |
| `settings` | “Settings” |
| `arrow_back` | “Back” (default) |
| `close` (new profile, explainer) | “Close” |
| `content_copy` (fingerprint) | “Copy fingerprint” |
| `content_copy` (one-liner) | “Copy command” |
| `expand_more` / `expand_less` on key | “Show full key” / “Collapse key” |
| `remove_circle_outline` | “Remove {cidr}” |
| `add` (CIDR) | “Add subnet” |
| InputChip close | “Remove {value}” |
| `file_open` (Keys bar) | “Import key” |
| `edit` / `delete` (key detail) | “Rename key” / “Delete key” |
| `pause` / `play_arrow` | “Pause updates” / “Resume updates” |
| `share` (diagnostics) | “Share as text file” |
| `more_vert` | “More options” |
| `visibility` / `visibility_off` | “Show passphrase” / “Hide passphrase” |
| Expand Excluded/Advanced | “Excluded subnets, collapsed/expanded” |
| Radio in ProfileRow | “Use {profile}” |

Font scale 200 %: all rows use min heights, never fixed; hero stats grid becomes 1 column below 360 dp of available width; segmented buttons wrap to two lines; bottom action bars stack buttons vertically (primary on top) when they don’t fit.

## 6. Behavior notes
- One process-wide `StateFlow<TunnelState>` drives Home, tile (`TileService.requestListeningState`) and the foreground-service notification.
- Profile switching while not Off → H7 dialog; editing a connected profile → P6.
- Host-key mismatch never offers accept; the only path to a new key is Forget pinned key (P7) → verify (S3).
- `FORWARDING_DENIED` and `DNS_UNREACHABLE` are On-state warnings (notification text + an inline warning `Card` below the hero with “View diagnostics”); they do not stop the tunnel.
- Final copy: `handoff/strings.xml`.
