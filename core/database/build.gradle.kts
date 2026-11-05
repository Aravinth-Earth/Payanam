//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    id("jacoco")
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
}

android {
    namespace = "io.payanam.core.database"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Room schema export
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
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

    sourceSets {
        getByName("main") {
            java.srcDir("src/main/kotlin")
        }
        getByName("androidTest") {
            assets.srcDir("$projectDir/schemas")
        }
    }
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
    implementation(project(":core:domain"))
    // Entities + mappers now live in the platform-neutral model module, consumed here through
    // Room/KSP and by :core:database-jvm through JDBC. Package names are preserved, so no imports
    // in this module changed.
    //
    // `api`, not `implementation`: the app module legitimately uses io.payanam.database.entity.*
    // types directly (UhabitsImporter, the import/export path, ...). While those classes were this
    // module's own source they were visible to consumers automatically; moving them out means this
    // edge must be exported or the app loses them off its compile classpath.
    api(project(":core:database-model"))
    implementation(project(":core:common"))
    // The shared schema contract (version, minimum migratable version, critical-table set) lives here
    // so the desktop can consume the same facts instead of hardcoding its own copies.
    implementation(project(":core:shared"))
    // The roll-up compute moved into shared code so the desktop can run the same
    // calculation, and this module is one of its callers. `implementation`, not `api`:
    // nothing outside this module needs the compute on its own classpath - the desktop
    // module takes its own edge to it.
    implementation(project(":core:scoring"))
    
    implementation(libs.core.ktx)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    
    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher)
    
    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    
    // Logging
    implementation(libs.timber)
    
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    // Test-only: SharedStateOverRoomTest drives a class from :core:presentation (the shared Notes
    // ViewModel) over this module's Room repositories - the Android half of the "a shared class
    // executes here" proof. No production code in this module reads :core:presentation.
    testImplementation(project(":core:presentation"))
    testImplementation(libs.coroutines.test)
    
    // Android test dependencies for integration tests
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.robolectric)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
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
    "**/R.class",
    "**/R$*.class",
    "**/BuildConfig.*",
    "**/Manifest*.*",
    "**/*Test*.*",
    "**/dao/**",
    "**/repository/**",
    "**/di/**",
    "**/migration/**",
    "**/PayanamDatabase*",
    "**/DatabaseHealthChecker*",
    "**/security/DatabaseEncryptionManager*",
    "**/security/DatabaseArtifactJanitor*",
    "**/security/DatabaseEncryptionMigrationSupport*",
    "**/mapper/**",              // Mapper classes are simple conversions
    "**/entity/ScoringConfig*",  // Data class only
    "**/entity/DayPlanPolicyEntity*", // Data class only
    "**/entity/DayTypeTemplatePreferenceEntity*", // Data class only
    // Disabled-module entities
    "**/entity/NoteTagEntity*"
)

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    val kotlinClasses = fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
        exclude(coverageExclusions)
    }
    val javaClasses = fileTree("${layout.buildDirectory.get()}/intermediates/javac/debug/classes") {
        exclude(coverageExclusions)
    }
    classDirectories.setFrom(kotlinClasses, javaClasses)
    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    executionData.setFrom(
        fileTree(layout.buildDirectory.get()).include(
            "jacoco/testDebugUnitTest.exec",
            "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"
        )
    )
}

tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn("testDebugUnitTest")
    val kotlinClasses = fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
        exclude(coverageExclusions)
    }
    val javaClasses = fileTree("${layout.buildDirectory.get()}/intermediates/javac/debug/classes") {
        exclude(coverageExclusions)
    }
    classDirectories.setFrom(kotlinClasses, javaClasses)
    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    executionData.setFrom(
        fileTree(layout.buildDirectory.get()).include(
            "jacoco/testDebugUnitTest.exec",
            "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"
        )
    )
    violationRules {
        rule {
            limit {
                minimum = "0.50".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn("jacocoTestCoverageVerification")
}
