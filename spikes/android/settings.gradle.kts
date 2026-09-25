// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

// M0 spike app. Throwaway: plain Java, no AndroidX, so the only build-time
// dependency is the Android Gradle Plugin.
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    // -PagpVersion=… overrides gradle.properties; spikes/env/run-spikes.sh passes the latest stable.
    val agpVersion: String by settings
    plugins { id("com.android.application") version agpVersion }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "sshovel-spikes"
include(":app")
