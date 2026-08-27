//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
import java.util.Properties

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

android {
    namespace = "io.payanam"
    compileSdk = 36

    defaultConfig {
          applicationId = "io.payanam"
          minSdk = 28
          targetSdk = 35
          versionCode = 1685
          versionName = "#1685 (20260827_125248)"

          buildConfigField("boolean", "MINIMAL_MODE", "false")
        buildConfigField("boolean", "SCORING_ENABLED", "true")
        buildConfigField("boolean", "RECURRING_TASKS_ENABLED", "true")
        buildConfigField("boolean", "REMINDERS_ENABLED", "true")
        buildConfigField("boolean", "TAGS_ENABLED", "true")
        buildConfigField("boolean", "PLANS_CTA_ENABLED", "true")
        buildConfigField("boolean", "FOCUS_MODE_SETTINGS_ENABLED", "true")
        buildConfigField("boolean", "SCORE_SETTINGS_ENABLED", "true")




        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Room schema export for migrations
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }

        // ARM64-only default for all builds (debug + release).
        // Pass -PuniversalBuild=true to include all ABIs (emulator, CI, Chromebook).
        val universalBuild = (project.findProperty("universalBuild") as String?)?.toBoolean() ?: false
        if (!universalBuild) {
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
            isMinifyEnabled = true
            isShrinkResources = true
            applicationIdSuffix = ".debug"
            resValue("string", "launcher_app_name", "@string/debug_launcher_app_name")
            if (hasDevDebugSigning) {
                signingConfig = signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            resValue("string", "launcher_app_name", "@string/app_name")
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

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    sourceSets {
        getByName("main") {
            java.srcDir("src/main/kotlin")
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
    implementation(project(":core:scoring"))
    implementation(project(":core:common"))

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

    // WorkManager for auto-backup
    implementation(libs.workmanager)
    implementation(libs.biometric)

    // Vico Charts
    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)
    implementation(libs.vico.core)

    // Logging
    implementation(libs.timber)

    // Testing
    testImplementation(libs.junit)
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















