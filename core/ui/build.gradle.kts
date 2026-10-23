//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // NOT com.android.library. Since AGP 9 the classic library/application plugins are rejected
    // outright when org.jetbrains.kotlin.multiplatform is applied; this is the supported plugin for a
    // module that is both an Android library and a multiplatform module.
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
}

/**
 * The shared UI layer: one copy of every screen, rendered by Android and the desktop app alike.
 *
 * This module exists because the UI was forked. The Android screens in `app/` were never reused by the
 * desktop - the desktop got its own hand-written scaffold with hardcoded English strings - and the two
 * drifted immediately. The measurement that justifies merging them: of ~50,700 lines of UI and
 * view-model code, 96.2% touches no Android API at all, and the genuinely platform-bound remainder is
 * ~124 call sites (notifications 88, biometrics 30, file picker 6). Sharing the UI is therefore a MOVE,
 * not a rewrite.
 *
 * Targets:
 *  - `androidLibrary` so `:app` renders these composables directly
 *  - `jvm("desktop")` so `:desktop` renders the same ones
 *
 * Platform seams are injected by the caller, never branched on inside the shared code. Anything that
 * needs the OS - picking a file, notifications, biometrics - arrives as a parameter.
 */
kotlin {
    jvmToolchain(17)

    androidLibrary {
        namespace = "io.payanam.core.ui"
        compileSdk = 36
        minSdk = 28
        androidResources {
            enable = true
        }
    }

    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                // The onboarding slides use Lock, AutoGraph, Style and ArrowForward. Android already
                // ships material-icons-extended via :app, so the shared module matches it rather than
                // hand-drawing the four vectors and risking a visual difference from the Android app.
                implementation(compose.materialIconsExtended)
                // The navigation graph lives here too: without it every platform re-declares the same
                // routes and the desktop can only reach the screens it happens to wire itself.
                implementation(libs.navigation.compose.multiplatform)
                // The shared, platform-neutral half of the app: the logging contract the graph reports
                // through (AppLogger, installed by each platform's entry point) and the settings types the
                // graph passes around (FocusModePreset). A plain JVM module, so it is consumable from both
                // the Android and the desktop target of this one.
                implementation(project(":core:shared"))
                // The colour palette resolves a canonical dimension's colour from the taxonomy, so the
                // tokens belong beside the catalog they are derived from rather than duplicating it.
                implementation(project(":core:domain"))
                // NOTE: the DB-gate screens log through io.payanam.shared.logging.AppLogger (from
                // :core:shared, above), NOT io.payanam.common.logging.UnifiedLogger. The latter lives in
                // :core:common, which is an Android library, so depending on it here made
                // :core:ui - and therefore the JVM desktop, which consumes this module - unresolvable.
                // AppLogger.current returns the shared UnifiedLogger INTERFACE, whose Android and desktop
                // implementations each satisfy it, so the shared screens still raise the same log lines.
                // The copy lives in the leaf module below every layer, so both the composables here and the
                // shared ViewModels in :core:presentation read the SAME generated table. :core:ui depends on
                // :core:presentation, so keeping the strings here would make them unreadable from a
                // promoted ViewModel (a Gradle dependency cycle) - the strings module exists to break it.
                // `api`, not `implementation`: a shared composable takes `UiStrings` in its own signature, so
                // the type is part of this module's public surface. As `implementation` it would be hidden
                // from :app and :desktop, which both read the table through a composable that returns it.
                api(project(":core:strings"))
                // The shared preferences state carries a TaskFilter, which is state and lives with the
                // other shared ViewModel types. `api` because it appears in this module's public surface.
                api(project(":core:presentation"))
                // The charts are the one visual surface both platforms must share verbatim, so the chart
                // library is a commonMain dependency rather than an Android-only one. Vico 3.x's `compose`
                // artifact IS the multiplatform module (it depends on org.jetbrains.compose), and 3.x ships
                // no separate `core` coordinate - the com.patrykandpatrick.vico.core packages moved under
                // com.patrykandpatrick.vico.compose, which is why 2.x's `vico-core` line is gone.
                implementation(libs.vico.compose)
                implementation(libs.vico.compose.m3)
                // NOTE: compose-resources is deliberately NOT used. Its plugin in CMP 1.10.2 cannot
                // resolve AGP 9's multiplatform library target, so it assembles the string tables for
                // the desktop target only - the APK ends up with neither the keys nor their values.
                // Copy lives in a Kotlin table instead (see onboarding/OnboardingIntroStrings.kt).
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        // The screen tests render real composables, so they need the JVM Compose test harness. Desktop-only
        // rather than common: the harness is a JVM artifact, and the desktop target is where a shared screen
        // is rendered outside Android's instrumentation.
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.junit)
                // Truth for the settings-label table's completeness proof, which moved here from `:app`
                // with the table it guards; the app module had it and `:core:ui` did not.
                implementation(libs.truth)
                implementation(compose.desktop.uiTestJUnit4)
                // Skiko's host-native payload: the test harness renders through Skia, and without this the
                // first `Surface` initialisation throws (the desktop module gets it via `compose.desktop.currentOs`).
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

detekt {
    toolVersion = libs.versions.detekt.get()
    config = files("${rootProject.projectDir}/config/detekt/detekt.yml")
}

/**
 * Give the two colliding per-target detekt tasks their own report directories.
 *
 * detekt names a Kotlin-Multiplatform task after its *compilation*, and both `detektAndroidMain` and
 * `detektDesktopMain` derive their report paths from `compilation.name` - "main" for both. They
 * therefore write the SAME `reports/detekt/main.*` files, and whichever runs second erases the other's
 * verdict. Measured: the desktop task analyses 3 real files, the Android one 4, and only one of those
 * results survived on disk.
 *
 * This matters because both are now WIRED INTO THE GATE (`staticAnalysisCheck`, and `parity-fast.sh`
 * for the common source set). Both are green today, which is exactly the reason to fix it: a finding in
 * the desktop source set would be masked by the Android report, the phase would print its green tick,
 * and the module would be unchecked again - by a different route. That is the same "a green that does
 * not mean what it reads as" defect this module's wiring was added to remove.
 *
 * Each task keeps all five formats and gets a directory named after the source set it analyses, so
 * `reports/detekt/AndroidMain/` and `reports/detekt/DesktopMain/` can both be read. (The directory names
 * are the task suffixes, `AndroidMain`/`DesktopMain` - verified on disk, not assumed.)
 */
listOf("detektAndroidMain", "detektDesktopMain").forEach { detektTaskName ->
    tasks.named<io.gitlab.arturbosch.detekt.Detekt>(detektTaskName) {
        val sourceSet = name.removePrefix("detekt")
        reports {
            xml.required.set(true)
            xml.outputLocation.set(layout.buildDirectory.file("reports/detekt/$sourceSet/detekt.xml"))
            html.required.set(true)
            html.outputLocation.set(layout.buildDirectory.file("reports/detekt/$sourceSet/detekt.html"))
            txt.required.set(true)
            txt.outputLocation.set(layout.buildDirectory.file("reports/detekt/$sourceSet/detekt.txt"))
            sarif.required.set(true)
            sarif.outputLocation.set(layout.buildDirectory.file("reports/detekt/$sourceSet/detekt.sarif"))
            md.required.set(true)
            md.outputLocation.set(layout.buildDirectory.file("reports/detekt/$sourceSet/detekt.md"))
        }
    }
}


spotless {
    kotlin {
        target("**/*.kt")
        ktlint(libs.versions.ktlint.get())
        licenseHeaderFile("${rootProject.projectDir}/config/spotless/copyright.kt")
    }
}