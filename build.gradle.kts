//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless) apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

tasks.register("coverageCheck") {
    dependsOn(
        ":desktop:jacocoTestCoverageVerification",
        ":core:shared:jacocoTestCoverageVerification",
        ":core:common:jacocoTestCoverageVerification",
        ":core:domain:jacocoTestCoverageVerification",
        ":core:scoring:jacocoTestCoverageVerification",
        ":core:database:jacocoTestCoverageVerification"
    )
}

tasks.register("staticAnalysisCheck") {
    dependsOn(
        ":app:lintDebug",
        ":desktop:detekt",
        ":core:shared:detekt",
        ":core:common:detekt",
        ":core:domain:detekt",
        ":core:scoring:detekt",
        ":core:database:detekt"
    )
}

tasks.register("preCommitCheck") {
    dependsOn(
        ":app:spotlessCheck",
        ":desktop:spotlessCheck",
        ":core:shared:spotlessCheck",
        ":core:common:spotlessCheck",
        ":core:domain:spotlessCheck",
        ":core:scoring:spotlessCheck",
        ":core:database:spotlessCheck",
        "staticAnalysisCheck"
    )
}
