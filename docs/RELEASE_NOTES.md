# Release notes

## 0.8.0 (first release candidate, unreleased)

The first build meant for people other than its developer. It is a release candidate: the release
build is still signed with the debug key (docs/RELEASE.md), so install it fresh, and expect to
reinstall when the first signed release comes out.

### What it does

sshovel reaches your intranet from any app on an Android 16 phone, through an SSH jump host you
already have an account on. Only traffic to the subnets you route goes through SSH; everything else
uses the normal connection. No server software to install: a stock OpenSSH server with one
`authorized_keys` line is enough (docs/SERVER_SETUP.md).

- **Split tunnel over SSH.** One SSH connection carries every TCP connection to the routed subnets
  as `direct-tcpip` channels (sshuttle-style, in a gVisor network stack in the app).
- **Split DNS.** Names under the profile's intranet domains are resolved by the intranet DNS
  server, through the tunnel; everything else by the phone's usual resolver.
- **Per-app control.** Tunnel all apps, only selected apps, or all but selected apps.
- **Keys that can't leave the phone.** Create an ECDSA P-256 key in the Android Keystore
  (StrongBox when the phone has it), or import an existing OpenSSH key (ed25519, ECDSA, RSA), kept
  encrypted with a Keystore key.
- **Host keys pinned on first use**, with the fingerprint shown to compare; a changed host key
  stops the connection until you accept the new one in the profile editor.
- **Quick Settings tile** to connect and disconnect, with "Require unlock", and support for
  Android's Always-on VPN with a default profile.
- **Reconnects by itself** after network changes, with backoff, and says what it's doing.
- **Diagnostics** for when an intranet page doesn't load: events, DNS queries (tunneled or direct),
  and connections with the app that opened them and why one failed; share them as a text file.
- **Onboarding** that goes from nothing to a working profile, including the server-side line to add
  and a connection test.
- Material 3 with dynamic color, light and dark, TalkBack labels and announcements, and layouts
  that hold up at 200 % font size.

### Known limitations

- Only TCP and DNS go through the tunnel: no other UDP (QUIC falls back to TCP), no ICMP (ping).
- IPv4 only inside the tunnel.
- Private DNS set to a specific provider, and apps with their own DNS-over-HTTPS, bypass the
  intranet DNS; Diagnostics explains when that happens.
- All tunneled connections share one TCP connection to the jump host, so a slow or lossy link
  slows all of them.
- A routed subnet that overlaps the local Wi-Fi network hides local devices in that range from
  tunneled apps (the profile editor warns when it can tell).
- "Discover from server" runs `ip route` on the jump host, which a `restrict`ed key doesn't allow.
- Android 16 (API 36) and newer, arm64 and x86_64 only.

### Privacy

No accounts, no analytics, no crash reporting, no network access except the SSH connections and
DNS queries you configure. Diagnostics live in memory only, until you share them. Settings and
profiles stay on the phone and are excluded from backups.
