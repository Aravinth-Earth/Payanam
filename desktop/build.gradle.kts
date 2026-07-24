//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
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
    implementation(project(":core:shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    runtimeOnly(libs.sqlite.jdbc)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(compose.desktop.uiTestJUnit4)
}

jacoco {
    toolVersion = "0.8.11"
}

tasks.withType<Test>().configureEach {
    useJUnit()
    extensions.configure(org.gradle.testing.jacoco.plugins.JacocoTaskExtension::class) {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

val desktopCoverageExclusions =
    listOf(
        "**/*Test*.*",
        "**/*\$Companion*",
        "**/ComposableSingletons*",
        "**/DesktopAppKt*",
        "**/DesktopAppSupport*",
        "**/DesktopAppModels*",
        "**/DesktopLifecycleState*",
        "**/DesktopJournalDimensionOption*",
        "**/DesktopJournalRouteKt*",
        "**/DesktopMainKt*",
        "**/DesktopNoteDialogState*",
        "**/DesktopNoteDimensionOption*",
        "**/DesktopNotesRouteKt*",
        "**/DesktopPersistenceStores*",
        "**/DesktopRouteScreensKt*",
        "**/DesktopRememberedState*",
        "**/DesktopShellCallbackSupportKt*",
        "**/DesktopShellCallbacks*",
        "**/DesktopShellRenderInputs*",
        "**/DesktopShellRenderState*",
        "**/DesktopStartupGateCallbacks*",
        "**/DesktopStartupGateState*",
        "**/DesktopStartupGateKt*",
        "**/DesktopDataHandoffStore*",
    )

tasks.named<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoTestReport") {
    dependsOn("test")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    val kotlinClasses =
        fileTree("${layout.buildDirectory.get()}/classes/kotlin/main") {
            exclude(desktopCoverageExclusions)
        }
    classDirectories.setFrom(kotlinClasses)
    sourceDirectories.setFrom(files("src/main/kotlin"))
    executionData.setFrom(
        fileTree(layout.buildDirectory.get()).include(
            "jacoco/test.exec",
            "jacoco/test*.exec",
        ),
    )
}

tasks.named<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn("test")
    val kotlinClasses =
        fileTree("${layout.buildDirectory.get()}/classes/kotlin/main") {
            exclude(desktopCoverageExclusions)
        }
    classDirectories.setFrom(kotlinClasses)
    sourceDirectories.setFrom(files("src/main/kotlin"))
    executionData.setFrom(
        fileTree(layout.buildDirectory.get()).include(
            "jacoco/test.exec",
            "jacoco/test*.exec",
        ),
    )
    violationRules {
        rule {
            limit {
                minimum = "0.75".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn("jacocoTestCoverageVerification")
}

compose.desktop {
    application {
        mainClass = "io.payanam.desktop.DesktopMainKt"
        nativeDistributions {
            modules("jdk.accessibility", "java.sql")
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi
            )
            packageName = "PayanamDesktop"
            packageVersion = "0.1.615"
            vendor = "Aravinth-Earth"
            description = "Payanam Desktop"
            windows {
                shortcut = true
                menu = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "0f9f504f-1be8-42d7-a8a7-72fd1d067d7e"
            }
        }
    }
}
