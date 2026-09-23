# sshovel — design brief

Audience: Claude Design. The output will be implemented by Claude Code in Jetpack Compose + Material 3.

## 1. Product in one paragraph

sshovel lets someone on mobile data reach their company or home intranet from any app on their
Android phone. It opens one SSH connection to a jump host they already have access to. Traffic to the
intranet subnets goes through that connection, intranet hostnames resolve correctly, and everything
else (streaming, messaging, the public web) keeps using the normal connection. The whole thing is
toggled from a Quick Settings tile. Opening the app is for setup, checking status, and troubleshooting.

**Name and brand.** The name is always written lowercase: **sshovel**. It's a play on SSH and
sshuttle, and the shovel digs a tunnel into the intranet. Use the shovel as the core motif for the app,
tile, and notification icons. It must stay legible as a 24 dp monochrome silhouette, so keep it simple
(e.g. a spade blade, optionally suggesting a tunnel mouth or a key). Keep the playfulness in the name
and icon; the rest of the UI stays calm and precise.

## 2. Users and design principles

**Primary user:** technically capable (developers, sysadmins, homelab owners). They know what SSH,
public keys, and subnets are, but they want the phone app to be quiet, fast, and obviously correct.

Principles:

1. **Glanceable state.** At any moment it's obvious whether the tunnel is off, connecting, on,
   reconnecting, or failed, and which profile is active. State never relies on color alone; always pair it with an icon and text.
2. **The tile is the main UI.** The app is opened mostly for setup and diagnosis. Optimize the home screen
   for "is it working, and if not, why?"
3. **Trust is visible.** Security-relevant facts are shown plainly: the host key fingerprint,
   whether the private key is hardware-backed, and which apps and subnets are tunneled. Never hide a
   security warning behind a dismissible snackbar.
4. **Technical, not intimidating.** Use real terms (CIDR, fingerprint, `authorized_keys`) with short
   helper text. Monospace only for machine values: IPs, CIDRs, fingerprints, keys, log lines.
5. **Errors give direction.** Every failure says what happened and what to do next (see §8).

## 3. Constraints (hard)

- **Platform:** Android 16 (API 36), phones first. Portrait primary; the layout must not break in
  landscape or on a 600 dp+ width (a centered max-width content column is fine).
- **Components:** only components available in stable `androidx.compose.material3`. Examples: `Scaffold`,
  `TopAppBar` / `LargeTopAppBar` / `MediumTopAppBar`, `ListItem`, `Card` / `ElevatedCard` /
  `OutlinedCard`, `Switch`, `FilterChip` / `InputChip` / `AssistChip`, `SegmentedButton`,
  `OutlinedTextField`, `ModalBottomSheet`, `AlertDialog`, `NavigationBar` (only if needed),
  `FloatingActionButton` / `ExtendedFloatingActionButton`, `SnackbarHost`, `LinearProgressIndicator` /
  `CircularProgressIndicator`, `Badge`, `HorizontalDivider`. Material 3 Expressive components are
  allowed only where they exist in stable Compose Material 3. If you use one, name it explicitly
  in the handoff.
- **Color:** express every color as a Material 3 color role (`primary`, `onPrimary`,
  `primaryContainer`, `secondaryContainer`, `tertiaryContainer`, `error`, `errorContainer`,
  `surface`, `surfaceContainerLow` … `surfaceContainerHighest`, `outline`, `outlineVariant`, etc.).
  Dynamic color (wallpaper-based) is on by default. Also provide a brand fallback scheme generated
  from one seed color of your choice, light and dark. Custom semantic colors are allowed only for the
  connection-state accents (§4), and each needs a light and dark value plus an `on` color.
- **Type:** Material 3 type-scale roles (`displaySmall`, `headlineMedium`, `titleLarge`,
  `titleMedium`, `bodyLarge`, `bodyMedium`, `labelLarge`, `labelMedium`, …). The system font (Roboto /
  Google Sans Flex, per device) is fine. Specify one monospace family for machine values (e.g.
  Roboto Mono or JetBrains Mono, which would be bundled).
- **Icons:** Material Symbols (Outlined or Rounded, pick one style). Name each icon, e.g.
  `vpn_key`, `lan`, `dns`, `fingerprint`, `shield_lock`, `sync_problem`.
- **Layout:** edge-to-edge (enforced on Android 16); content respects system-bar and cutout insets.
  Predictive back is enabled, so sheets, dialogs, and sub-screens must make sense with a back
  gesture preview.
- **Accessibility:** touch targets ≥ 48 dp. Layouts must survive 200 % font scale. Contrast must meet WCAG AA.
  Every state change is announced for TalkBack (describe what's announced). There is no information
  that only a color conveys.
- **Units:** dp for spacing and sizes, sp for text. Use a 4 dp spacing grid.

## 4. Connection state model

One process-wide state, shown identically in the app, tile, and notification:

| State | Meaning | Visual intent |
|---|---|---|
| **Off** | No tunnel | Neutral. Primary action: connect |
| **Connecting** | SSH handshake / auth in progress | Indeterminate progress. Cancel is available |
| **On** | Tunnel up | Clearly positive but calm. Shows profile, uptime, and a live traffic summary |
| **Reconnecting** | Link lost (network change, timeout). Retrying automatically | Warning-ish, not alarming. Shows next retry countdown and a "Retry now" action |
| **Needs attention** | Stopped due to an error that needs the user (auth failed, host key changed, host key unverified, VPN permission revoked) | Uses the `error` / `errorContainer` roles. Shows the error title, one-line explanation, and a primary fix action |

Design a small reusable **state indicator** component (icon + label, e.g. a chip or badge) and a
large **status hero** component used on the home screen. Both must have all five states.

## 5. Screens

For each screen, design all listed states. Content lists are required content, not layout
instructions. You decide the layout.

### 5.1 Home

Purpose: see and control the tunnel and switch profiles.

- **Status hero.** Current state (§4), active profile name, jump host (`user@host:port`), and a primary
  control to connect or disconnect. When On, it also shows uptime, active connections count,
  data in/out, and DNS queries (tunneled vs direct). When Needs attention, it shows the error and
  fix action.
- **Profile list.** Each row shows name, `user@host`, number of routed subnets, and a default-profile
  marker (the tile uses the default). Row tap opens the profile. Selecting a different profile
  while connected asks for confirmation ("Switch to Lab? This disconnects Office.").
- **Entry points** to Keys, Diagnostics, and Settings (top app bar actions or overflow; your choice).
- An "Add profile" action.
- If Always-on VPN is enabled for sshovel in system settings, show a small informational row, and
  indicate "Lockdown" if it's also enabled.

States: no profiles (empty state that starts onboarding), Off, Connecting, On, Reconnecting,
Needs attention (one example each for *auth failed* and *host key changed*).

### 5.2 Onboarding (first run)

A short, skippable, linear flow. Each step can be revisited later from Settings.

1. **Welcome.** One sentence on what sshovel does and what's needed: an SSH server you can log in
   to with a key, and the intranet subnets you want to reach.
2. **Create a key.** Generates a hardware-backed key. Show "Stored in secure hardware" or "StrongBox"
   when true. A secondary option imports an existing key instead.
3. **Install the public key.** Shows the public key (monospace, truncated with expand) with
   **Copy**, **Share**, and **Show QR code** actions. Include the suggested `authorized_keys` line with
   the `restrict,port-forwarding` prefix. Also show a copyable one-liner hint:
   `echo '<key>' >> ~/.ssh/authorized_keys`.
4. **Add your server.** Condensed profile form: name, host, port, user, subnets, intranet DNS server,
   and domain suffixes.
5. **Verify the server.** Host key verification (§5.5). Includes a "Test connection" result
   (success, or a specific error from §8).
6. **Add the tile.** Explains the tile, then triggers the system "Add tile" prompt. Design the
   in-app screen and a success/declined outcome. The system prompt itself isn't designable.
7. **Done.** Offer "Connect now".

### 5.3 Profile editor

Sections (collapsible or separate cards; your call):

- **Server:** name, host, port (default 22), username.
- **Authentication:** key picker (list of keys from §5.6, with a hardware-backed badge) and a
  shortcut to create or import a key.
- **Server identity:** pinned host key algorithm + SHA-256 fingerprint and pinned date. Actions:
  "Verify now" and "Forget pinned key" (the latter requires confirmation and explains the risk).
- **Subnets:** list of CIDRs as chips or rows (monospace), add and remove, inline validation. Add an
  "Excluded subnets" sub-list (collapsed by default). Add a "Discover from server" action that opens §5.8.
- **DNS:**
  - intranet DNS server IP
  - domain suffixes routed to it (chips, e.g. `corp.example`, `internal`)
  - search domains (chips)
  - toggle "Also resolve reverse lookups for routed subnets" (default on)
  - toggle "Hide IPv6 answers for intranet names" (default on; helper text: "Avoids delays when the tunnel is IPv4-only")
- **Apps:** segmented choice of *All apps* / *Only selected apps* / *All except selected*, plus a
  summary ("3 apps") that opens §5.7.
- **Advanced (collapsed):**
  - keepalive interval (seconds)
  - connect timeout
  - tunnel interface subnet (default `10.99.0.0/24`)
  - MTU
  - "Set as default profile"
- **Delete profile** (destructive, with confirmation).

States: new vs. existing; field-level validation errors (bad CIDR, overlapping subnets, tunnel
subnet overlaps a routed subnet, missing DNS server when suffixes are set); unsaved-changes
confirmation on back; read-only banner while this profile is connected ("Disconnect to edit").

### 5.4 Pre-permission explainer

Shown once before Android's system VPN consent dialog appears. It explains that Android will ask for
permission to set up a VPN connection, what sshovel routes, and that no traffic leaves the phone
except to your SSH server. There's one primary action, "Continue". The system dialog itself isn't
designable. Also design the "Permission denied" outcome with "Try again".

### 5.5 Host key verification

- **First use (dialog or full-screen sheet):**
  - server `host:port`
  - key algorithm
  - SHA-256 fingerprint in monospace, grouped for readability, with copy
  - guidance: "Compare this with the output of `ssh-keygen -lf /etc/ssh/ssh_host_ecdsa_key.pub` on the server."
  - actions: "Trust this server" / "Cancel"
- **Mismatch (full-screen, not dismissible by tapping outside):**
  - a clear warning
  - old vs. new fingerprint side by side
  - explanation (possible interception, or the server was reinstalled)
  - the only actions are "Disconnect" (primary) and a low-emphasis "Review in profile". There's no one-tap accept.

### 5.6 Keys

- A list of keys. Each row shows:
  - name
  - type (ECDSA P-256 hardware-backed / imported Ed25519 / imported RSA)
  - badge: *Hardware-backed*, *StrongBox*, or *Encrypted on device*
  - created date
  - the profiles using it
- **Key detail:** public key (monospace, expandable), SHA-256 fingerprint, actions Copy / Share /
  Show QR / Rename / Delete. Delete is blocked while a profile uses the key, with an explanation.
- **Create key** sheet: name field. Show a note that the private key can never leave this device.
- **Import key** sheet:
  - paste text or pick a file
  - optional passphrase
  - result: success, or errors for wrong passphrase / unsupported format
- **QR sheet:** a large QR of the public key line, plus a note to scan it from a computer.

### 5.7 App picker

A searchable list of launchable apps (icon, label, package name in small monospace), with
checkboxes. Includes a "Show system apps" toggle, a selected-count summary, and "Select all" /
"Clear". The header reflects the mode ("Tunnel only these apps" vs. "Don't tunnel these apps").

### 5.8 Discover subnets (bottom sheet)

- **Loading:** "Reading routes from the server…"
- **Results:** a checklist of discovered CIDRs with interface names. Default and link-local routes are
  pre-unchecked and labeled.
- **Actions:** "Add selected" and "Cancel".
- **Errors:** the server doesn't allow commands; timeout.

### 5.9 Diagnostics

- **Tabs or segmented control:** *Events* / *DNS* / *Connections*.
- **Events:** timestamped log lines (monospace). Filter by level and component (SSH, DNS, Tunnel,
  System). Each has a severity icon.
- **DNS:** recent queries showing name, route (tunnel/direct badge), result (IPs or error code),
  and latency. A warning badge appears when an intranet name resolved to an IP outside the routed
  subnets ("Resolved outside routed subnets, so traffic won't use the tunnel").
- **Connections:** active flows showing destination `ip:port`, app (if known), bytes, and age. Also
  shows failures with a reason, e.g. *Refused by server policy* or *Destination unreachable*.
- **Actions:** Pause, Clear, Copy all, Share as text file.
- **Empty states:** tunnel off, or no events yet.

### 5.10 Settings

- Default profile (used by the tile and Always-on VPN)
- "Require unlock to use the tile" toggle
- Link to system VPN settings (to set Always-on / Lockdown), with explanation
- Notification settings link
- Theme: System / Light / Dark; toggle "Use wallpaper colors"
- Re-run onboarding
- About: version, open-source licenses

## 6. Key flows to storyboard

1. First run → onboarding → connect → On.
2. Tile tap when off (VPN permission already granted) → Connecting → On. Show the tile,
   notification, and home hero in sync.
3. Tile tap when VPN permission is not yet granted → app opens the explainer (§5.4) → system dialog → connected.
4. On → switch from Wi-Fi to mobile data → Reconnecting → On.
5. Connect → host key changed → Needs attention → mismatch screen.
6. User opens `wiki.corp.example` in the browser but it fails → Diagnostics shows the DNS
   entry and the connection failure reason.

## 7. System surfaces

These are rendered by Android, so design within their limits.

- **Quick Settings tile.** You design the tile icon (24 dp monochrome vector) and specify
  label and subtitle text for each state:
  - Off: label "sshovel", subtitle "Off", inactive
  - Connecting: subtitle "Connecting…", active
  - On: subtitle = profile name, active
  - Reconnecting: subtitle "Reconnecting…", active
  - Needs attention: subtitle "Tap to fix", inactive
  - No profile: subtitle "Set up", inactive

  One icon is required; optionally provide a variant for the Needs attention state. A long-press opens
  the app's Home screen.
- **Ongoing notification** (low importance, while not Off). It has a small icon (24 dp monochrome),
  title, text, and actions. Specify content per state, for example:
  - On: title "Connected to Office", text "3 subnets · 12 active connections", action "Disconnect"
  - Reconnecting: action "Retry now"
  - Needs attention: a normal-importance notification with the fix action
- **App icon:** adaptive icon (foreground + background layers) plus a monochrome layer for themed
  icons.

## 8. Voice and copy

Plain verbs, sentence case, no apologies, no exclamation marks. Actions keep the same name
throughout a flow: a "Trust this server" button produces the confirmation "Server trusted".
Machine values (hosts, IPs, CIDRs, fingerprints) always appear in monospace.

Error catalog (title — body — primary action). Use these strings; refine wording if you like, but
keep the meaning.

| Code | Title | Body | Action |
|---|---|---|---|
| `AUTH_FAILED` | Key not accepted | `{user}@{host}` rejected the key "{key}". Add its public key to `~/.ssh/authorized_keys` on the server. | Show public key |
| `HOST_UNREACHABLE` | Can't reach server | No response from `{host}:{port}`. Check the address and that you're online. | Retry |
| `HOST_KEY_UNVERIFIED` | Verify server first | This server's identity hasn't been confirmed yet. | Verify server |
| `HOST_KEY_MISMATCH` | Server identity changed | The host key for `{host}` doesn't match the one you trusted. The connection was blocked. | Disconnect |
| `FORWARDING_DENIED` | Forwarding not allowed | The server refused to forward connections. Ask the admin to enable `AllowTcpForwarding`. | View diagnostics |
| `DNS_UNREACHABLE` | Intranet DNS not answering | `{dns}` isn't answering through the tunnel. Intranet names won't resolve. | View diagnostics |
| `NETWORK_LOST` | No network | sshovel will reconnect when you're back online. | — |
| `VPN_REVOKED` | Disconnected by another VPN | Android allows one VPN at a time, and another app took over. | Reconnect |
| `VPN_PERMISSION` | VPN permission needed | Android needs your permission before sshovel can route traffic. | Continue |
| `KEY_UNAVAILABLE` | Key unavailable | The key "{key}" can't be used. It may have been deleted or invalidated by a security change. | Choose key |

`FORWARDING_DENIED` and `DNS_UNREACHABLE` are warnings shown while On, not stopping errors.

## 9. Handoff deliverables (what engineering needs from you)

1. Every screen and state in §5, light and dark, plus the storyboards in §6.
2. A **component inventory**. For each reusable component (state indicator, status hero, profile
   row, CIDR chip/row, fingerprint block, key row, log line, DNS entry row, etc.), give the
   Material 3 Compose component(s) it's built from, its variants and states, and dimensions in dp.
3. **Tokens:**
   - seed color and the full generated light/dark fallback schemes (role → hex)
   - the connection-state accent colors (light/dark + `on` colors)
   - type-scale overrides, if any
   - the monospace family
   - spacing scale
   - shape scale (corner sizes by component)
4. **Icon list** (Material Symbols names and style), plus the custom tile, notification, and
   adaptive app icons as SVG.
5. **Motion notes**, only for meaningful transitions: state changes in the status hero, and sheet and
   dialog entry. Specify durations and easing using Material motion tokens. Respect "Remove animations".
6. **TalkBack notes**: announcement text for each state change, and content descriptions for icon-only buttons.
7. **Final copy** for all strings, including empty states and errors.

## 10. Out of scope

Tablet-specific two-pane layouts, Wear OS, widgets, password-based SSH auth, multi-hop jump hosts,
UDP forwarding other than DNS, onboarding for server administrators, and any marketing surfaces.
