# sshovel — architecture

Audience: Claude Code. Read together with `IMPLEMENTATION_PLAN.md` (how and in what order) and
`DESIGN_BRIEF.md` (states, behavior, copy). Licensing requirements are in §12.

## 1. Goals and non-goals

**Goals**

- Phone-wide split-tunnel access to intranet subnets over one SSH connection (sshuttle semantics).
- Public-key auth only. Default keys are generated in and never leave Android Keystore.
- Split DNS: intranet suffixes resolve over the tunnel, and everything else resolves via the underlying network.
- Quick Settings tile toggle, ongoing notification, and Always-on VPN support.
- Zero server-side install: stock OpenSSH `sshd` with `AllowTcpForwarding yes`.

**Non-goals (v1)**

- UDP forwarding other than DNS
- ICMP (ping)
- IPv6 inside the tunnel (see §10)
- Multi-hop / ProxyJump
- Password or keyboard-interactive auth
- Per-connection routing by hostname

## 2. Component overview

```
┌──────────────────────────── Android app (Kotlin) ─────────────────────────────┐
│  Compose UI ──► ViewModels ──► Repositories (DataStore) ──► TunnelController  │
│                                                         ▲                     │
│  TunnelTileService ─────────────────────────────────────┤  observes StateFlow │
│  Notification ──────────────────────────────────────────┘                     │
│                                                                               │
│  SshovelVpnService (VpnService) ── establishes TUN, owns Engine lifecycle    │
│  NetworkMonitor (best non-VPN network)                                        │
│  KeystoreKeys (ECDSA P-256 signing)    ImportedKeyVault (AES-GCM wrapped)     │
│  PlatformBridge implements Go `mobile.Platform` callbacks                     │
└──────────────────────────────────┬────────────────────────────────────────────┘
                                   │ gomobile bind (core.aar)
┌──────────────────────────────────▼──────── Go core ───────────────────────────┐
│  mobile/   thin gomobile-safe API (Engine, Platform, helpers)                 │
│  engine/   state machine, reconnect/backoff, stats                            │
│  netstack/ gVisor stack on TUN fd; TCP forwarder; UDP/53 forwarder           │
│  sshx/     dial (protected socket), auth, host-key pinning, keepalive,        │
│            direct-tcpip dialer, route discovery                               │
│  dnsproxy/ split DNS, DNS-over-TCP pool to intranet resolver, truncation      │
│  config/   profile JSON schema + validation (shared with Kotlin)              │
└───────────────────────────────────────────────────────────────────────────────┘
                                   │ one TCP socket, protect()ed
                                   ▼
                    sshd (jump host) ──► intranet hosts, intranet DNS (tcp/53)
```

## 3. Routing (VpnService.Builder)

Configured once per connection, after SSH has authenticated successfully (see §7):

```kotlin
Builder()
    .setSession(profile.name)
    .setMtu(profile.mtu)                            // default 1500
    .addAddress(tunAddr, tunPrefix)                 // default 10.99.0.1/24
    .apply { profile.routes.forEach { addRoute(it.addr, it.prefix) } }
    .apply { profile.excludedRoutes.forEach { excludeRoute(IpPrefix(it)) } }
    .addDnsServer(dnsVirtualIp)                     // default 10.99.0.53 (inside tun subnet)
    .apply { profile.searchDomains.forEach { addSearchDomain(it) } }
    .apply { applyAppMode(profile.apps) }           // allowed XOR disallowed, never both
    .setMetered(false)                              // meteredness follows underlying network
    .setUnderlyingNetworks(arrayOf(currentUnderlying))
    .setConfigureIntent(openAppPendingIntent)
    .establish()
```

Rules:

- **Only the listed routes enter the TUN.** All other traffic uses the underlying network via the kernel and never reaches the app.
- **Allowed and disallowed apps are mutually exclusive.** Calling `addAllowedApplication` and
  `addDisallowedApplication` together throws. The modes are *All apps* (neither is called),
  *Only selected* (allowed), and *All except selected* (disallowed).
- **Validation** (in `config/`, mirrored in the Kotlin form):
  - CIDRs must be canonical
  - the tun subnet must not overlap any route
  - the DNS virtual IP must lie inside the tun subnet
  - routes must not overlap each other; warn and suggest a merge
- **Network changes:** call `setUnderlyingNetworks` again whenever the underlying network changes.

## 4. TCP forwarding (netstack)

- The gVisor stack is built on the TUN fd via `link/fdbased`. Enable promiscuous mode and spoofing on
  the NIC, and install a default route to the NIC so the stack accepts packets for any destination.
- `tcp.NewForwarder(stack, rcvWnd=0 /*default*/, maxInFlight=1024, handler)`:
  1. Read the destination `ip:port` from `ForwarderRequest.ID()`.
  2. **Connect-then-accept:** open an SSH `direct-tcpip` channel to the destination *before*
     completing the local handshake, with a timeout (`profile.connectTimeoutSec`, default 10 s).
  3. On failure, call `r.Complete(true)` to send an RST to the app. Classify the error for diagnostics:
     `ssh.OpenChannelError` with `Reason == ssh.Prohibited` → `FORWARDING_DENIED`;
     `ConnectionFailed` → `DEST_UNREACHABLE`; timeout → `DEST_TIMEOUT`.
  4. On success, `CreateEndpoint` → `gonet.NewTCPConn` → bidirectional copy with half-close
     propagation (`CloseWrite` both ways). Count bytes for stats.
- **Special destination: the DNS virtual IP.**
  - TCP/53 is handled locally by `dnsproxy` (length-prefixed messages).
  - TCP/853 gets an immediate RST, so Android's opportunistic Private DNS falls back to port 53 fast.
- **While Reconnecting:** new SYNs are RST'd immediately, and existing flows are closed when the SSH
  connection dies.
- **Non-DNS UDP and ICMP are dropped.** Count them in stats as `droppedUdp` / `droppedIcmp`.

## 5. DNS (split DNS)

Android sends **all** DNS lookups from VPN-covered apps to the VPN's DNS server, so `dnsproxy` is
the resolver for everything, not just intranet names.

**Ingress.** Traffic arrives as UDP/53 and TCP/53 to `dnsVirtualIp`, via the netstack UDP forwarder
and the special-case TCP handler above.

**Classification.** Parse the query with `miekg/dns`. Use the first question only. The query is
**tunnel-bound** if:

- the QNAME equals or is under any `profile.dns.suffixes` (case-insensitive, FQDN-normalized), or
- `reverseLookups` is on and the QNAME is an `in-addr.arpa` name inside a routed IPv4 subnet.

Everything else is **direct**.

**Tunnel path.**

- A pool of 1–2 persistent `direct-tcpip` channels to `profile.dns.server:53`.
- Pipeline queries (RFC 7766 length-prefix framing). Rewrite message IDs to unique pool IDs, keep a
  map to restore the original ID and route the answer back to the correct client. Per-query timeout
  is 4 s. On channel error, reopen and retry once.
- If `hideAAAA` is on and QTYPE is AAAA, answer NODATA locally (NOERROR, empty answer, SOA optional).
  If the tunnel and resolver are down, answer SERVFAIL.

**Direct path.**

- Call `Platform.QueryUpstreamDNS(query)`. Kotlin implements this with `DnsResolver.rawQuery` on the
  **current underlying network**, tracked by `NetworkMonitor` (see §7). This respects the underlying
  network's own Private DNS settings and never re-enters the VPN.

**Truncation.** When replying over UDP, get the client's max size (EDNS0 UDP size, else 512). If the
response is larger, use `msg.Truncate(size)`, which sets TC. The client then retries over TCP/53, which
we also serve.

**Diagnostics.** Record `{name, qtype, route, rcode, answers, latencyMs, ts}` in a ring buffer of 500
entries. If a tunnel-bound answer contains an A record outside all routed subnets, flag it as
`resolvedOutsideRoutes`.

**Caching.** None in v1. Android's resolver caches per network, so don't add a cache unless profiling shows a need.

**Known interference (show in the UI, don't try to defeat it):**

- Private DNS in *strict* (hostname) mode bypasses our resolver.
- Apps with built-in DNS-over-HTTPS (e.g. browser Secure DNS) bypass it.

## 6. SSH layer (`sshx/`)

**Dial**

- `net.Dialer{Timeout, KeepAlive: 30s, Control: protect}`. `protect` calls
  `Platform.Protect(fd)` through `syscall.RawConn.Control`. Always protect, even when the server
  IP isn't in a routed subnet.
- Then `ssh.NewClientConn` → `ssh.NewClient`. Use the library's default algorithms.

**Auth: Keystore key (default)**

- Kotlin generates the key:
  ```kotlin
  KeyGenParameterSpec.Builder(alias, PURPOSE_SIGN)
      .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
      .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
      .setIsStrongBoxBacked(true)   // retry without StrongBox on StrongBoxUnavailableException
      .build()
  ```
  Don't require user authentication on the key, or the tile couldn't connect while the phone is
  locked. The optional "require unlock" setting is enforced in the tile instead (§7).
- Go implements `crypto.Signer`:
  - `Public()` returns the `*ecdsa.PublicKey` parsed (`x509.ParsePKIXPublicKey`) from the PKIX bytes
    Kotlin passes in the config.
  - `Sign(_, digest, _)` returns `Platform.SignDigest(alias, digest)`. Kotlin signs with
    `Signature.getInstance("NONEwithECDSA")` and returns the ASN.1 DER signature.
- Wrap it with `ssh.NewSignerFromSigner`. The library hashes the data and converts the DER ECDSA
  signature into SSH wire format. Add a unit test that proves this round-trips against an
  in-process server.

**Auth: imported key**

- Formats: OpenSSH private key (Ed25519, ECDSA, RSA). PEM PKCS#1/PKCS#8 is optional.
- Kotlin stores the key bytes encrypted with an AES-256-GCM Keystore key (`ImportedKeyVault`). The
  passphrase is used only once at import. The key is re-encrypted without a passphrase under the
  Keystore key, because the tile can't prompt for one.
- At connect, the decrypted bytes are passed to `Engine.Start` as a separate `[]byte` argument
  (never inside config JSON) and parsed with `ssh.ParseRawPrivateKey`. Zero the slice after parsing.

**Host key pinning**

- `HostKeyCallback` compares `ssh.FingerprintSHA256(key)` and the key type with the profile's
  pinned value:
  - no pin → `HOST_KEY_UNVERIFIED`
  - mismatch → `HOST_KEY_MISMATCH`
  - neither error is retried
- `mobile.FetchHostKey(configJSON)` dials, captures the host key in the callback, and aborts with a
  sentinel error before authenticating. It returns `{type, fingerprintSHA256, authorizedKeyLine}` for
  the verification UI.

**Keepalive**

- Every `profile.keepaliveSec` (default 20 s), send
  `SendRequest("keepalive@openssh.com", true, nil)`, with a deadline of 2 × interval. Failure → Reconnecting.

**Reconnect**

- Exponential backoff 1 s → 30 s with ±20 % jitter, resetting after 60 s of stable connection.
- `Engine.NetworkChanged()` cancels the current backoff and reconnects immediately.
- Auth and host-key errors stop reconnecting and move to NeedsAttention.
- The TUN stays established across reconnects.

**Route discovery**

- `mobile.DiscoverRoutes(configJSON, privateKey)` opens a session channel and runs
  `ip -4 route show`, falling back to `netstat -rn`.
- Parse the output into `{cidr, dev, isDefault, isLinkLocal}` entries.
- If the server denies exec or the command fails, return `ROUTE_DISCOVERY_UNAVAILABLE`.

**Recommended `authorized_keys` line** (shown in UI):
`restrict,port-forwarding ecdsa-sha2-nistp256 AAAA… sshovel@<device>`

## 7. Lifecycle, state machine, and Android integration

### State machine (Go `engine/`, mirrored as a Kotlin sealed type)

```
Off ──connect──► Connecting ──ssh ok──► [TUN established] ──► On
Connecting ──auth/hostkey error──► NeedsAttention
Connecting ──transient error──► Reconnecting
On ──keepalive fail / NetworkChanged / conn closed──► Reconnecting ──ok──► On
Reconnecting ──auth/hostkey error──► NeedsAttention
any ──disconnect──► Disconnecting ──► Off
any ──onRevoke()──► NeedsAttention(VPN_REVOKED)
```

- **Connect SSH before establishing the TUN.** Auth and host-key failures then never disturb
  routing. When Always-on *lockdown* is active, establish the TUN first so the system doesn't
  treat the VPN as failed. Check via `VpnService.isLockdownEnabled()`.
- **Stats** come from `Engine.StatsJSON()`, polled every 1 s while the UI or notification is
  visible, otherwise every 10 s.

Stats fields: `uptimeSec`, `bytesIn`, `bytesOut`, `activeFlows`, `dnsTunneled`, `dnsDirect`,
`droppedUdp`, `droppedIcmp`, `lastError`.

### Kotlin side

- **`TunnelController`** (application-scoped singleton) exposes `state: StateFlow<TunnelState>` and
  `connect(profileId)` / `disconnect()`. The service, tile, notification, and UI all go through it.
- **`SshovelVpnService`**
  - Actions: `ACTION_CONNECT(profileId)` and `ACTION_DISCONNECT`.
  - When the system starts it for Always-on (intent action `VpnService.SERVICE_INTERFACE` or null
    intent), connect the default profile.
  - Implements `onRevoke()`.
  - Posts the ongoing notification and calls `startForeground` (see the M0 spike on service type).
  - Passes the TUN fd to Go with `ParcelFileDescriptor.detachFd()`. Go owns and closes it.
- **`NetworkMonitor`**
  - Uses `ConnectivityManager.registerBestMatchingNetworkCallback` with a request for
    `NET_CAPABILITY_INTERNET` + `NET_CAPABILITY_NOT_VPN`.
  - Tracks `currentUnderlying: Network?`.
  - On change, calls `Builder.setUnderlyingNetworks` (via `setUnderlyingNetworks` on the service) and
    `Engine.NetworkChanged()`.
  - On loss, the state becomes Reconnecting with `NETWORK_LOST`.
- **`TunnelTileService`**
  - Manifest meta-data: `ACTIVE_TILE=true` and `TOGGLEABLE_TILE=true`.
  - Update the tile via `TileService.requestListeningState` whenever the state changes; set label,
    subtitle, and state per `DESIGN_BRIEF.md` §7.
  - `onClick`:
    1. If "require unlock" is on and `isLocked`, wrap the action in `unlockAndRun { … }`.
    2. If there's no default profile, open the app.
    3. If `VpnService.prepare(context) != null`, call
       `startActivityAndCollapse(PendingIntent)` to open `VpnConsentActivity`. This activity shows
       the explainer, then the system consent, then connects.
    4. Otherwise, toggle.
  - Long-press opens the app via an activity with the `QS_TILE_PREFERENCES` intent filter.
- **Onboarding tile prompt:** `StatusBarManager.requestAddTileService(...)`.
- **Notification channels**
  - `tunnel_status`: low importance, ongoing, with Disconnect / Retry now actions.
  - `tunnel_alerts`: default importance, for NeedsAttention.
- **Diagnostics log:** an in-memory ring buffer (2000 events) fed by `Platform.Log`. It's never
  persisted unless the user shares or exports it.

## 8. Go ↔ Kotlin contract (`core/mobile`)

gomobile only exports:

- signed integers, `float64`, `bool`, `string`, `[]byte`
- structs/interfaces built from those types
- functions or methods with an optional trailing `error` return

Complex data therefore crosses the boundary as JSON strings, validated on both sides.

```go
package mobile

// Implemented in Kotlin (PlatformBridge). Calls may block the calling goroutine.
type Platform interface {
    Protect(fd int32) bool
    SignDigest(keyAlias string, digest []byte) ([]byte, error) // DER ECDSA signature
    QueryUpstreamDNS(query []byte) ([]byte, error)             // raw DNS wire format
    OnState(stateJSON string)                                  // {"state":"on","code":"","detail":""}
    Log(level int32, component string, message string)
    OnDnsEvent(eventJSON string)
    OnFlowEvent(eventJSON string)
}

type Engine struct{ /* unexported */ }

func NewEngine(p Platform) *Engine
func (e *Engine) Start(tunFd int32, configJSON string, importedKey []byte) error // non-blocking; progress via OnState
func (e *Engine) AttachTun(tunFd int32) error   // lockdown / establish-after-auth ordering
func (e *Engine) Stop()
func (e *Engine) NetworkChanged()
func (e *Engine) RetryNow()
func (e *Engine) StatsJSON() string

func FetchHostKey(configJSON string, importedKey []byte) (string, error) // {"type","fingerprint","line"}
func DiscoverRoutes(configJSON string, importedKey []byte) (string, error) // [{"cidr","dev","isDefault","isLinkLocal"}]
func AuthorizedKeyLine(pkixPublicKey []byte, comment string) (string, error)
func ValidateConfig(configJSON string) string // "" or JSON list of {field, code}
func Version() string
```

`Start` may be called with `tunFd = -1`, in which case SSH connects first. Once Kotlin sees the
state `sshReady`, it establishes the TUN and calls `AttachTun`. This is the normal (non-lockdown)
ordering from §7.

### Profile JSON (single schema; Kotlin `@Serializable` data classes mirror it)

```json
{
  "id": "uuid",
  "name": "Office",
  "server": { "host": "jump.example.com", "port": 22, "user": "alice" },
  "auth": { "kind": "keystore", "alias": "sshovel-key-1", "publicKeyPkix": "base64" },
  "hostKey": { "type": "ecdsa-sha2-nistp256", "fingerprint": "SHA256:…", "pinnedAt": "2026-09-23T10:00:00Z" },
  "routes": ["10.0.0.0/8", "172.16.0.0/12"],
  "excludedRoutes": [],
  "dns": {
    "server": "10.1.0.53",
    "suffixes": ["corp.example", "internal"],
    "searchDomains": ["corp.example"],
    "reverseLookups": true,
    "hideAAAA": true
  },
  "apps": { "mode": "all", "packages": [] },
  "tun": { "cidr": "10.99.0.0/24", "dnsVirtualIp": "10.99.0.53", "mtu": 1500 },
  "keepaliveSec": 20,
  "connectTimeoutSec": 10
}
```

- `auth.kind` is `"keystore"` or `"imported"`. For imported keys, `alias` names the vault entry.
- `apps.mode` is `"all"`, `"include"`, or `"exclude"`.

Error and warning codes (used in `OnState` and in the UI catalog):

- **Errors:** `AUTH_FAILED`, `HOST_UNREACHABLE`, `HOST_KEY_UNVERIFIED`, `HOST_KEY_MISMATCH`,
  `NETWORK_LOST`, `VPN_REVOKED`, `VPN_PERMISSION`, `KEY_UNAVAILABLE`, `INTERNAL`.
- **Warnings:** `FORWARDING_DENIED`, `DNS_UNREACHABLE`, `ROUTE_DISCOVERY_UNAVAILABLE`.

## 9. Security model

- **Keystore keys** are non-exportable and hardware-backed where available. The app shows whether
  they are.
- **Imported keys** exist in plaintext only in memory, briefly, during connect and route discovery.
- **Host keys** are pinned. A mismatch is a hard failure with no one-tap override.
- **Logging:**
  - Never log private key material, passphrases, or full config JSON.
  - DNS names and destinations appear only in the in-memory diagnostics buffer.
- **No telemetry.** No analytics. There are no network calls other than to the configured SSH server
  and the underlying DNS.
- `android:allowBackup="false"`, since pinned host keys and the vault shouldn't be restored onto a
  different device. Profiles without keys can be exported manually in a later version.

## 10. Known limitations (document in-app where relevant)

- No UDP (except DNS) or ICMP through the tunnel. QUIC-capable apps fall back to TCP automatically.
- IPv4-only tunnel in v1. IPv6 is a v1.1 candidate: add a ULA TUN address and IPv6 routes. netstack
  and `direct-tcpip` already support IPv6 destinations.
- Strict Private DNS and app-level DNS-over-HTTPS bypass split DNS.
- Every tunneled TCP connection is an SSH channel on one TCP connection. Throughput is bounded by that
  connection, and head-of-line blocking can occur on lossy links.
- If a routed subnet overlaps the local Wi-Fi LAN, local devices in that range become unreachable for
  covered apps. The UI warns about this when the overlap is detectable.

## 11. Platform facts to verify early (Milestone 0)

These are believed correct but must be confirmed on a real Android 16 device or emulator before
building on them:

1. **Foreground service type** for the VPN service on API 36: `systemExempted` (documented for VPN
   apps) vs. `specialUse`. Also check the required permissions and whether `startForeground` from
   `onStartCommand` works when the service is started from a tile click.
2. **Tile start path:** starting `SshovelVpnService` from `TileService.onClick` works with the app
   in the background, both when consent is already granted and when it isn't.
3. **`VpnService` manifest `exported` value** that keeps the app listed under Settings → VPN for
   Always-on while staying protected by `BIND_VPN_SERVICE`.
4. **`DnsResolver.rawQuery` on the underlying `Network`** is not captured by our own VPN.
5. **gVisor on gomobile:** use gVisor's Go-compatible branch (`go get gvisor.dev/gvisor@go`; the
   default branch is Bazel-oriented). The `.so` files in `core.aar` must be **16 KB page-aligned**.
6. **`ssh.NewSignerFromSigner` with a Kotlin-backed ECDSA signer** authenticates against OpenSSH 9.x.

## 12. Licensing and compliance

sshovel is licensed **GPL-3.0-or-later**. The copyright holder is a placeholder,
`<Copyright holder>`, until the owner fills it in.

### Dependency compatibility

Every shipped dependency must use a license that can be combined into a GPL-3.0 work.

| Status | Licenses |
|---|---|
| Allowed | Apache-2.0, BSD-2-Clause, BSD-3-Clause, MIT, ISC, Zlib, MPL-2.0, LGPL-2.1-or-later, LGPL-3.0, GPL-3.0(-or-later), GPL-2.0-or-later, OFL-1.1 (fonts), Unicode-DFS/Unicode-3.0 |
| Forbidden | GPL-2.0-only, SSPL, BUSL, Commons Clause, any "non-commercial" or "no-derivatives" license, the JSON License, proprietary SDKs (Google Play Services, Firebase, ML Kit, crash reporters, analytics) |
| Needs owner approval | AGPL-3.0 (compatible, but adds network-use obligations) and anything not listed above |

Current stack, all allowed:

| Component | License |
|---|---|
| Go runtime/stdlib, `x/crypto`, `x/mobile`, `miekg/dns` | BSD-3-Clause |
| gVisor | Apache-2.0 |
| AndroidX, Compose, Material 3, Kotlin, kotlinx, ZXing, AboutLibraries, Material Symbols | Apache-2.0 |
| Monospace font | Apache-2.0 or OFL-1.1 |

Test-only tooling that isn't shipped in the APK is out of scope: the Docker test environment
(nginx, dnsmasq) and build tools like `go-licenses` and `reuse`.

### Obligations the app and repo must meet

1. **License text.** Keep the verbatim GPL-3.0 text in `LICENSE` at the repo root, and ship it in
   the app so the in-app screen can display it.
2. **Per-file notices.** Every source file (Go, Kotlin, Gradle scripts, XML resources, shell) starts
   with SPDX headers:
   ```
   // SPDX-FileCopyrightText: 2026 <Copyright holder>
   // SPDX-License-Identifier: GPL-3.0-or-later
   ```
   Use the comment syntax of each file type. Files that can't carry a header (binary assets,
   generated files) are covered by `REUSE.toml`. The repo follows the REUSE specification and passes
   `reuse lint`.
3. **Appropriate Legal Notices (GPL-3.0 §0, §5(d)).** The interactive UI must show them in
   Settings → About:
   - app name, version, and copyright line
   - the statement "sshovel is free software under the GNU GPL v3 or later. It comes with ABSOLUTELY
     NO WARRANTY."
   - a link to view the full license (rendered in-app from the bundled text, not only a web link)
   - a "Source code" link to the public repository
4. **Corresponding Source (GPL-3.0 §6).** Every distributed binary (Play, F-Droid, GitHub release)
   is built from a public, tagged commit. The About screen's source link points to the exact tag
   for that build (`BuildConfig.SOURCE_URL`, set by Gradle from the version tag). The Go module
   versions are pinned in `go.sum` so the build is reproducible from that tag.
5. **Third-party notices.** Include the license text and any `NOTICE` file of every shipped
   dependency, for both the Android and Go sides. Show them in Settings → About → Open-source
   licenses.
   - Android dependencies: the AboutLibraries Gradle plugin generates the metadata, which is
     rendered with its Compose UI (styled with our theme).
   - Go dependencies: `go-licenses` collects them from the `core/mobile` package graph at build time,
     with `GOOS=android GOARCH=arm64`. The output is merged into the same screen.
   - Go's own `$(go env GOROOT)/LICENSE` is added explicitly, since the runtime is statically linked
     into the `.so` and tooling may not report it.
6. **Copied code.** Code copied or adapted from other projects keeps its original copyright and
   license header (SPDX lines for both). The file must be listed in `docs/THIRD_PARTY.md`, and its
   license must be on the allowed list. Code from `xjasonlyu/tun2socks` (MIT) may be adapted under
   these rules.
7. **Contributions.** Contributions are accepted under the Developer Certificate of Origin
   (`Signed-off-by:` trailer). There is no CLA, so "or later" relicensing remains possible under the
   FSF's future versions.

### Enforcement in the build

- **`checkLicenses` (Gradle):** fails the build on any dependency whose license isn't on the
  allowed list. Use the AboutLibraries strict mode / allowed-license list for Android, and
  `go-licenses check ./mobile --allowed_licenses=<list>` for Go. Unknown licenses fail until the
  owner approves them in `docs/THIRD_PARTY.md`.
- **`reuse lint`:** must pass. If `reuse` isn't available, a small script that checks for SPDX
  headers is an acceptable fallback.
- **Runs:** both checks run in the `check` task and before every release build.
