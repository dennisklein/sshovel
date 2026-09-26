#!/bin/sh
# SPDX-FileCopyrightText: 2026 Dennis Klein
# SPDX-License-Identifier: GPL-3.0-or-later
# Host keys live in a volume so they survive rebuilds. Delete them to rotate:
# within a second new keys are generated and sshd reloads them (SIGHUP), so
# acceptance runs can test HOST_KEY_MISMATCH without restarting the container.
set -eu
keys() {
    for t in ecdsa ed25519; do
        k=/etc/ssh/hostkeys/ssh_host_${t}_key
        [ -f "$k" ] || ssh-keygen -q -t "$t" -N '' -f "$k"
    done
    for k in /etc/ssh/hostkeys/*.pub; do ssh-keygen -lf "$k"; done
}
keys
/usr/sbin/sshd -D -e &
pid=$!
trap 'kill -TERM "$pid"; wait "$pid"; exit 0' TERM INT
while kill -0 "$pid" 2>/dev/null; do
    if [ ! -f /etc/ssh/hostkeys/ssh_host_ed25519_key ] || [ ! -f /etc/ssh/hostkeys/ssh_host_ecdsa_key ]; then
        echo "host keys removed: rotating"
        keys
        kill -HUP "$pid"
    fi
    sleep 1
done
wait "$pid"
