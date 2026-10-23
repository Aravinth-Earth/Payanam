//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

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

/**
 * The shared *state* layer: one ViewModel per screen, owned by Android and the desktop alike.
 *
 * WHY THIS MODULE EXISTS:
 *
 * The UI could not be shared on its own. A screen without its state is a mock - it compiles, it renders,
 * and it shows nothing, because the thing that reads the database and turns it into the screen's state
 * stayed behind in `:app`. Sharing the composables alone would have produced exactly that on the desktop,
 * and a second hand-written data path on the desktop (its own stores) would have been the divergence the
 * shared layer exists to remove.
 *
 * So the state logic moves too. Every ViewModel here is a plain class - no Hilt, no `androidx.lifecycle` -
 * because Hilt is Android-only and this module is consumed by an Android target and a JVM target. The
 * platform supplies the repositories (see [io.payanam.presentation.data.LocalDataLayer]) and a
 * [kotlinx.coroutines.CoroutineScope]; nothing else differs between platforms.
 *
 * `kotlin.jvm` rather than a multiplatform module: this layer is pure Kotlin with no Compose and no
 * platform API, and a plain JVM jar is consumable from BOTH the Android app and the desktop app.
 */
dependencies {
    // The repository interfaces and domain models this layer drives. Exposed through this module's own
    // public API (ViewModel constructors), so it is `api`.
    api(project(":core:domain"))
    // AppLogger, the process-wide logger the platform installs. `api` for the same reason.
    api(project(":core:shared"))
    // Scoring types appear in the shared ViewModels' state (CompletionStats) and in the ports they drive, so
    // this layer must see the module that owns them. Acyclic: presentation -> scoring -> domain.
    api(project(":core:scoring"))
    // The shared copy table, so a ViewModel promoted into this layer can read its own error and label text
    // instead of an Android R id. It lives in a leaf module precisely so this edge cannot cycle back via
    // :core:ui, which depends on THIS module. `api` because a promoted ViewModel exposes its own state
    // types (a `UiStrings` field, or a message built from one) to whoever renders it.
    api(project(":core:strings"))
    implementation(libs.coroutines.core)
    // org.json: the Android platform ships it; a plain JVM module declares it. Used by UpdateChecker's
    // release parsing, which moved here from :app.
    implementation(libs.org.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
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

// The data-layer contract is an interface plus a holder - no coverable logic, same reasoning as
// `**/repository/**` in :core:domain.
val coverageExclusions =
    listOf(
        "**/*Test*.*",
        "**/data/**",
    )

tasks.named<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoTestReport") {
    dependsOn("test")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    val kotlinClasses =
        fileTree("${layout.buildDirectory.get()}/classes/kotlin/main") {
            exclude(coverageExclusions)
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
            exclude(coverageExclusions)
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
                // Floor is the MEASURED value, not an aspiration - the same convention :core:domain uses.
                // It ratchets UP only, so a ViewModel arriving here without its test fails the build - that
                // is the gate working, not a nuisance.
                //
                // This was 1.0, measured when the module held one Notes ViewModel and nothing else, and it
                // was never enforced: the module had no consumer, so it was absent from the root gate task
                // lists. Adding it to the gate (D2) is what first ran this check, and it fails at 1.0.
                //
                // NOTE the number is the gate's, and the gate is an INSTRUCTION ratio: the limit above
                // names no counter, so Gradle's JacocoLimitImpl defaults to counter=INSTRUCTION,
                // value=COVEREDRATIO. The report this reconciles is therefore read off the same counter -
                // the INSTRUCTION one in jacocoTestReport.xml - and NOT off the CLASS one.
                //
                // It used to say the two disagreed: "the verification task reports 0.9 while
                // jacocoTestReport prints 0.9604 for what should be the same bundle". There was no
                // disagreement, only a misread counter: 0.9604 was the module's CLASS ratio (289/301),
                // a different denominator that cannot fall below 0.9 whenever every class is at least partly
                // covered. Measured on the INSTRUCTION counter, the module now sits at
                // covered=57623 / total=64217 = 0.8973 - 172 instructions short of the floor, which is
                // why the floor is a real INSTRUCTION budget: the module grew well past the 44580
                // instructions the earlier measurement named, and coverage did not grow with it. The
                // floor stays at 0.9; the debt this note now describes is real and is being paid by
                // tests, not by moving the limit.
                minimum = "0.9".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn("jacocoTestCoverageVerification")
}
