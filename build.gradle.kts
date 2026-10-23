//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless) apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

tasks.register("coverageCheck") {
    dependsOn(
        ":desktop:jacocoTestCoverageVerification",
        ":core:shared:jacocoTestCoverageVerification",
        ":core:common:jacocoTestCoverageVerification",
        ":core:domain:jacocoTestCoverageVerification",
        ":core:scoring:jacocoTestCoverageVerification",
        ":core:database:jacocoTestCoverageVerification",
        // The JVM data layer. Its floor is a MEASURED 0.55 (see its build file for why that is low:
        // the accept path needs a real export, which a generated fixture cannot reach).
        //
        // :core:database-model is deliberately NOT here yet. It holds the entities + mappers but no
        // tests of its own — the mapper tests still live in :core:database — so any floor for it would
        // be 0 and therefore decorative, which is the exact defect this repo has already been burned by
        // (core/common and core/database pass vacuously). It joins this list together with the mapper
        // tests that give it real coverage.
        ":core:database-jvm:jacocoTestCoverageVerification",
        // The shared state layer. Hilt-free ViewModels, so a plain JVM floor applies.
        ":core:presentation:jacocoTestCoverageVerification"
    )
}

// ---------------------------------------------------------------------------------------------
// Direct-logging guard. UnifiedLogger is the only logging path that runs through LogSanitizer, so
// a raw `Timber.`, `android.util.Log`, `println(` or `System.out/err` write bypasses redaction
// entirely and still compiles - nothing caught it before this task. It runs from a plain JVM
// Gradle invocation: no Android plugin, no `:app` task dependency.
//
// Scanned: `app/src/main` and `core/*/src/main` only. Test source sets, generated `build/` output
// and the `desktop/` module (which has its own legitimate console output) are out of scope.
// It is a TEXT match on purpose - cheap and predictable, no parsing - so a hit inside a string
// literal or a trailing comment also counts; whole-line comments are skipped.
// ---------------------------------------------------------------------------------------------
// Repo-relative paths that ARE the logging implementation, so every pattern is allowed in them:
// the sink cannot route its own emission through itself, and FileLogger's stderr fires only after
// a write has already failed. Two files, deliberately by name - not the whole `logging/` package.
//
// All of the values below are declared INSIDE the task action rather than at script scope: a
// script-scope `val` captured by a task action pins a reference to the whole build script, which
// the configuration cache cannot serialise. That is why they are not file-level constants.
// ---------------------------------------------------------------------------------------------
tasks.register("verifyNoDirectLogging") {
    group = "verification"
    description = "Fails when production source logs outside UnifiedLogger (text match)."
    val repoRootPath = rootDir.absolutePath
    inputs
        .files(
            fileTree("app/src/main") { include("**/*.kt") },
            fileTree("core") { include("*/src/main/**/*.kt") },
            // core/ui is multiplatform, so it has no src/main and would otherwise escape the scan
            // entirely. Production source sets only; its desktopTest is where a diagnostic print
            // legitimately lives.
            fileTree("core/ui") { include("commonMain/**/*.kt", "androidMain/**/*.kt") },
        ).withPropertyName("sources")
    doLast {
        val patterns =
            listOf(
                Regex("""android\.util\.Log"""),
                Regex("""\bLog\.(?:d|e|i|w|v|wtf)\s*\("""),
                Regex("""\bTimber\."""),
                Regex("""\bprintln\s*\("""),
                Regex("""\bSystem\.(?:out|err)\."""),
            )
        val exemptFiles =
            setOf(
                "core/common/src/main/kotlin/io/payanam/common/logging/UnifiedLogger.kt",
                "core/common/src/main/kotlin/io/payanam/common/logging/FileLogger.kt",
            )
        val exemptLines =
            mapOf(
                // Terminal path: the log sink is what just proved broken, so there is nowhere else to
                // report to. Only `System.err.` is allowed - a new Timber/println in this file fails.
                "core/shared/src/main/kotlin/io/payanam/shared/concurrent/AppCoroutineScope.kt" to
                    Regex("""\bSystem\.(?:out|err)\."""),
                // Planting the debug tree is logging bootstrap, not a log call. Only `Timber.plant(` is
                // allowed - a `Timber.d(...)` added to this class still fails the gate.
                "app/src/main/kotlin/io/payanam/PayanamApp.kt" to Regex("""Timber\.plant\s*\("""),
            )
        val repoRoot = java.io.File(repoRootPath)
        val violations = mutableListOf<String>()
        inputs.files.files.sortedBy { it.path }.forEach { file ->
            // `invariantSeparatorsPath`-equivalent: the allowlist keys are written with `/`.
            val relative = file.relativeTo(repoRoot).path.replace('\\', '/')
            if (relative in exemptFiles) return@forEach
            val exemptLine = exemptLines[relative]
            file.readLines().forEachIndexed { index, line ->
                val code = line.trimStart()
                if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) {
                    return@forEachIndexed
                }
                val hits = patterns.mapNotNull { it.find(line)?.value }.distinct()
                if (hits.isEmpty() || (exemptLine != null && exemptLine.containsMatchIn(line))) {
                    return@forEachIndexed
                }
                violations += "  $relative:${index + 1}: [${hits.joinToString()}]  ${line.trim()}"
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine(
                        "Direct logging found in ${violations.size} line(s): these bypass UnifiedLogger, " +
                            "and therefore LogSanitizer.",
                    )
                    appendLine("Route them through UnifiedLogger instead.")
                    appendLine("This check is a text match, so a mention inside a string literal also counts:")
                    appendLine("reword it, or add a narrow, justified entry to directLoggingExemptLines.")
                    violations.forEach { appendLine(it) }
                },
            )
        }
    }
}

tasks.register("staticAnalysisCheck") {
    dependsOn(
        // Production code must not log outside UnifiedLogger (see verifyNoDirectLogging).
        "verifyNoDirectLogging",
        ":app:lintDebug",
        ":app:detekt",
        ":desktop:detekt",
        ":core:shared:detekt",
        ":core:common:detekt",
        ":core:domain:detekt",
        ":core:scoring:detekt",
        ":core:database:detekt",
        ":core:database-model:detekt",
        ":core:database-jvm:detekt",
        ":core:presentation:detekt",
        // :core:ui is Kotlin Multiplatform and has no src/main/kotlin, so the plain `detekt`
        // task reports NO-SOURCE and checks nothing. These are the per-target tasks that do.
        ":core:ui:detektMetadataCommonMain",
        ":core:ui:detektAndroidMain",
        ":core:ui:detektDesktopMain",
        // Applies the detekt plugin but was never listed here.
        ":core:strings:detekt"
    )
}

tasks.register("preCommitCheck") {
    dependsOn(
        ":app:spotlessCheck",
        ":desktop:spotlessCheck",
        ":core:shared:spotlessCheck",
        ":core:common:spotlessCheck",
        ":core:domain:spotlessCheck",
        ":core:scoring:spotlessCheck",
        ":core:database:spotlessCheck",
        ":core:database-model:spotlessCheck",
        ":core:database-jvm:spotlessCheck",
        ":core:presentation:spotlessCheck",
        ":core:ui:spotlessCheck",
        "staticAnalysisCheck"
    )
}
