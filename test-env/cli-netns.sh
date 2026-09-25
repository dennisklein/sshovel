#!/bin/sh
# SPDX-FileCopyrightText: 2026 Dennis Klein
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Runs sshovel-cli against this test environment from a network namespace
# ("phone") that, like the emulator, has no route to the intranet: on the
# Docker host itself 10.77.0.0/24 is a directly attached bridge.
#
#   sudo ./cli-netns.sh setup          create netns "phone" (veth 192.168.100.2 ⇄ host .1)
#   sudo ./cli-netns.sh run ARGS...    run sshovel-cli ARGS inside it
#   sudo ./cli-netns.sh exec CMD...    run CMD inside it (curl, …)
#   sudo ./cli-netns.sh teardown
set -eu
NS=phone
HERE=$(cd "$(dirname "$0")" && pwd)
case "${1:-}" in
setup)
    ip netns add $NS
    ip link add sshovel-vh type veth peer name sshovel-vp
    ip link set sshovel-vp netns $NS
    ip addr add 192.168.100.1/24 dev sshovel-vh
    ip link set sshovel-vh up
    ip -n $NS addr add 192.168.100.2/24 dev sshovel-vp
    ip -n $NS link set sshovel-vp up
    ip -n $NS link set lo up
    # Name lookups inside the netns go to sshovel's DNS virtual IP.
    mkdir -p /etc/netns/$NS
    echo "nameserver 198.18.0.53" > /etc/netns/$NS/resolv.conf
    echo "netns $NS ready; the jump host is 192.168.100.1:2222"
    ;;
run)
    shift
    BIN=${SSHOVEL_CLI:-$HERE/../core/sshovel-cli}
    [ -x "$BIN" ] || (cd "$HERE/../core" && go build -o sshovel-cli ./cmd/sshovel-cli)
    exec ip netns exec $NS "$BIN" "$@"
    ;;
exec)
    shift
    exec ip netns exec $NS "$@"
    ;;
teardown)
    ip netns del $NS 2>/dev/null || true
    ip link del sshovel-vh 2>/dev/null || true
    rm -rf /etc/netns/$NS
    ;;
*)
    sed -n '5,14p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
