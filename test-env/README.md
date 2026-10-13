<!--
SPDX-FileCopyrightText: 2026 Dennis Klein
SPDX-License-Identifier: GPL-3.0-or-later
-->

# test-env: a fake intranet behind a jump host

`./build.sh && docker compose up` starts the services below. (`build.sh` builds the images
through `docker buildx bake` with host networking allowed, which `docker compose build` can't
grant on newer Docker versions; see the script.)

| Service | Address | Reachable from |
|---|---|---|
| `jumphost` (OpenSSH, user `tester`, key-only) | host port `2222` | everywhere |
| `bastion` (the same image; forwards only to `jumphost:22`) | host port `2223` | everywhere |
| `wiki` (nginx) | `10.77.0.20:80`; `/files/` is throttled to 128 KB/s (`wiki/default.conf`) for downloads that outlast a network switch | only the jump host |
| `dns` (dnsmasq): `wiki.corp.test` → `10.77.0.20`, `api.corp.test` → `10.77.0.21` (no host), `git.corp.test` → `203.0.113.40` (outside the routed subnet) | `10.77.0.53:53` | only the jump host |

The jump host forwards only to `10.77.0.20:80`, `10.77.0.53:53` and `10.77.0.21:80`
(`PermitOpen`), so `https://wiki.corp.test` (port 443) is refused by server policy and
`http://api.corp.test` finds nothing: the failures Diagnostics explains (M7).

Keys allowed to log in go into `keys/authorized_keys` (one per line; sshd reads the file on
every login). To rotate the host key (`HOST_KEY_MISMATCH` test), delete the files in `hostkeys/`:
the jump host generates new ones within a second and reloads sshd, no restart needed.

## Android emulator

Profile: host `10.0.2.2`, port `2222`, user `tester`, route `10.77.0.0/24`, DNS server
`10.77.0.53`, suffix `corp.test`. `http://wiki.corp.test` should load in the browser.

## Through a bastion (M9)

`bastion` stands in for a site's login host in front of the machine with intranet access: it
forwards to nothing but `jumphost:22`, a name only it can resolve, and refuses everything else
as "administratively prohibited". Its host key lives in `hostkeys-bastion/` (delete to rotate,
as above). Profile for the emulator: host `jumphost`, port `22`, jump host `10.0.2.2` port
`2223`, user `tester`, the rest as above. With any other server name the bastion refuses to
forward (`FORWARDING_DENIED`, Needs attention). For the CLI, start from
`cli-profile-jump.example.json` (copy it to `cli-profile-jump.json`) and run `hostkey` twice:
the first run prints the bastion's key for `jump.hostKey`, the second (with `-key`) the jump
host's key for `hostKey`.

## Linux CLI (`core/cmd/sshovel-cli`)

The CLI runs the same Go engine on a TUN device. On the Docker host `10.77.0.0/24` is a local
bridge, so run the CLI in a network namespace that can only reach the jump host
(needs root for the namespace and the TUN device):

```sh
ssh-keygen -t ed25519 -N '' -f cli_key            # the CLI uses an unencrypted key file
echo "restrict,port-forwarding $(cat cli_key.pub)" >> keys/authorized_keys
cp cli-profile.example.json cli-profile.json
./build.sh && docker compose up -d

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
