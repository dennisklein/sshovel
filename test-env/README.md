<!--
SPDX-FileCopyrightText: 2026 Dennis Klein
SPDX-License-Identifier: GPL-3.0-or-later
-->

# test-env: a fake intranet behind a jump host

`docker compose up --build` starts:

| Service | Address | Reachable from |
|---|---|---|
| `jumphost` (OpenSSH, user `tester`, key-only) | host port `2222` | everywhere |
| `wiki` (nginx) | `10.77.0.20:80` | only the jump host |
| `dns` (dnsmasq): `wiki.corp.test` → `10.77.0.20`, `api.corp.test` → `10.77.0.21` | `10.77.0.53:53` | only the jump host |

Keys allowed to log in go into `keys/authorized_keys` (one per line; sshd reads the file on
every login). Empty `hostkeys/` and restart to rotate the host key (`HOST_KEY_MISMATCH` test).

## Android emulator

Profile: host `10.0.2.2`, port `2222`, user `tester`, route `10.77.0.0/24`, DNS server
`10.77.0.53`, suffix `corp.test`. `http://wiki.corp.test` should load in the browser.

## Linux CLI (`core/cmd/sshovel-cli`)

The CLI runs the same Go engine on a TUN device. On the Docker host `10.77.0.0/24` is a local
bridge, so run the CLI in a network namespace that can only reach the jump host
(needs root for the namespace and the TUN device):

```sh
ssh-keygen -t ed25519 -N '' -f cli_key            # the CLI uses an unencrypted key file
echo "restrict,port-forwarding $(cat cli_key.pub)" >> keys/authorized_keys
cp cli-profile.example.json cli-profile.json
docker compose up -d --build

sudo ./cli-netns.sh setup
sudo ./cli-netns.sh run -profile cli-profile.json hostkey
#   → paste the printed "hostKey" object into cli-profile.json
sudo ./cli-netns.sh run -profile cli-profile.json -key cli_key routes
sudo ./cli-netns.sh run -profile cli-profile.json -key cli_key -upstream 192.168.100.1:53 -v up
```

In a second terminal:

```sh
sudo ./cli-netns.sh exec curl http://wiki.corp.test/      # through the tunnel
sudo ./cli-netns.sh exec curl http://10.77.0.20/
sudo kill -USR1 $(pgrep -f 'sshovel-cli.* up')          # simulate a network change
sudo ./cli-netns.sh teardown
```

`-upstream` is the resolver for names outside `corp.test` (Android uses the underlying network
for these). Point it at any resolver the namespace can reach; without one, direct names answer
`SERVFAIL`, which doesn't affect intranet tests.
