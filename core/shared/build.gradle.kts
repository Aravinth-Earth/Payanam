//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    buildUponDefaultConfig = true
}

spotless {
    kotlin {
        target("**/*.kt")
        ktlint(libs.versions.ktlint.get())
        licenseHeaderFile("${rootProject.projectDir}/config/spotless/copyright.kt")
    }
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)

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
    "**/*Test*.*",
    "**/*\$Companion*",
    "**/*\$\$serializer*",
    "**/BackupModulePayloads*",
    "**/BackupPayloadEnvelope*",
    "**/DataModuleSelection*",
    "**/ImportMode*",
    "**/DesktopTaskSeedCatalogKt*",
    "**/DesktopTaskRecord*",
    "**/DesktopTaskCatalogSnapshot*",
    "**/DesktopTaskBoardPreferences*",
    "**/DesktopTaskBoardCounts*",
    "**/DesktopTaskListItem*",
    "**/DesktopHabitListItem*",
    "**/DesktopTaskBoardContent*",
    "**/DesktopTaskBoardSnapshot*",
    "**/DesktopNotesSnapshot*",
    "**/JournalReflectionContracts\$upsertDay\$\$inlined\$sortedByDescending\$1*",
    "**/DesktopTaskBoardContracts\$sortHabits\$\$inlined\$thenByDescending\$1*",
    "**/DesktopTaskBoardContracts\$sortHabits\$\$inlined\$thenBy\$1*",
    "**/DesktopTaskBoardContracts\$sortHabits\$\$inlined\$thenBy\$2*",
    "**/DesktopTaskBoardContracts\$sortHabits\$\$inlined\$thenBy\$3*",
    "**/DesktopTaskBoardContracts\$sortTasks\$\$inlined\$thenBy\$1*",
    "**/DesktopTaskBoardContracts\$sortTasks\$\$inlined\$thenBy\$2*",
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
                minimum = "0.94".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn("jacocoTestCoverageVerification")
}
