#!/bin/sh
# SPDX-FileCopyrightText: 2026 Dennis Klein
# SPDX-License-Identifier: GPL-3.0-or-later
#
# M0 spike 1: build core.aar with gomobile (pinned via the go.mod tool
# directive) and check that every .so is 16 KB page-aligned.
# Needs ANDROID_HOME and ANDROID_NDK_HOME (NDK r28+).
set -eu
cd "$(dirname "$0")"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to an NDK r28+ install}"
out=../android/app/libs/core.aar
# gomobile bind execs "gobind" from PATH; build the version pinned in go.mod.
bin=$(mktemp -d)
trap 'rm -rf "$bin"' EXIT
go build -o "$bin/gobind" golang.org/x/mobile/cmd/gobind
PATH="$bin:$PATH"
mkdir -p "$(dirname "$out")"
go tool gomobile bind -v \
    -target=android/arm64,android/amd64 \
    -androidapi 26 \
    -javapkg=com.github.dennisklein.sshovel.core \
    -o "$out" ./mobile
./check-alignment.sh "$out"
