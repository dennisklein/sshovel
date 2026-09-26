#!/bin/sh
# SPDX-FileCopyrightText: 2026 Dennis Klein
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Runs a milestone script in the toolbox on a Linux x86_64 host with Docker and
# KVM: builds the toolbox image, starts test-env, and runs the script. Results
# land in tools/android-env/out/.
#
#   tools/android-env/run.sh m2          M2 acceptance on the emulator
#   tools/android-env/run.sh m3          M3 acceptance on the emulator
#   tools/android-env/run.sh shell       a shell in the toolbox (test-env running)
set -eu
cd "$(dirname "$0")"

[ -e /dev/kvm ] || { echo "No /dev/kvm: enable virtualization (VT-x/AMD-V) in the BIOS and load kvm_intel/kvm_amd." >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "Needs Docker with the compose plugin (v2.20+)." >&2; exit 1; }

compose="docker compose -f compose.yaml"
$compose --profile tools build
$compose up -d --build jumphost dns wiki
trap '$compose down' EXIT
case "${1:-}" in
    shell) $compose --profile tools run --rm android bash ;;
    m2|m3) m=$1; shift; $compose --profile tools run --rm android bash /work/tools/android-env/$m.sh "$@" ;;
    *)     echo "usage: $0 m2|m3|shell" >&2; exit 2 ;;
esac
