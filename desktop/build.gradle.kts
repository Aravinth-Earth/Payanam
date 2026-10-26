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

// Declared version and short sha, shared with :app through the root extra properties.
val declaredVersionName: String = rootProject.extra["payanamVersionName"] as String
val declaredVersionCode: Int = rootProject.extra["payanamVersionCode"] as Int
val declaredShortSha: String = rootProject.extra["payanamShortSha"] as String

version = declaredVersionName

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

// Host-OS detection at CONFIGURATION time. NOTE: the generateBuildInfo task also reads os.name, but
// that read happens inside its doLast block (execution phase) and therefore cannot drive dependency
// selection — dependencies resolve during configuration, not execution.
val isLinuxHost = providers.systemProperty("os.name").get().lowercase().contains("linux")

dependencies {
    implementation(project(":core:shared"))
    // Added for the onboarding dimension step, which reads the canonical dimension catalog so the
    // desktop lists the same dimensions as Android rather than a copy. JVM-only module, no Android
    // dependency, so it is safe on the desktop classpath.
    implementation(project(":core:domain"))
    implementation(project(":core:backup"))
    // The shared data layer, and the shared state layer over it. Both are wired into startup in D4/D5;
    // declaring them here is what puts them on the desktop's classpath at all.
    implementation(project(":core:database-jvm"))
    implementation(project(":core:presentation"))
    // The shared UI layer: same screens as Android. This is the module that makes the desktop
    // onboarding the Android onboarding rather than a lookalike scaffold.
    implementation(project(":core:ui"))
    implementation(compose.desktop.currentOs)
    // Material 3, so this module can name `MaterialTheme.colorScheme` itself. `:core:ui` declares it as
    // `implementation`, which does not re-export it, so the desktop's shell chrome cannot read a shared
    // theme token without a declaration here.
    //
    // The compose plugin's accessor - the SAME one `:core:ui` uses - rather than the
    // `libs.compose.material3` catalog alias, which resolves to androidx's Android artifact. One
    // coordinate means one `MaterialTheme.colorScheme` on the classpath, so a token read here and a
    // token read inside a shared screen resolve the same `LocalColorScheme` instead of two competing
    // ones. JVM-only module, no Android dependency, so it is safe on the desktop classpath.
    implementation(compose.material3)
    implementation(libs.coroutines.core)
    // The shared screens call repeatOnLifecycle (TimeScreen), which resolves Dispatchers.Main. On
    // Android that comes from coroutines-android; the desktop equivalent is coroutines-swing, which
    // maps Main onto the Swing EDT - the same thread Compose Desktop already dispatches frames on.
    // Without it the app dies on its landing route with "Main dispatcher is missing".
    implementation(libs.coroutines.swing)
    implementation(libs.serialization.json)
    runtimeOnly(libs.sqlite.jdbc)

    // Desktop-only: opens Android-exported ENCRYPTED databases. The core artifact holds the JDBC
    // driver; the per-platform artifact carries the native SQLCipher payload, so exactly one is
    // selected for the host (declaring both would add ~15-20 MB of unusable natives per build).
    // Omitting the platform artifact does NOT fail the build — it fails at runtime with
    // UnsatisfiedLinkError, which :core:database-jvm reports as a boot issue rather than a crash.
    // NOTE: nothing in this module opens a database yet. The JDBC backend is implemented in
    // :core:database-jvm (which this module does not depend on); this module carries only the
    // driver artifact until that wiring lands. Do NOT reach for net.zetetic:sqlcipher-android
    // here: that artifact is Android-only.
    implementation(libs.sqlcipher.jvm.jdbc)
    runtimeOnly(
        if (isLinuxHost) libs.sqlcipher.jvm.linux.x64 else libs.sqlcipher.jvm.windows.x64,
    )

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
                // 0.75 restored. The floor fell to 0.52 when the desktop's own plain-SQLite data
                // layer was deleted along with its 8 store test classes - the best-covered code in
                // this module - so the old number no longer described the same set. The shared data
                // layer that replaced those stores brought the ratio back up (measured 0.7571
                // instruction / 0.8042 line), so the floor returns to 0.75 and ratchets from here:
                // it fails the build on any fall.
                minimum = "0.75".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn("jacocoTestCoverageVerification")
}

// ── Build info generation (matches Android's BuildConfig pattern) ──────────
// Build scripts pass -PbuildPlatform=Linux|Windows -PbuildTimestamp=... -PbuildType=debug|release.
// Version and sha come from the root extra properties; this task generates DesktopBuildInfo.kt.
val buildPlatform: String = project.findProperty("buildPlatform")?.toString() ?: ""
val buildTimestamp: String = project.findProperty("buildTimestamp")?.toString() ?: ""
// -PbuildType=debug|release, set by the build scripts, which are the only sanctioned build entry points.
// An absent value means the module was built directly (a test run, an IDE), and that is NOT treated as a
// debug build: the build-type default for debug logging must never switch itself on by accident.
val buildType: String = project.findProperty("buildType")?.toString() ?: ""

tasks.register("generateBuildInfo") {
    notCompatibleWithConfigurationCache("References script-level project properties")

    val outputDir = layout.buildDirectory.dir("generated/buildinfo")

    // Always run — no outputs declaration to avoid Gradle caching stale values
    outputs.upToDateWhen { false }

    doLast {
        val osName = System.getProperty("os.name").lowercase()
        val platform = buildPlatform.ifBlank {
            if (osName.contains("linux")) "Linux" else "Windows"
        }
        val timestamp = buildTimestamp.ifBlank { "00000000_000000" }
        val isDebugBuild = buildType.equals("debug", ignoreCase = true)

        val dir = outputDir.get().asFile.resolve("io/payanam/desktop")
        dir.mkdirs()
        dir.resolve("DesktopBuildInfo.kt").writeText(
            """
package io.payanam.desktop
/** Auto-generated at build time by generateBuildInfo task. DO NOT EDIT. */
object DesktopBuildInfo {
    const val PLATFORM = "$platform"
    const val VERSION_NAME = "$declaredVersionName"
    const val VERSION_CODE = $declaredVersionCode
    const val BUILD_SHORT_SHA = "$declaredShortSha"
    const val BUILD_TIMESTAMP = "$timestamp"
    const val VERSION_DISPLAY_NAME = "$declaredVersionName ($declaredShortSha)"
    const val BUILD_NAME = "Payanam_${platform}_${declaredVersionName}_${declaredShortSha}_$timestamp"
    /**
     * Whether this build was produced as a debug build (`-PbuildType=debug`).
     *
     * The desktop counterpart of Android's `BuildConfig.DEBUG`, and the reason it exists: the build
     * scripts computed a debug/release value and only printed it, so the running app could not tell a
     * debug build from a release one and debug logging had no build-type default to follow.
     */
    const val IS_DEBUG_BUILD = $isDebugBuild
}
""".trimIndent()
        )
    }
}

sourceSets.main {
    kotlin.srcDir(layout.buildDirectory.dir("generated/buildinfo"))
    // The shared brand assets are the single source for every platform build. Putting them on the
    // classpath makes the same file power both the packaged installer icon (nativeDistributions
    // iconFile, below) and the runtime window icon (DesktopMain), so the launcher entry and the
    // taskbar entry cannot drift apart or fall back to the generic Java icon.
    resources.srcDir(rootProject.file("assets/branding"))
    // ...but only the PNG is needed at runtime. The .ico is consumed by the Windows packaging step
    // straight from assets/branding, and at ~370 KB of uncompressed BMP entries it has no business
    // riding along inside every platform's application jar.
    resources.exclude("payanam-icon.ico")
}

tasks.named("compileKotlin") {
    dependsOn("generateBuildInfo")
}

compose.desktop {
    application {
        mainClass = "io.payanam.desktop.DesktopMainKt"
        nativeDistributions {
            modules("jdk.accessibility", "java.sql")
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
            )
            // One name, everywhere: the launcher name, the Windows exe/install dir, and (lowercased by
            // jpackage) the Debian package name all derive from this. It is "Payanam" - the same name the
            // F-Droid listing uses - not "PayanamDesktop", which leaked a technical qualifier into the
            // window title, the app grid and the binary path.
            packageName = "Payanam"
            // Declared version. Must stay 3-component: Exe/MSI reject anything else at configuration time.
            packageVersion = declaredVersionName
            vendor = "Aravinth-Earth"
            description = "Navigate your life journey across dimensions privately."
            // Icon comes from the shared brand assets (assets/branding), the same file Android's
            // launcher icon and the F-Droid listing render from. Per-format because the platforms
            // demand different containers: Linux installers take a PNG, Windows installers take an
            // ICO (multi-size, so Explorer picks a crisp one at every zoom level).
            linux {
                iconFile.set(rootProject.file("assets/branding/payanam-icon-512.png"))
                // jpackage writes `Categories=<menuGroup>` into the .desktop, defaulting to the literal
                // "Unknown" - which desktop-file-validate rejects as an unregistered value, and which
                // parks the app in a catch-all section instead of a real menu group. `Utility;` is the
                // closest registered main category for a personal life/task organiser (freedesktop has
                // no "Productivity"), and the trailing semicolon is required by the desktop-entry spec.
                menuGroup = "Utility;"
            }
            windows {
                shortcut = true
                menu = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "0f9f504f-1be8-42d7-a8a7-72fd1d067d7e"
                iconFile.set(rootProject.file("assets/branding/payanam-icon.ico"))
                // Exe/MSI require MAJOR.MINOR.BUILD (MAJOR, MINOR <= 255; BUILD <= 65535) at configuration
                // time, so the declared version must stay below those caps.
                msiPackageVersion = declaredVersionName
                exePackageVersion = declaredVersionName
            }
        }
        // ProGuard runs for the release distribution and STAYS enabled - the point of the rules
        // file is a correct rule, not a disabled optimizer. The file is required because the
        // bundled SQLite JDBC driver probes for SLF4J reflectively and falls back to
        // java.util.logging when no SLF4J binding is present. The desktop ships no SLF4J, so that
        // missing class is expected, but ProGuard cannot see the try/catch fallback and fails the
        // release build with "Please correct the above warnings first".
        buildTypes {
            release {
                proguard {
                    isEnabled.set(true)
                    configurationFiles.from(rootProject.file("desktop/proguard-desktop.pro"))
                }
            }
        }
    }
}
