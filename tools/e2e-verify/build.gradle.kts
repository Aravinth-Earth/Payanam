//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("io.payanam.tools.e2everify.MainKt")
}

// Paths on the command line are written from the repository root (the artifacts live under
// build-logs/e2e), so the run task must execute there rather than in the module directory.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

dependencies {
    // The verifier reads the SAME encrypted file the app wrote, with the same SQLCipher JDBC driver and
    // the same open guards the desktop uses — not a second, hand-rolled SQLCipher configuration that
    // could disagree with the app about key derivation or page format.
    implementation(project(":core:database-jvm"))
    // `:core:database-jvm` ships the native payload only for its own tests; a JVM program that opens a
    // file needs it on its runtime classpath too. Same host-platform choice as `:desktop`.
    val isLinuxHost = providers.systemProperty("os.name").get().lowercase().contains("linux")
    runtimeOnly(
        if (isLinuxHost) libs.sqlcipher.jvm.linux.x64 else libs.sqlcipher.jvm.windows.x64,
    )
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

// No tests in this module: its assertions run against a real device run's artifacts, which only exist
// after `maestro test .maestro/sessions` + `adb pull`. Unit-testing the parser in isolation is covered
// by the failable-checks policy in docs/e2e/README.md rather than a green test that proves nothing.
tasks.named<Test>("test") {
    enabled = false
}

tasks.withType<Test>().configureEach {
    extensions.findByType(JacocoTaskExtension::class.java)?.isEnabled = false
}
