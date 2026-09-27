// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    id("com.android.application") version "9.4.1"
}

android {
    namespace = "test.othervpn"
    compileSdk = 37
    buildToolsVersion = "36.1.0"
    defaultConfig {
        applicationId = "test.othervpn"
        minSdk = 36
        targetSdk = 36
    }
}
