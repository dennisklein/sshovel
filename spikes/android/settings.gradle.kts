// SPDX-FileCopyrightText: 2026 <Copyright holder>
// SPDX-License-Identifier: GPL-3.0-or-later

// M0 spike app. Throwaway: plain Java, no AndroidX, so the only build-time
// dependency is the Android Gradle Plugin.
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "sshovel-spikes"
include(":app")
