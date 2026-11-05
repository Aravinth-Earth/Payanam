//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

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

/**
 * Generates the shared `UiStrings` accessors from this module's own Android string files.
 *
 * WHY THIS EXISTS:
 *
 * The copy has to live in dedicated string files - those are what translators work on, what Android's
 * missing-translation lint and Translations Editor understand, and what `values-<locale>/` is for. But the
 * shared screens are in `commonMain`, which has no `R` and no resource system at all, so it cannot read
 * them.
 *
 * Reading them at runtime instead would mean every platform exposing a resource loader: Android needs a
 * `Context` (`Resources` cannot be reached without one), the desktop needs its own path, and Windows and
 * macOS would each add another. That is the per-platform duplication this module exists to avoid.
 *
 * So the files are parsed at BUILD time and one typed accessor is generated for every platform. The string
 * files stay the single source of truth, the properties are compile-time checked (a missing key is a
 * compile error, not a blank label at runtime), and a screen costs zero glue: it takes the `UiStrings` for
 * the user's language and reads properties off it.
 *
 * WHY IT IS ITS OWN MODULE (it used to live in `:core:ui`):
 *
 * `:core:ui` depends on `:core:presentation`, so a shared ViewModel promoted into `:core:presentation`
 * could not read its own copy — importing `UiStrings` would close a Gradle dependency cycle
 * (`:core:presentation` -> `:core:ui` -> `:core:presentation`). Copy lives below every layer, so it
 * belongs in a module nobody depends on: `:core:strings` is a plain-JVM leaf that both `:core:ui` (the
 * composables) and `:core:presentation` (the shared ViewModels) consume. Hoisting the generator rather
 * than duplicating it keeps ONE source of truth for the copy and ONE generator - the alternative,
 * a second string table or a second accessor set, is exactly the divergence this migration exists to end.
 *
 * The generated file is released under `build/` and must never be hand-edited - it is overwritten on
 * every build.
 */
val uiStringsDir = layout.buildDirectory.dir("generated/uiStrings/main/kotlin")

val generateUiStrings by tasks.registering {
    description = "Generates the shared UiStrings accessors from src/main/res/values{,-ta}/strings.xml"
    group = "build"
    val sourceRes = layout.projectDirectory.dir("src/main/res")
    val destination = uiStringsDir
    // Resolved at configuration time: touching `layout` or `logger` inside doLast would capture the
    // script object, which the configuration cache (enabled in gradle.properties) refuses to serialize.
    val buildDirFile = layout.buildDirectory.get().asFile
    inputs.dir(sourceRes).withPropertyName("androidStringResources")
    outputs.dir(destination).withPropertyName("generatedUiStrings")
    doLast {
        val languages = listOf("" to "English", "-ta" to "Tamil")
        fun camel(key: String): String {
            val parts = key.split('_')
            return parts.first() + parts.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        }
        // Android string files use backslash escapes (\' \" \n \t \\ \@ \?) that a resource compiler
        // resolves but an XML parser does not. Without this the extracted copy would render a literal
        // backslash on every platform, e.g. "I\'ll choose later" instead of "I'll choose later".
        fun androidUnescape(value: String): String {
            val sb = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '\\' && i + 1 < value.length) {
                    when (value[i + 1]) {
                        'n' -> { sb.append('\n'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        '\\' -> { sb.append('\\'); i += 2 }
                        '\'' -> { sb.append('\''); i += 2 }
                        '"' -> { sb.append('"'); i += 2 }
                        '@' -> { sb.append('@'); i += 2 }
                        '?' -> { sb.append('?'); i += 2 }
                        else -> { sb.append(c); i += 1 }
                    }
                } else {
                    sb.append(c)
                    i += 1
                }
            }
            return sb.toString()
        }
        val tables = languages.map { (suffix, typeName) ->
            val file = sourceRes.asFile.resolve("values$suffix/strings.xml")
            require(file.isFile) { "Missing string file: $file" }
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
            }.newDocumentBuilder().parse(file)
            val nodes = doc.getElementsByTagName("string")
            typeName to (0 until nodes.length).associate { i ->
                val el = nodes.item(i)
                camel(el.attributes.getNamedItem("name").nodeValue) to androidUnescape(el.textContent)
            }
        }
        // Parity is required, not merely checked: a language missing a key would compile and then render a
        // blank label, which is the failure mode these files are supposed to make impossible.
        val (referenceName, referenceValues) = tables.first()
        tables.drop(1).forEach { (name, values) ->
            val missing = referenceValues.keys - values.keys
            val extra = values.keys - referenceValues.keys
            require(missing.isEmpty() && extra.isEmpty()) {
                "String tables disagree: $name is missing $missing and has extra $extra versus $referenceName"
            }
        }

        val sb = StringBuilder()
        sb.append("//  SPDX-FileCopyrightText: 2026 Aravinth-Earth\n")
        sb.append("//  SPDX-License-Identifier: AGPL-3.0-or-later\n")
        sb.append("//  GENERATED FILE - do not edit. Source: src/main/res/values{,-ta}/strings.xml\n")
        sb.append("//  Regenerate with: ./gradlew :core:strings:generateUiStrings\n")
        sb.append("package io.payanam.core.ui.text\n\n")
        sb.append("/**\n")
        sb.append(" * Every string the shared screens and shared ViewModels render, for each shipped language.\n")
        sb.append(" *\n")
        sb.append(" * Derived from this module's Android string files, so those remain the one place the copy is\n")
        sb.append(" * maintained. Property names are the resource names in camel case.\n")
        sb.append(" */\n")
        sb.append("interface UiStrings {\n")
        referenceValues.keys.sorted().forEach { sb.append("    val $it: String\n") }
        sb.append("\n    companion object {\n")
        sb.append("        /** Copy for [language]. Every shipped language is complete - see the generator's parity check. */\n")
        sb.append("        fun of(language: UiLanguage): UiStrings =\n")
        sb.append("            when (language) {\n")
        languages.forEach { (_, typeName) ->
            val langName = if (typeName == "English") "ENGLISH" else "TAMIL"
            sb.append("                UiLanguage.$langName -> $typeName\n")
        }
        sb.append("            }\n")
        tables.forEach { (typeName, values) ->
            sb.append("\n        private object $typeName : UiStrings {\n")
            values.keys.sorted().forEach { key ->
                sb.append("            override val $key: String = ${values.getValue(key).let { v ->
                    "\"" + v
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("$", "\${'$'}")
                        // A resolved escape can be a real newline; emitting it raw would break the
                        // Kotlin literal that carries it.
                        .replace("\r", "\\r")
                        .replace("\n", "\\n") + "\""
                }}\n")
            }
            sb.append("        }\n")
        }
        sb.append("    }\n")
        sb.append("}\n")

        val out = destination.get().asFile.resolve("io/payanam/core/ui/text/UiStrings.kt")
        out.parentFile.mkdirs()
        out.writeText(sb.toString())
        System.out.println(
            "generateUiStrings: wrote ${out.relativeTo(buildDirFile)} " +
                "(${referenceValues.size} strings x ${tables.size} languages)",
        )
    }
}

kotlin {
    sourceSets.named("main") {
        kotlin.srcDir(generateUiStrings)
    }
}

dependencies {
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

spotless {
    kotlin {
        target("**/*.kt")
        ktlint(libs.versions.ktlint.get())
        licenseHeaderFile("${rootProject.projectDir}/config/spotless/copyright.kt")
    }
}
