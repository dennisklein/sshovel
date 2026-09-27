// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

// A throwaway VPN app for the M5 acceptance run: starting it must revoke sshovel's VPN
// (VPN_REVOKED). Built by tools/android-env/m5.sh only; never shipped.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "othervpn"
