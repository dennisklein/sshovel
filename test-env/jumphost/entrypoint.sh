#!/bin/sh
# SPDX-FileCopyrightText: 2026 Dennis Klein
# SPDX-License-Identifier: GPL-3.0-or-later
# Host keys live in a volume so they survive rebuilds; empty it to rotate them
# (manual test: HOST_KEY_MISMATCH).
set -eu
for t in ecdsa ed25519; do
    k=/etc/ssh/hostkeys/ssh_host_${t}_key
    [ -f "$k" ] || ssh-keygen -q -t "$t" -N '' -f "$k"
done
for k in /etc/ssh/hostkeys/*.pub; do ssh-keygen -lf "$k"; done
exec /usr/sbin/sshd -D -e
