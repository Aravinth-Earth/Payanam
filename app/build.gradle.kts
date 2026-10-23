//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
import java.util.Properties
import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
}

val localPropsFile = rootProject.file("local.properties")
val localProps = Properties().apply {
    if (localPropsFile.exists()) localPropsFile.inputStream().use(::load)
}

val devDebugSigningPropsFile = rootProject.file("keystore-dev-debug.properties")
val devDebugSigningProps = Properties().apply {
    if (devDebugSigningPropsFile.exists()) {
        devDebugSigningPropsFile.inputStream().use(::load)
    }
}
val hasDevDebugSigning = devDebugSigningPropsFile.exists()

val releaseSigningPropsFile = rootProject.file("keystore-release.properties")
val releaseSigningProps = Properties().apply {
    if (releaseSigningPropsFile.exists()) releaseSigningPropsFile.inputStream().use(::load)
}
val hasReleaseSigning = releaseSigningPropsFile.exists()

// The version comes from the build script's `-PbuildNumber` flag - the same property the desktop module
// reads - so the number the gate prints, the number in the APK filename and the number the installed app
// reports are one number. It used to be hardcoded, which meant a build script could print "Build #2028"
// and hand over an APK that told the user it was 1825.
//
// With no flag (a bare `./gradlew assembleDebug`) it falls back to the tracked build counter, which is
// where the script's own buildNumber comes from, so the value is never a guess and never stale.
val buildNumber: Int =
    (project.findProperty("buildNumber") as String?)?.toIntOrNull()
        ?: run {
            val counterFile = rootProject.file("build-tools/tracking/build-counter.json")
            check(counterFile.exists()) { "Build counter missing: ${counterFile.path}" }
            val counterJson = counterFile.readText()
            val androidBlock =
                requireNotNull(
                    Regex("\"android\"\\s*:\\s*\\{([^}]*)\\}").find(counterJson)?.groupValues?.get(1),
                ) { "No android block in ${counterFile.path}; cannot resolve the app version." }
            requireNotNull(
                Regex("\"builds\"\\s*:\\s*(\\d+)").find(androidBlock)?.groupValues?.get(1)?.toIntOrNull(),
            ) { "No android.builds value in ${counterFile.path}; cannot resolve the app version." }
        }

// F-Droid build: the recipe passes -PfdroidBuild=true. That build must not ship
// the self-updater nor REQUEST_INSTALL_PACKAGES (see src/fdroid/AndroidManifest.xml).
val fdroidBuild = (project.findProperty("fdroidBuild") as String?)?.toBoolean() ?: false

android {
    namespace = "io.payanam"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
          applicationId = "io.payanam"
          minSdk = 28
          targetSdk = 35
          versionCode = buildNumber
          versionName = buildNumber.toString()

          buildConfigField("boolean", "MINIMAL_MODE", "false")
        buildConfigField("boolean", "SCORING_ENABLED", "true")
        buildConfigField("boolean", "RECURRING_TASKS_ENABLED", "true")
        buildConfigField("boolean", "REMINDERS_ENABLED", "true")
        buildConfigField("boolean", "TAGS_ENABLED", "true")
        buildConfigField("boolean", "PLANS_CTA_ENABLED", "true")
        buildConfigField("boolean", "FOCUS_MODE_SETTINGS_ENABLED", "true")
        buildConfigField("boolean", "SCORE_SETTINGS_ENABLED", "true")
        buildConfigField("boolean", "FDROID_BUILD", "$fdroidBuild")




        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Room schema export for migrations
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }

        // Universal APK by default: every ABI ships, so a build from any channel
        // installs on any device. Pass -Parm64Only=true for the arm64-only APK used
        // for local USB iteration.
        val arm64Only = (project.findProperty("arm64Only") as String?)?.toBoolean() ?: false
        if (arm64Only) {
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    signingConfigs {
        if (hasDevDebugSigning) {
            getByName("debug") {
                val storePath = requireNotNull(devDebugSigningProps.getProperty("storeFile")) {
                    "keystore-dev-debug.properties is missing required key: storeFile"
                }
                this.storeFile = rootProject.file(storePath)
                storePassword = requireNotNull(devDebugSigningProps.getProperty("storePassword")) {
                    "keystore-dev-debug.properties is missing required key: storePassword"
                }
                keyAlias = requireNotNull(devDebugSigningProps.getProperty("keyAlias")) {
                    "keystore-dev-debug.properties is missing required key: keyAlias"
                }
                keyPassword = requireNotNull(devDebugSigningProps.getProperty("keyPassword")) {
                    "keystore-dev-debug.properties is missing required key: keyPassword"
                }
            }
        }
        if (hasReleaseSigning) {
            create("release") {
                val storePath = requireNotNull(releaseSigningProps.getProperty("storeFile")) {
                    "keystore-release.properties is missing required key: storeFile"
                }
                this.storeFile = rootProject.file(storePath)
                storePassword = requireNotNull(releaseSigningProps.getProperty("storePassword")) {
                    "keystore-release.properties is missing required key: storePassword"
                }
                keyAlias = requireNotNull(releaseSigningProps.getProperty("keyAlias")) {
                    "keystore-release.properties is missing required key: keyAlias"
                }
                keyPassword = requireNotNull(releaseSigningProps.getProperty("keyPassword")) {
                    "keystore-release.properties is missing required key: keyPassword"
                }
            }
        }
    }

    buildTypes {
        debug {
            // The in-process instrumentation tier needs an unminified build. When the debug
            // build type is minified, AGP also runs R8 over the androidTest APK, and R8 strips
            // test-runtime classes that are only reached indirectly (kotlin.LazyKt and friends),
            // which crashes AndroidJUnitRunner before the first test is executed:
            //   NoClassDefFoundError: Failed resolution of: Lkotlin/LazyKt;
            //     at androidx.test.platform.io.TestDirCalculator.<init>
            // build-android.ps1 -RunInProcess passes -Ppayanam.noMinify=true for this reason.
            val noMinify = (project.providers.gradleProperty("payanam.noMinify").orNull
                ?: "false").toBoolean()
            isMinifyEnabled = !noMinify
            isShrinkResources = !noMinify
            applicationIdSuffix = ".debug"
            if (hasDevDebugSigning) {
                signingConfig = signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // The refusal to emit an unsigned release is opt-in, not unconditional: a
                // third-party builder (F-Droid) compiles this repo from source with a bare
                // `assembleRelease` and no release keystore, and an unconditional throw would
                // break that build outright. Our own pipeline passes
                // -Ppayanam.requireReleaseSigning=true, and only there does the build stop
                // rather than produce a release artefact that carries no release signature.
                val requireReleaseSigning =
                    (project.providers.gradleProperty("payanam.requireReleaseSigning").orNull
                        ?: "false").toBoolean()
                if (requireReleaseSigning) {
                    throw GradleException(
                        "Release signing is required but keystore-release.properties is missing. " +
                            "This build refuses to emit an unsigned release artefact. Provide " +
                            "keystore-release.properties at the repository root (storeFile, " +
                            "storePassword, keyAlias, keyPassword), or build without " +
                            "-Ppayanam.requireReleaseSigning=true. Third-party builders (F-Droid) " +
                            "build without that property and are unaffected.",
                    )
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // Robolectric unit tests must see the app's MERGED resources: without this the app's own
    // strings resolve to unresolved IDs at runtime and getString() throws
    // Resources$NotFoundException (measured in TaskReminderSchedulerTest).
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // Stated explicitly rather than left to the default, so the security floor in lint.xml
    // cannot be quietly switched off by a future AGP default change. Warnings stay warnings
    // on purpose: the existing warning backlog is large and warningsAsErrors would fail every
    // build for unrelated reasons, which is how a gate gets bypassed instead of fixed.
    lint {
        abortOnError = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    sourceSets {
        getByName("main") {
            java.srcDir("src/main/kotlin")
        }
        if (fdroidBuild) {
            getByName("release").manifest.srcFile("src/fdroid/AndroidManifest.xml")
        }
    }
}

detekt {
    toolVersion = libs.versions.detekt.get()
    config.setFrom(files("${rootProject.projectDir}/config/detekt/detekt.yml"))
}

spotless {
    kotlin {
        target("**/*.kt")
        ktlint(libs.versions.ktlint.get())
            .editorConfigOverride(
                mapOf(
                    "ktlint_function_naming_ignore_when_annotated_with" to "Composable"
                )
            )
        licenseHeaderFile("${rootProject.projectDir}/config/spotless/copyright.kt")
    }
}

configurations.configureEach {
    if (name.endsWith("RuntimeClasspath") && !name.contains("Test") && !name.contains("androidTest")) {
        exclude(group = "com.android.tools.emulator")
        exclude(group = "io.grpc")
        exclude(group = "io.netty")
        exclude(group = "org.bouncycastle")
    }
}

dependencies {
    // Project modules
    implementation(project(":core:shared"))
    implementation(project(":core:domain"))
    implementation(project(":core:database"))
    implementation(project(":core:backup"))
    // The shared state layer: the ViewModels move here one feature at a time, and the Android app
    // consumes them like any other platform.
    implementation(project(":core:presentation"))
    implementation(project(":core:scoring"))
    implementation(project(":core:common"))
    // The shared UI layer. Onboarding renders from :core:ui so Android and the desktop draw the
    // same composable from one definition instead of two copies that drift.
    implementation(project(":core:ui"))

    // Core Android
    implementation(libs.core.ktx)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Activity & Lifecycle
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    // Room (for direct DB/DAO access via DatabaseSessionManager)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    // androidx.sqlite: direct dependency for SupportSQLiteOpenHelper/FrameworkSQLiteOpenHelperFactory
    // used by the AppDatabaseInitPorts/UhabitsImporter reads (Room does not expose these as api).
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.sqlite.framework)

    // WorkManager for auto-backup
    implementation(libs.workmanager)
    implementation(libs.biometric)

    // Vico Charts
    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)

    // Logging
    implementation(libs.timber)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    
    // Unit test dependencies
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.runtime)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
}















