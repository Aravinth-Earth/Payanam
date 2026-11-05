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
    // `api`, not `implementation`: this module's public functions expose io.payanam.domain types
    // (BackupTrigger, BackupRetryPolicy) and io.payanam.shared types (UnifiedLogger), so consumers
    // must see both modules on their compile classpath.
    api(project(":core:domain"))
    api(project(":core:shared"))
    // rotation policy + prune live here (rotationPolicyFromBackupRows, pruneBackupArtifacts)
    api(project(":core:presentation"))
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

val coverageExclusions = listOf(
    "**/*Test*.*"
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
                // Floor is the MEASURED value (91% of instructions), not an aspiration. Before the
                // kotlin.jvm promotion this task resolved an empty class set and passed vacuously,
                // so the previous 0.95 was never actually enforced. This floor ratchets UP only.
                minimum = "0.91".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn("jacocoTestCoverageVerification")
}
