<!-- SPDX-FileCopyrightText: 2026 Dennis Klein -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Setting up the jump host for sshovel

sshovel needs one thing from the server: an OpenSSH account that accepts its key and lets that key
open TCP forwards (`direct-tcpip` channels) to the intranet. It never runs a shell or commands,
except "Discover from server" (`ip route`), which a restricted key turns off on purpose.

## 1. The key's `authorized_keys` line

sshovel shows the line to add in onboarding (step 3) and in Keys → key → "Copy as authorized_keys
line". It looks like this:

```
restrict,port-forwarding ecdsa-sha2-nistp256 AAAAE2VjZHNh… sshovel key
```

- `restrict` turns off everything for this key: shell, commands, agent and X11 forwarding, PTYs.
- `port-forwarding` turns local forwarding back on, which is all sshovel uses.

To limit the key to the destinations your users need, add `permitopen` (one per destination;
`*` as the port allows any port on that host):

```
restrict,port-forwarding,permitopen="10.20.0.53:53",permitopen="wiki.corp.example:443",permitopen="10.20.7.15:*" ecdsa-sha2-nistp256 AAAA… sshovel key
```

Include the intranet DNS server with port 53 if the profile sets one: sshovel sends intranet DNS
queries over TCP through the same connection. A destination outside the list shows up in sshovel's
Diagnostics as "Refused by server policy", and the app shows the `FORWARDING_DENIED` warning.

## 2. `sshd_config`

The defaults of a current OpenSSH work. The settings that matter:

```
PubkeyAuthentication yes
PasswordAuthentication no          # sshovel only uses keys
KbdInteractiveAuthentication no
AllowTcpForwarding local           # or yes; "no" makes every connection fail with FORWARDING_DENIED
# PermitOpen 10.20.0.53:53 10.20.7.15:443   # optional server-wide allow-list, like permitopen above
ClientAliveInterval 60             # optional: drop dead clients; sshovel sends its own keepalive every 20 s
```

sshovel's key types: ECDSA P-256 keys created on the phone (the default), or imported Ed25519,
ECDSA and RSA (2048 bit or more) keys. Host keys of any type OpenSSH offers are accepted and pinned.

Each TCP connection an app makes becomes one SSH channel on a single SSH connection, so a busy
phone may hold a few dozen channels open; OpenSSH has no per-connection channel limit to raise.

## 3. Let users verify the server

On the first connection sshovel shows the server's host key fingerprint and asks the user to
compare it before trusting the server. Publish the fingerprints where your users can find them:

```
for k in /etc/ssh/ssh_host_*_key.pub; do ssh-keygen -lf "$k"; done
```

sshovel pins the key it was shown. If you replace the server's host keys, every user sees
"Server identity changed" and has to forget the pinned key in the profile and verify the new one;
tell them before you rotate.

## 4. Routes and DNS for the profile

Users add the intranet subnets and, for name resolution, the intranet DNS server and its domain
suffixes. With a restricted key "Discover from server" can't run `ip route`; hand users the list
instead (for example `10.20.0.0/16`, DNS `10.20.0.53`, suffixes `corp.example`).

Names that resolve to addresses outside the routed subnets are flagged in Diagnostics ("Resolved
outside routed subnets"); add those subnets to the profile or fix the DNS records.

## 5. Known limitations users may report

- Only TCP (and DNS) goes through the tunnel; no other UDP, and no ICMP (ping).
- IPv4 only.
- Phones with Private DNS set to a specific hostname, and apps with their own DNS-over-HTTPS,
  bypass the intranet DNS; sshovel explains this in Diagnostics.
