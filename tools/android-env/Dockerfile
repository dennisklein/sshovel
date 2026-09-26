# SPDX-FileCopyrightText: 2026 Dennis Klein
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Build and emulator toolbox for a Linux x86_64 host with KVM: JDK, Go,
# go-licenses, reuse, Android SDK, NDK (latest stable, r28+), emulator, and an
# API 36 system image. ~12 GB. Use ./run.sh rather than building this directly.
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl unzip git python3 python3-pip binutils file openssh-client \
        openjdk-21-jdk-headless golang-go \
        libpulse0 libnss3 libxcomposite1 libxcursor1 libxdamage1 libxi6 libxtst6 \
        libasound2t64 libgl1 libxkbfile1 libgbm1 libdbus-1-3 libxkbcommon0 libx11-xcb1 \
    && rm -rf /var/lib/apt/lists/*

# Ubuntu's Go is older than gVisor needs; the go.mod toolchain line makes it
# download the right one (cached in the go-cache volume).
ENV GOTOOLCHAIN=auto
ENV PATH=/root/go/bin:/opt/go-tools:$PATH

# License tooling (IMPLEMENTATION_PLAN §4). go-licenses must be built with the
# core's Go toolchain; installed outside /root/go, which is a cache volume.
RUN GOTOOLCHAIN=go1.26.3 GOBIN=/opt/go-tools go install github.com/google/go-licenses@v1.6.0 \
    && pip install --break-system-packages --no-cache-dir reuse

ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH

# Any cmdline-tools release works as a bootstrap; it updates itself below.
ARG CMDLINE_TOOLS=13114758
RUN curl -fsSL -o /tmp/ct.zip \
        https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip \
    && mkdir -p $ANDROID_HOME/cmdline-tools \
    && unzip -q /tmp/ct.zip -d $ANDROID_HOME/cmdline-tools \
    && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/bootstrap \
    && rm /tmp/ct.zip \
    && yes | $ANDROID_HOME/cmdline-tools/bootstrap/bin/sdkmanager --licenses >/dev/null \
    && $ANDROID_HOME/cmdline-tools/bootstrap/bin/sdkmanager --install "cmdline-tools;latest" \
    && rm -rf $ANDROID_HOME/cmdline-tools/bootstrap

# Newer sdkmanager prints package paths with "/" (ndk/28.2.x), older ones with
# ";" (ndk;28.2.x). Normalize to ";" for matching, try both when installing.
# The app compiles against API 37 (platform 37.0) and runs on API 36 and up.
ARG COMPILE_PLATFORM="platforms;android-37.0"
# Must match android.buildToolsVersion in app/build.gradle.kts.
ARG BUILD_TOOLS="build-tools;36.1.0"
ARG SYSTEM_IMAGE="system-images;android-36;google_apis;x86_64"
RUN set -eux; \
    list=$(sdkmanager --list 2>/dev/null | awk '{print $1}' | tr '/' ';' | sort -u); \
    ndk=$(echo "$list" | grep -E '^ndk;[0-9]+\.[0-9]+\.[0-9]+$' | sort -t';' -k2 -V | tail -n1 || true); \
    bt=$(echo "$list" | grep -Fx "$BUILD_TOOLS" || true); \
    if [ -z "$ndk" ] || [ -z "$bt" ] || ! echo "$list" | grep -Fxq "$SYSTEM_IMAGE" \
            || ! echo "$list" | grep -Fxq "$COMPILE_PLATFORM"; then \
        echo "Package lookup failed (ndk='$ndk' build-tools='$bt'). Available:"; \
        echo "$list" | grep -E '^(ndk|build-tools|platforms|system-images;android-3[67]);' ; exit 1; \
    fi; \
    major=${ndk#ndk;}; major=${major%%.*}; [ "$major" -ge 28 ]; \
    pkgs="platform-tools emulator $COMPILE_PLATFORM $bt $ndk $SYSTEM_IMAGE"; \
    sdkmanager --install $pkgs || sdkmanager --install $(echo "$pkgs" | tr ';' '/'); \
    ln -s "$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -n1)" /opt/ndk; \
    echo "$ndk $bt" > /opt/sdk-versions
ENV ANDROID_NDK_HOME=/opt/ndk

RUN set -eux; \
    echo no | avdmanager create avd -n sshovel -k "$SYSTEM_IMAGE" -d pixel_7; \
    printf 'hw.ramSize=4096\ndisk.dataPartition.size=6G\nhw.keyboard=yes\n' >> /root/.android/avd/sshovel.avd/config.ini

# /work is a bind mount owned by the host user, but the toolbox runs as root.
# Without this git refuses the repo ("dubious ownership"), so reuse lint no
# longer honours .gitignore and SOURCE_URL loses the commit hash.
RUN git config --system --add safe.directory /work

WORKDIR /work
