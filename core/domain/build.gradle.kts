//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.kotlin.jvm)
    id("jacoco")
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

dependencies {
    // Public API exposes io.payanam.shared types (AppLogger, UnifiedLogger) to this module's
    // consumers, so :core:shared is `api` rather than `implementation`.
    api(project(":core:shared"))
    implementation(libs.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

jacoco {
    toolVersion = "0.8.11"
}

tasks.withType<Test>().configureEach {
    useJUnit()
    extensions.configure(JacocoTaskExtension::class) {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

// Android-only entries (R.class, BuildConfig, Manifest) were dropped with the promotion.
// `**/repository/**` stays: those are interfaces, so they carry no coverable code.
val coverageExclusions = listOf(
    "**/*Test*.*",
    "**/repository/**",
    "**/model/ScoringConfig*"  // Data class only
)

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn("test")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    val kotlinClasses = fileTree("${layout.buildDirectory.get()}/classes/kotlin/main") {
        exclude(coverageExclusions)
    }
    classDirectories.setFrom(kotlinClasses)
    sourceDirectories.setFrom(files("src/main/kotlin"))
    executionData.setFrom(
        fileTree(layout.buildDirectory.get()).include(
            "jacoco/test.exec",
            "jacoco/test*.exec"
        )
    )
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn("test")
    val kotlinClasses = fileTree("${layout.buildDirectory.get()}/classes/kotlin/main") {
        exclude(coverageExclusions)
    }
    classDirectories.setFrom(kotlinClasses)
    sourceDirectories.setFrom(files("src/main/kotlin"))
    executionData.setFrom(
        fileTree(layout.buildDirectory.get()).include(
            "jacoco/test.exec",
            "jacoco/test*.exec"
        )
    )
    violationRules {
        rule {
            limit {
                // Floor is the MEASURED value (78% of instructions), not an aspiration. Before the
                // kotlin.jvm promotion this task resolved an empty class set and passed vacuously,
                // so the previous 0.95 was never actually enforced. This floor ratchets UP only.
                minimum = "0.78".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn("jacocoTestCoverageVerification")
}
