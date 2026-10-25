//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.version

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pins the declared-version contract in `gradle/version.properties`.
 *
 * Three separate things can rot without anyone noticing until an install is refused:
 *  - the `versionCode` line stops being a literal number (F-Droid's recipe greps that key and cannot
 *    evaluate arithmetic, so a computed value would silently give the recipe nothing to read);
 *  - the code stops matching the name, because someone edited one of the two lines by hand;
 *  - the app module goes back to taking its version from the build counter, which would make a bare
 *    `./gradlew assembleDebug` depend on a file that only the build scripts maintain.
 *
 * The packing below is re-implemented here rather than imported from the build script on purpose: a
 * test that calls the implementation it is checking proves nothing about that implementation, and the
 * build script's own value is not on the unit-test classpath anyway.
 */
class DeclaredVersionTest {

    @Test
    fun versionFileKeepsTheLiteralKeysTheFdroidRecipeGreps() {
        val lines = versionFileLines()
        assertTrue(
            "gradle/version.properties must keep a literal 'versionCode=<digits>' line; F-Droid greps it " +
                "and cannot evaluate arithmetic. Found: $lines",
            lines.any { it.matches(Regex("""versionCode=\d+""")) },
        )
        assertTrue(
            "gradle/version.properties must keep a literal 'version=<major>.<minor>.<patch>' line. Found: $lines",
            lines.any { it.matches(Regex("""version=\d+(\.\d+){0,2}""")) },
        )
    }

    @Test
    fun declaredVersionCodeEqualsThePackedVersionName() {
        val declared = readDeclaredVersion()
        assertEquals(
            "versionCode must equal packed(version) = major*1_000_000 + minor*1_000 + patch. " +
                "Run ./gradlew bumpVersion -Pto=<new version> instead of editing one line by hand.",
            packed(declared.name),
            declared.code,
        )
    }

    @Test
    fun aMismatchedCodeIsDetectable() {
        val version = "2.5.7"
        // The matching pair passes the same check the app build script runs.
        validate(version, packed(version))
        // A code that disagrees with the name by one is caught, and the message points at the fix.
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                validate(version, packed(version) + 1)
            }
        assertTrue(
            "the drift message must tell the reader to run bumpVersion, got: ${failure.message}",
            failure.message.orEmpty().contains("bumpVersion"),
        )
    }

    @Test
    fun packingKeepsDistinctVersionsDistinct() {
        assertEquals(1_002_003, packed("1.2.3"))
        assertEquals(1_000_000, packed("1"))
        assertEquals(1_001_000, packed("1.1"))
        assertNotEquals(packed("1.2.3"), packed("1.2.4"))
        // The 0..999 cap is what keeps versions distinct: without it "1.1002" would pack to 2_002_000,
        // the same code as "2.2".
        assertThrows(IllegalArgumentException::class.java) { packed("1.1002") }
        assertThrows(IllegalArgumentException::class.java) { packed("1.2.3.4") }
        assertThrows(IllegalArgumentException::class.java) { packed("1.x.3") }
    }

    @Test
    fun appModuleVersionsFromTheDeclaredFileAndNotTheBuildCounter() {
        val appBuild = repoRoot().resolve("app/build.gradle.kts").toFile().readText()
        assertTrue(
            "app/build.gradle.kts must take both version surfaces from the shared declared version",
            appBuild.contains("versionCode = declaredVersionCode") &&
                appBuild.contains("versionName = declaredVersionName"),
        )
        assertFalse(
            "app/build.gradle.kts must not read the build counter: a bare Gradle invocation has to work " +
                "from gradle/version.properties alone",
            appBuild.contains("build-tools/tracking/build-counter.json"),
        )
        assertFalse(
            "app/build.gradle.kts must not take its version from -PbuildNumber any more",
            appBuild.contains("findProperty(\"buildNumber\")"),
        )
        assertTrue(
            "app/build.gradle.kts must keep the drift assert and name bumpVersion as the fix",
            appBuild.contains("require(declaredVersionCode == declaredPackedVersionCode)") &&
                appBuild.contains("bumpVersion"),
        )
    }

    /**
     * The packing rule, implemented here rather than imported: `major*1_000_000 + minor*1_000 + patch`,
     * with each segment capped at 999 so two distinct versions cannot pack to the same code.
     */
    private fun packed(version: String): Int {
        val segments = version.split('.')
        require(segments.size <= 3) {
            "Version '$version' has ${segments.size} segments; at most three (major.minor.patch) fit a versionCode."
        }
        val numbers =
            segments.map { segment ->
                require(segment.isNotEmpty() && segment.all(Char::isDigit)) {
                    "Version '$version' has a non-numeric segment '$segment'."
                }
                segment.toInt().also { value ->
                    require(value in 0..999) {
                        "Version '$version' has segment $value; each segment must be 0..999 so distinct versions " +
                            "cannot pack to the same versionCode."
                    }
                }
            }
        val padded = numbers + List(3 - numbers.size) { 0 }
        return padded[0] * 1_000_000 + padded[1] * 1_000 + padded[2]
    }

    private fun validate(
        version: String,
        code: Int,
    ) {
        require(code == packed(version)) {
            "run ./gradlew bumpVersion -Pto=<new version>"
        }
    }

    /** The file's non-blank, non-comment lines, trimmed. */
    private fun versionFileLines(): List<String> =
        repoRoot()
            .resolve("gradle/version.properties")
            .toFile()
            .readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    private fun readDeclaredVersion(): Declared {
        val lines = versionFileLines()
        val name = lines.firstOrNull { it.startsWith("version=") }?.substringAfter("=")?.trim()
        val code = lines.firstOrNull { it.startsWith("versionCode=") }?.substringAfter("=")?.trim()
        assertNotNull("gradle/version.properties must declare a 'version=' line", name)
        assertNotNull("gradle/version.properties must declare a 'versionCode=' line", code)
        return Declared(name = requireNotNull(name), code = requireNotNull(code).toInt())
    }

    /**
     * Walks up to the directory holding `settings.gradle.kts`, the same way
     * `LocalizationAndDateFormatRegressionTest` resolves it: Gradle runs a module's unit tests with the
     * module directory as the working directory, so the repository root is some number of levels up.
     */
    private fun repoRoot(): Path {
        var current: Path? = Path.of("").toAbsolutePath()
        while (current != null) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) return current
            current = current.parent
        }
        error("Could not resolve the repository root from the test working directory")
    }

    private data class Declared(
        val name: String,
        val code: Int,
    )
}
