#!/bin/sh
# SPDX-FileCopyrightText: 2026 Dennis Klein
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Fail unless every ELF LOAD segment of every .so inside the given AAR/APK/.so
# files is aligned to at least 16 KB (0x4000). Uses the NDK's llvm-readelf if
# ANDROID_NDK_HOME is set, else readelf from PATH.
set -eu
readelf=$(ls "${ANDROID_NDK_HOME:-/nonexistent}"/toolchains/llvm/prebuilt/*/bin/llvm-readelf 2>/dev/null | head -n1 || true)
[ -n "$readelf" ] || readelf=$(command -v llvm-readelf || command -v readelf)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
status=0
for f in "$@"; do
    case "$f" in
        *.so|*.so.*) libs="$f" ;;
        *) unzip -q -o "$f" '*.so' -d "$tmp/$(basename "$f")"
           libs=$(find "$tmp/$(basename "$f")" -name '*.so') ;;
    esac
    for so in $libs; do
        min=
        for a in $("$readelf" -lW "$so" | awk '$1 == "LOAD" { print $NF }'); do
            a=$((a))
            if [ -z "$min" ] || [ "$a" -lt "$min" ]; then min=$a; fi
        done
        if [ -z "$min" ]; then
            echo "FAIL $so: no LOAD segments"; status=1
        elif [ "$min" -lt 16384 ]; then
            printf 'FAIL %s: min LOAD align 0x%x < 0x4000\n' "${so#"$tmp"/}" "$min"; status=1
        else
            printf 'OK   %s: min LOAD align 0x%x\n' "${so#"$tmp"/}" "$min"
        fi
    done
done
exit $status
