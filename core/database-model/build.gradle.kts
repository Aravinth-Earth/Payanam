//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
}

kotlin {
    jvmToolchain(17)
}

detekt {
    toolVersion = libs.versions.detekt.get()
    config = files("${rootProject.projectDir}/config/detekt/detekt.yml")
}

spotless {
    kotlin {
        target("**/*.kt")
        ktlint(libs.versions.ktlint.get())
        licenseHeaderFile("${rootProject.projectDir}/config/spotless/copyright.kt")
    }
}

// The platform-neutral half of the data layer: Room entity definitions and the row<->domain
// mappers. Both Android (:core:database, via Room + KSP) and the JVM backend
// (:core:database-jvm, via JDBC) consume this module, so the table model and the mapping layer
// are defined exactly once.
//
// Why :room:common-jvm and not :room:runtime — Room 2.7.x is multiplatform. The annotation
// artifact is a plain JAR and is all this module needs; room-runtime resolves to
// room-runtime-android and cannot be used from a kotlin.jvm module. The Android side keeps
// room-runtime + room-ktx + the KSP compiler unchanged.
//
// Entities depend on nothing but androidx.room annotations (97 imports, zero android.content), and
// the mappers are pure Kotlin over the domain models. Round-tripped through a plain JVM.
dependencies {
    // Domain models are part of the public surface of the mappers.
    api(project(":core:domain"))
    // PersistedDateTime, used by the mappers for date conversion.
    api(project(":core:shared"))
    api(libs.room.common.jvm)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
