#!/bin/sh
# SPDX-FileCopyrightText: 2026 Dennis Klein
# SPDX-License-Identifier: GPL-3.0-or-later
#
# One command for all of M0 on a Linux x86_64 host with Docker and KVM:
# builds the toolbox image, starts test-env, runs every spike scenario on an
# API 36 emulator, and leaves the results in spikes/out/.
set -eu
cd "$(dirname "$0")"

[ -e /dev/kvm ] || { echo "No /dev/kvm: enable virtualization (VT-x/AMD-V) in the BIOS and load kvm_intel/kvm_amd." >&2; exit 1; }
[ -r /dev/kvm ] && [ -w /dev/kvm ] || echo "note: your user can't access /dev/kvm; Docker runs as root, so this is usually fine." >&2
docker compose version >/dev/null 2>&1 || { echo "Needs Docker with the compose plugin (v2.20+)." >&2; exit 1; }

compose="docker compose -f compose.yaml"
$compose --profile tools build
$compose up -d --build jumphost dns wiki
trap '$compose down' EXIT
$compose --profile tools run --rm android /work/spikes/env/run-spikes.sh "$@"
