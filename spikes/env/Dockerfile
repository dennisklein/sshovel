# SPDX-FileCopyrightText: 2026 Dennis Klein
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Everything M0 needs on a Linux x86_64 host with KVM: JDK, Go, Android SDK,
# NDK (latest stable, r28+), emulator and an API 36 system image. ~12 GB.
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl unzip git python3 binutils file \
        openjdk-21-jdk-headless golang-go \
        libpulse0 libnss3 libxcomposite1 libxcursor1 libxdamage1 libxi6 libxtst6 \
        libasound2t64 libgl1 libxkbfile1 libgbm1 libdbus-1-3 libxkbcommon0 libx11-xcb1 \
    && rm -rf /var/lib/apt/lists/*

# Ubuntu's Go is older than gVisor needs; the go.mod toolchain line makes it
# download the right one (cached in the go-cache volume).
ENV GOTOOLCHAIN=auto

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
ARG SYSTEM_IMAGE="system-images;android-36;google_apis;x86_64"
# 16 KB page-size image: spike 1 loads the Go .so on a real 16 KB kernel.
ARG SYSTEM_IMAGE_16K="system-images;android-36;google_apis_ps16k;x86_64"
RUN set -eux; \
    list=$(sdkmanager --list 2>/dev/null | awk '{print $1}' | tr '/' ';' | sort -u); \
    ndk=$(echo "$list" | grep -E '^ndk;[0-9]+\.[0-9]+\.[0-9]+$' | sort -t';' -k2 -V | tail -n1 || true); \
    bt=$(echo "$list" | grep -E '^build-tools;36\.[0-9]+\.[0-9]+$' | sort -t';' -k2 -V | tail -n1 || true); \
    img16k=$(echo "$list" | grep -Fx "$SYSTEM_IMAGE_16K" || true); \
    if [ -z "$ndk" ] || [ -z "$bt" ] || ! echo "$list" | grep -Fxq "$SYSTEM_IMAGE"; then \
        echo "Package lookup failed (ndk='$ndk' build-tools='$bt'). Available:"; \
        echo "$list" | grep -E '^(ndk|build-tools|platforms|system-images;android-36);' ; exit 1; \
    fi; \
    major=${ndk#ndk;}; major=${major%%.*}; [ "$major" -ge 28 ]; \
    pkgs="platform-tools emulator platforms;android-36 $bt $ndk $SYSTEM_IMAGE $img16k"; \
    sdkmanager --install $pkgs || sdkmanager --install $(echo "$pkgs" | tr ';' '/'); \
    ln -s "$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -n1)" /opt/ndk; \
    echo "$ndk $bt ${img16k:-no-16k-image}" > /opt/sdk-versions
ENV ANDROID_NDK_HOME=/opt/ndk

RUN set -eux; \
    printf 'hw.ramSize=4096\ndisk.dataPartition.size=6G\nhw.keyboard=yes\n' > /tmp/avd.ini; \
    echo no | avdmanager create avd -n spike -k "$SYSTEM_IMAGE" -d pixel_7; \
    cat /tmp/avd.ini >> /root/.android/avd/spike.avd/config.ini; \
    if [ -d "$ANDROID_HOME/$(echo "$SYSTEM_IMAGE_16K" | tr ';' '/')" ]; then \
        echo no | avdmanager create avd -n spike16k -k "$SYSTEM_IMAGE_16K" -d pixel_7; \
        cat /tmp/avd.ini >> /root/.android/avd/spike16k.avd/config.ini; \
    fi

WORKDIR /work
