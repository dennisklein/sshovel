#!/bin/sh
# SPDX-FileCopyrightText: 2026 Dennis Klein
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds a compose file's images with buildx bake, allowing host networking for the builds:
#   test-env/build.sh                                            test-env's images
#   test-env/build.sh -f tools/android-env/compose.yaml --profile tools
#
# The builds use the host's network so package downloads go through the host's resolver
# (compose.yaml). Compose v5 hands every build to bake but can't grant bake the network.host
# entitlement, and newer buildx refuses without it ("additional privileges requested"). So
# compose prints its bake definition and bake builds it with the grants: host networking, and
# reading the build contexts (all inside this repository).
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
[ $# -gt 0 ] || set -- -f "$root/test-env/compose.yaml"
docker compose "$@" build --print | docker buildx bake -f - --allow network.host --allow "fs.read=$root"
