// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

plugins { id("com.android.application") }

val fgsType = providers.gradleProperty("fgsType").get()
val vpnExported = providers.gradleProperty("vpnExported").get()
require(fgsType in setOf("systemExempted", "specialUse")) { "fgsType must be systemExempted or specialUse" }

android {
    namespace = "com.github.dennisklein.sshovel.spike"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.github.dennisklein.sshovel.spike"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "m0-$fgsType-exported-$vpnExported"
        manifestPlaceholders["fgsType"] = fgsType
        manifestPlaceholders["vpnExported"] = vpnExported
        buildConfigField("String", "FGS_TYPE", "\"$fgsType\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { useLegacyPackaging = false } }
}

dependencies {
    // Built by ../../core/build-aar.sh (spike 1).
    implementation(files("libs/core.aar"))
}
