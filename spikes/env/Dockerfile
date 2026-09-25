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

ARG SYSTEM_IMAGE="system-images;android-36;google_apis;x86_64"
RUN set -eux; \
    list=$(sdkmanager --list 2>/dev/null | awk '{print $1}'); \
    ndk=$(echo "$list" | grep -E '^ndk;[0-9]+\.[0-9]+\.[0-9]+$' | sort -t';' -k2 -V | tail -n1); \
    bt=$(echo "$list" | grep -E '^build-tools;36\.[0-9]+\.[0-9]+$' | sort -t';' -k2 -V | tail -n1); \
    major=${ndk#ndk;}; major=${major%%.*}; [ "$major" -ge 28 ]; \
    sdkmanager --install platform-tools emulator "platforms;android-36" "$bt" "$ndk" "$SYSTEM_IMAGE"; \
    ln -s "$ANDROID_HOME/ndk/${ndk#ndk;}" /opt/ndk; \
    echo "$ndk $bt" > /opt/sdk-versions
ENV ANDROID_NDK_HOME=/opt/ndk

RUN echo no | avdmanager create avd -n spike -k "$SYSTEM_IMAGE" -d pixel_7 \
    && printf 'hw.ramSize=4096\ndisk.dataPartition.size=6G\nhw.keyboard=yes\n' >> /root/.android/avd/spike.avd/config.ini

WORKDIR /work
