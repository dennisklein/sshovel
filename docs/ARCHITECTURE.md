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
    .addAddress(tunAddr, tunPrefix)                 // default 198.18.0.1/24
    .apply { profile.routes.forEach { addRoute(it.addr, it.prefix) } }
    .apply { profile.excludedRoutes.forEach { excludeRoute(IpPrefix(it)) } }
    .addDnsServer(dnsVirtualIp)                     // default 198.18.0.53 (inside tun subnet)
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
  - the DNS virtual IP must lie inside the tun subnet (not its network, broadcast, or TUN address)
  - routes must not overlap each other; warn and suggest a merge
- **Default tun subnet** is `198.18.0.0/24` (RFC 2544 benchmarking range). It sits outside the
  private ranges that intranets route, so the common `10.0.0.0/8` or `172.16.0.0/12` routes don't
  collide with it (M1: the earlier `10.99.0.0/24` default overlapped the §8 example profile).
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
- **Other addresses in the tun subnet** get an immediate RST; nothing lives there.
- **While Reconnecting:** new SYNs are RST'd immediately, and existing flows are closed when the SSH
  connection dies.
- **Non-DNS UDP is refused:** the UDP forwarder declines it, so gVisor answers with ICMP port
  unreachable and QUIC falls back to TCP at once instead of timing out. Counted as `droppedUdp`.
- **ICMP is dropped before gVisor sees it** (a filter on the link endpoint). Because the NIC is
  promiscuous, gVisor would otherwise answer echo requests for every address, making unreachable
  hosts look alive. Counted as `droppedIcmp`. Non-IPv4 packets are dropped the same way.
- **Flow events:** every forwarded connection emits `open` and `close` (with byte counts and
  duration), or `fail` with its reason, via `Platform.OnFlowEvent`.

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

**Diagnostics.** Every answered query emits `{name, qtype, route, rcode, answers, latencyMs, ts,
resolvedOutsideRoutes, error}` via `Platform.OnDnsEvent`; Kotlin keeps the last 500 in a ring
buffer. If a tunnel-bound answer contains an A record outside all routed subnets, flag it as
`resolvedOutsideRoutes`.

**Health.** A failed tunnel-bound query (channel error after the retry, or timeout) raises the
`DNS_UNREACHABLE` warning; the next successful one clears it.

**Caching.** None in v1. Android's resolver caches per network, so don't add a cache unless profiling shows a need.

**Known interference (show in the UI, don't try to defeat it):**

- Private DNS in *strict* (hostname) mode bypasses our resolver.
- Apps with built-in DNS-over-HTTPS (e.g. browser Secure DNS) bypass it.

## 6. SSH layer (`sshx/`)

**Dial**

- A host name is resolved with an A query through `Platform.QueryUpstreamDNS`, so the lookup
  never enters the VPN (during a reconnect the TUN is still up) and doesn't depend on Go's resolver
  on Android.
- `net.Dialer{Timeout, KeepAlive: 30s, Control: protect}`. `protect` calls
  `Platform.Protect(fd)` through `syscall.RawConn.Control`. Always protect, even when the server
  IP isn't in a routed subnet.
- Then `ssh.NewClientConn` → `ssh.NewClient`. Use the library's default algorithms, except that a
  pinned host key's algorithm is requested first, so a server with several host keys presents the
  pinned one. The whole dial (resolve, connect, handshake, auth) is bounded by
  `connectTimeoutSec`.
- Progress steps for the Connecting UI: `resolving` → `identity` (TCP connect and key exchange) →
  `auth` (host key accepted) → `tunnel` (reported by the engine).

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
- `mobile.FetchHostKey(platform, configJSON)` dials, captures the host key in the callback, and
  aborts the handshake before authenticating. It returns `{type, fingerprint, line}` (`line` is
  `"<type> <base64>"`) for the verification UI. It needs no key.

**Keepalive**

- Every `profile.keepaliveSec` (default 20 s), send
  `SendRequest("keepalive@openssh.com", true, nil)`, with a deadline of 2 × interval. Failure → Reconnecting.

**Reconnect**

- Exponential backoff 1 s → 30 s with ±20 % jitter, resetting after 60 s of stable connection.
- `Engine.NetworkChanged()` cancels the current backoff and reconnects immediately.
- Auth and host-key errors stop reconnecting and move to NeedsAttention.
- The TUN stays established across reconnects.

**Route discovery**

- `mobile.DiscoverRoutes(platform, configJSON, importedKey)` connects with the pinned host key
  (unpinned → `HOST_KEY_UNVERIFIED`) and runs, until one yields routes: `ip -4 route show`,
  `netstat -rn` (Linux and BSD/macOS formats), `cat /proc/net/route` (servers without iproute2 or
  net-tools; byte order detected from the mask).
- Parse the output into `{cidr, dev, isDefault, isLinkLocal}` entries: canonical, deduplicated,
  sorted, loopback dropped; `unreachable`/`blackhole`/`local`… routes skipped.
- If the server denies exec or no command yields routes, return `ROUTE_DISCOVERY_UNAVAILABLE`.

**Recommended `authorized_keys` line** (shown in UI):
`restrict,port-forwarding ecdsa-sha2-nistp256 AAAA… sshovel@<device>`

## 7. Lifecycle, state machine, and Android integration

### State machine (Go `engine/`, mirrored as a Kotlin sealed type)

```
Off ──connect──► Connecting ──ssh ok──► SshReady ──AttachTun──► On   (no TUN fd at Start)
Off ──connect──► Connecting ──ssh ok──────────────────────────► On   (lockdown: TUN fd at Start)
Connecting ──auth/hostkey/key error──► NeedsAttention
Connecting ──transient error──► Reconnecting
On ──keepalive fail / NetworkChanged / conn closed──► Reconnecting ──ok──► On
Reconnecting ──auth/hostkey/key error──► NeedsAttention ──RetryNow──► Connecting
any ──disconnect──► Disconnecting ──► Off
any ──onRevoke()──► NeedsAttention(VPN_REVOKED)
```

Permanent errors (never retried automatically): `AUTH_FAILED`, `HOST_KEY_UNVERIFIED`,
`HOST_KEY_MISMATCH`, `KEY_UNAVAILABLE`, `INTERNAL`. Everything else is `HOST_UNREACHABLE` and
retried with backoff. `NetworkChanged` during a dial or backoff restarts the dial at once and resets
the attempt counter; while On it drops the connection (bound to the old network) and redials
without waiting.

- **Connect SSH before establishing the TUN.** Auth and host-key failures then never disturb
  routing. When Always-on *lockdown* is active, establish the TUN first so the system doesn't
  treat the VPN as failed. Check via `VpnService.isLockdownEnabled()`.
- **Stats** come from `Engine.StatsJSON()`, polled every 1 s while the UI or notification is
  visible, otherwise every 10 s.

Stats fields: `uptimeSec` (since the last transition to On; 0 otherwise), `bytesIn` (to apps),
`bytesOut` (from apps), `activeFlows`, `dnsTunneled`, `dnsDirect`, `droppedUdp`, `droppedIcmp`,
`lastError` (last error code seen).

### Kotlin side

- **`TunnelController`** (application-scoped singleton) exposes `state: StateFlow<TunnelState>` and
  `connect(profileId)` / `disconnect()`. The service, tile, notification, and UI all go through it.
- **`SshovelVpnService`**
  - Actions: `ACTION_CONNECT(profileId)` and `ACTION_DISCONNECT`.
  - When the system starts it for Always-on (intent action `VpnService.SERVICE_INTERFACE` or null
    intent), connect the default profile.
  - Implements `onRevoke()`.
  - Posts the ongoing notification and calls `startForeground(id, n, FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)`
    first thing in `onStartCommand`, before SSH connects or the TUN is established (M0: works from
    the app, the tile, the consent activity and an Always-on boot start).
  - Manifest: `android:foregroundServiceType="systemExempted"`,
    `android:permission="android.permission.BIND_VPN_SERVICE"`, `android:exported="false"`, an
    intent filter for `android.net.VpnService`, and `<uses-permission>` for `FOREGROUND_SERVICE` and
    `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`.
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
    1. If "require unlock" is on and `isLocked`, wrap the action in `unlockAndRun { … }`. (M0: on the
       API 36 emulator with a PIN, SystemUI already shows the bouncer and never calls `onClick` while
       locked; keep the check anyway and verify on a physical device in M5.)
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
    OnState(stateJSON string)                                  // see "State JSON" below
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

func FetchHostKey(platform Platform, configJSON string) (string, error) // {"type","fingerprint","line"}
func DiscoverRoutes(platform Platform, configJSON string, importedKey []byte) (string, error) // [{"cidr","dev","isDefault","isLinkLocal"}]
func AuthorizedKeyLine(pkixPublicKey []byte, comment string) (string, error)
func ValidateConfig(configJSON string) string // "" or JSON list of {field, code, severity, suggestion}
func Version() string
```

`FetchHostKey` and `DiscoverRoutes` take the `Platform` because they must protect their socket,
resolve the host on the underlying network, and (for discovery) sign with the Keystore key
(M1). Every `[]byte importedKey` is zeroed by Go before the call returns.

**Errors.** Every error returned to Kotlin has a message of the form `"CODE: detail"`, where `CODE`
is one of the codes below. Invalid profiles fail with `INTERNAL: invalid profile: field=CODE, …`.

**State JSON** (`OnState`):

```json
{
  "state": "off|connecting|sshReady|on|reconnecting|needsAttention|disconnecting",
  "code": "HOST_UNREACHABLE",        // needsAttention: the error; reconnecting: last dial error
  "detail": "…",                     // human-readable detail for diagnostics, not UI copy
  "step": "resolving|identity|auth|tunnel",           // connecting only
  "reason": "dialFailed|networkChanged|keepaliveTimeout|connectionClosed", // reconnecting
  "attempt": 3,                      // reconnecting
  "nextRetryAt": 1790337601000,      // reconnecting, unix ms; absent while a dial is in progress
  "warnings": ["FORWARDING_DENIED", "DNS_UNREACHABLE"] // on
}
```

`FORWARDING_DENIED` is raised by the first flow the server refuses with `Prohibited` and cleared on
the next SSH connection. `ROUTE_DISCOVERY_UNAVAILABLE` only comes from `DiscoverRoutes`.

**Validation issues** (`ValidateConfig`): `{"field":"routes[1]","code":"ROUTE_OVERLAP",
"severity":"warning","suggestion":"10.0.0.0/8"}`. Codes are defined in `core/config/validate.go`;
only `severity:"error"` blocks saving or connecting.

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
  "tun": { "cidr": "198.18.0.0/24", "dnsVirtualIp": "198.18.0.53", "mtu": 1500 },
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

## 11. Platform facts (verified in Milestone 0)

Verified on 2026-09-25 on the API 36 `google_apis` x86_64 emulator (build BE2A.250530.026.F3), a
16 KB page-size `google_apis_ps16k` emulator, and stock OpenSSH 10.2 (Alpine) / 9.6 (Ubuntu). Raw
results and logs are on the `spikes` branch (`spikes/README.md`, `spikes/out/`).

1. **Foreground service type:** `systemExempted` works. `specialUse` works too, but isn't needed and
   would need a justification property. `startForeground` from `onStartCommand` succeeds when the
   service is started by the app, the tile (process killed beforehand), the consent activity, and
   the system for Always-on at boot.
2. **Tile start path:** `TileService.onClick` → `startForegroundService` works with the app's process
   killed (the process runs at importance 125 during the click). Without consent,
   `startActivityAndCollapse(PendingIntent)` → consent activity → system dialog → start works.
   **Lock screen:** with a PIN set, SystemUI shows the bouncer and doesn't call `onClick` while
   locked, so the tile can't connect from a locked, secured phone. "Require unlock" is therefore
   enforced by the platform on this build. Check on a physical device in M5 (§7 test matrix).
3. **`exported`:** `android:exported="false"` (plus `BIND_VPN_SERVICE`) keeps the app listed under
   Settings → VPN, and Always-on still starts the service at boot (`action=android.net.VpnService`).
   The same holds for `true`; `false` is chosen because nothing but the system needs to bind it.
4. **`DnsResolver.rawQuery` on the underlying `Network`** isn't captured by our VPN: with a
   `0.0.0.0/0` VPN up, it answered in 267 ms (21 ms with strict Private DNS, which it follows) and no
   DNS packet reached the TUN. The control query on the default (VPN) network went into the TUN to
   the VPN's DNS address and timed out. Android also opened TCP/853 to the VPN DNS address
   (opportunistic Private DNS probe), which confirms the RST-on-853 rule in §4.
5. **gVisor on gomobile:** the `@go` branch needs Go ≥ 1.26.3. `gomobile bind` with NDK r30 produces
   `.so` files with 16 KB `LOAD` alignment by default (no extra linker flags), `zipalign -P 16`
   passes, and the library loads and runs a gVisor stack on a 16 KB page-size kernel.
   `gomobile bind` execs `gobind` from `PATH`, so the build installs the version pinned in `go.mod`
   first.
6. **`ssh.NewSignerFromSigner` with a Keystore ECDSA P-256 key** (`NONEwithECDSA`, DER signature)
   authenticates against OpenSSH 10.2, and `direct-tcpip` works through it. The emulator's Keystore
   reports `SECURITY_LEVEL_SOFTWARE` and no StrongBox, so the hardware-backed badge must come from
   `KeyInfo.getSecurityLevel()` and may legitimately be absent.

## 12. Licensing and compliance

sshovel is licensed **GPL-3.0-or-later**. The copyright holder is **Dennis Klein**.

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
   <!-- REUSE-IgnoreStart -->
   ```
   // SPDX-FileCopyrightText: 2026 Dennis Klein
   // SPDX-License-Identifier: GPL-3.0-or-later
   ```
   <!-- REUSE-IgnoreEnd -->
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
