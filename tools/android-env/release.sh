#!/bin/bash
# SPDX-FileCopyrightText: 2026 Dennis Klein
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds a release APK from a clean checkout of a tag (docs/RELEASE.md), inside the toolbox:
#   tools/android-env/run.sh release v0.8.0
# The tag must exist in /work (the repo the toolbox mounts) and match versionName ("v" + it).
# The checkout always lands at /build/sshovel: the Go library records its build directory, so
# the same tag, toolchain, and path give the same libgojni.so (checked in M8: two builds of the
# same commit at the same path were byte-identical).
# Signing: with SSHOVEL_SIGNING_STORE_FILE, _STORE_PASSWORD, _KEY_ALIAS and _KEY_PASSWORD set
# (the store file under /work), the release key; otherwise the debug key, a placeholder.
# Results: tools/android-env/out/sshovel-<tag>.apk, release-<tag>.txt (toolchain, hashes).
set -euo pipefail

tag=${1:?usage: release.sh <tag>}
WORK=${WORK:-/work}
OUT=${OUT:-$WORK/tools/android-env/out}
SRC=/build/sshovel
mkdir -p "$OUT"
trap 'chown -R "$(stat -c %u:%g "$WORK")" "$OUT"' EXIT
git config --global --add safe.directory '*'

rm -rf "$SRC"; mkdir -p /build
git clone --quiet --no-local "$WORK" "$SRC"
git -C "$SRC" -c advice.detachedHead=false checkout --quiet "refs/tags/$tag"
git -C "$SRC" status --porcelain | grep -q . && { echo "checkout of $tag isn't clean" >&2; exit 1; }

version=$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' "$SRC/app/build.gradle.kts")
[ "v$version" = "$tag" ] || { echo "tag $tag doesn't match versionName $version" >&2; exit 1; }

echo "sdk.dir=$ANDROID_HOME" > "$SRC/local.properties"
# Signing properties go in as ORG_GRADLE_PROJECT_* variables, so the passwords aren't in argv.
signing=()
if [ -n "${SSHOVEL_SIGNING_STORE_FILE:-}" ]; then
    signing=("ORG_GRADLE_PROJECT_sshovel.signing.storeFile=$SSHOVEL_SIGNING_STORE_FILE"
             "ORG_GRADLE_PROJECT_sshovel.signing.storePassword=$SSHOVEL_SIGNING_STORE_PASSWORD"
             "ORG_GRADLE_PROJECT_sshovel.signing.keyAlias=$SSHOVEL_SIGNING_KEY_ALIAS"
             "ORG_GRADLE_PROJECT_sshovel.signing.keyPassword=$SSHOVEL_SIGNING_KEY_PASSWORD")
fi
# preReleaseBuild runs checkLicenses and reuseLint first (ARCHITECTURE §12).
(cd "$SRC" && env "${signing[@]}" ./gradlew --no-daemon --console=plain :app:assembleRelease collectGoLicenses verifyPageAlignment)

apk="$OUT/sshovel-$tag.apk"
cp "$SRC/app/build/outputs/apk/release/app-release.apk" "$apk"
# The About screen's source link, as built into the APK (BuildConfig.SOURCE_URL).
url="https://github.com/dennisklein/sshovel/tree/$tag"
# (grep -c, not -q: under pipefail, grep quitting early would fail unzip with SIGPIPE.)
[ "$(unzip -p "$apk" 'classes*.dex' | grep -acF "$url")" -gt 0 ] || { echo "APK doesn't carry the source link $url" >&2; exit 1; }
signer=$("$ANDROID_HOME"/build-tools/*/apksigner verify --print-certs "$apk" | grep -m1 'certificate DN' || true)
{
    echo "sshovel $tag ($(git -C "$SRC" rev-parse HEAD)), built $(date -u +%FT%TZ)"
    echo "source link: $url"
    echo "signer: ${signer#*: }"
    echo "SDK packages: $(cat /opt/sdk-versions 2>/dev/null)"
    (cd "$SRC/core" && go version)
    java -version 2>&1 | head -n1
    echo
    sha256sum "$apk" | sed "s|$OUT/||"
    rm -rf /tmp/release-libs; unzip -o -q "$apk" 'lib/*' -d /tmp/release-libs
    (cd /tmp/release-libs && sha256sum lib/*/*)
} | tee "$OUT/release-$tag.txt"
