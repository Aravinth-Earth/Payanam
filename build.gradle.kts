//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
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
        ":core:database:jacocoTestCoverageVerification",
        // The JVM data layer. Its floor is a MEASURED 0.55 (see its build file for why that is low:
        // the accept path needs a real export, which a generated fixture cannot reach).
        //
        // :core:database-model is deliberately NOT here yet. It holds the entities + mappers but no
        // tests of its own — the mapper tests still live in :core:database — so any floor for it would
        // be 0 and therefore decorative, which is the exact defect this repo has already been burned by
        // (core/common and core/database pass vacuously). It joins this list together with the mapper
        // tests that give it real coverage.
        ":core:database-jvm:jacocoTestCoverageVerification",
        // The shared state layer. Hilt-free ViewModels, so a plain JVM floor applies.
        ":core:presentation:jacocoTestCoverageVerification"
    )
}

tasks.register("staticAnalysisCheck") {
    dependsOn(
        ":app:lintDebug",
        ":app:detekt",
        ":desktop:detekt",
        ":core:shared:detekt",
        ":core:common:detekt",
        ":core:domain:detekt",
        ":core:scoring:detekt",
        ":core:database:detekt",
        ":core:database-model:detekt",
        ":core:database-jvm:detekt",
        ":core:presentation:detekt",
        // :core:ui is Kotlin Multiplatform and has no src/main/kotlin, so the plain `detekt`
        // task reports NO-SOURCE and checks nothing. These are the per-target tasks that do.
        ":core:ui:detektMetadataCommonMain",
        ":core:ui:detektAndroidMain",
        ":core:ui:detektDesktopMain",
        // Applies the detekt plugin but was never listed here.
        ":core:strings:detekt"
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
        ":core:database-model:spotlessCheck",
        ":core:database-jvm:spotlessCheck",
        ":core:presentation:spotlessCheck",
        ":core:ui:spotlessCheck",
        "staticAnalysisCheck"
    )
}
