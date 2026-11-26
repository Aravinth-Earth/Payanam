//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.credits

/**
 * The third-party software this project ships and builds with, and where each one lives.
 *
 * WHY THIS IS DATA AND NOT A SCREEN:
 *
 * The same list has to serve three readers - the About card on Android, the About card on desktop, and
 * the Credits section of README.md - and a list retyped in three places disagrees with itself inside a
 * release. One list, one shape, and the screens render it.
 *
 * WHY VERSIONS ARE HERE AT ALL: `gradle/libs.versions.toml` is the truth for what Gradle resolves, but a
 * person reading About is not holding Gradle. The versions below mirror that catalogue for the release
 * they were written for; the README carries the same numbers, and `ThirdPartyCreditsTest` fails when a
 * dependency the build really uses is missing from this list. When the catalogue moves, both move.
 *
 * A row settles a credit, not a dependency graph: a library that arrives only as a transitive dependency
 * of another named row (Compose BOM members, for example) is credited with the row that brings it.
 */
enum class CreditScope(
    val heading: String,
) {
    ANDROID_RUNTIME("Android runtime"),
    DESKTOP_RUNTIME("Desktop runtime"),
    BUILD("Build and packaging"),
    TEST_AND_TOOLING("Tests and quality tooling"),
}

/** One library: what it is, where its own page is, and the licence it is used under. */
data class CreditEntry(
    val name: String,
    val version: String,
    val purpose: String,
    val license: String,
    val url: String,
    val scope: CreditScope,
)

object ThirdPartyCredits {
    private const val APACHE = "Apache-2.0"

    val all: List<CreditEntry> =
        listOf(
            // ------------------------------------------------------------------ Android runtime
            CreditEntry(
                name = "AndroidX Core KTX",
                version = "1.18.0",
                purpose = "Kotlin extensions for the Android platform libraries this app runs on.",
                license = APACHE,
                url = "https://developer.android.com/kotlin/ktx",
                scope = CreditScope.ANDROID_RUNTIME,
            ),
            CreditEntry(
                name = "AndroidX Activity Compose",
                version = "1.13.0",
                purpose = "Hosts Compose content in the app's single activity and hands back its result APIs.",
                license = APACHE,
                url = "https://developer.android.com/jetpack/androidx/releases/activity",
                scope = CreditScope.ANDROID_RUNTIME,
            ),
            CreditEntry(
                name = "AndroidX Lifecycle",
                version = "2.10.0",
                purpose = "Lifecycle-aware state, view models and Compose collectors for every screen.",
                license = APACHE,
                url = "https://developer.android.com/jetpack/androidx/releases/lifecycle",
                scope = CreditScope.ANDROID_RUNTIME,
            ),
            CreditEntry(
                name = "AndroidX Navigation Compose",
                version = "2.9.7",
                purpose = "The app's navigation graph and typed destinations.",
                license = APACHE,
                url = "https://developer.android.com/guide/navigation",
                scope = CreditScope.ANDROID_RUNTIME,
            ),
            CreditEntry(
                name = "Jetpack Compose",
                version = "BOM 2026.02.01",
                purpose = "The declarative UI toolkit every screen in the app is drawn with.",
                license = APACHE,
                url = "https://developer.android.com/jetpack/compose",
                scope = CreditScope.ANDROID_RUNTIME,
            ),
            CreditEntry(
                name = "Compose Material 3",
                version = "1.3.1",
                purpose = "Material 3 components, colour roles and typography for the Compose UI.",
                license = APACHE,
                url = "https://developer.android.com/jetpack/androidx/releases/compose-material3",
                scope = CreditScope.ANDROID_RUNTIME,
            ),
            CreditEntry(
                name = "Hilt",
                version = "2.59.2",
                purpose = "Dependency injection for the app's view models, services and workers.",
                license = APACHE,
                url = "https://dagger.dev/hilt/",
                scope = CreditScope.ANDROID_RUNTIME,
            ),
            CreditEntry(
                name = "Hilt Navigation Compose",
                version = "1.3.0",
                purpose = "Scopes a Hilt view model to the navigation destination that shows it.",
                license = APACHE,
                url = "https://developer.android.com/jetpack/androidx/releases/hilt",
                scope = CreditScope.ANDROID_RUNTIME,
            ),
            CreditEntry(
                name = "Room",
                version = "2.7.2",
                purpose = "The local SQLite database layer behind every task, habit and time entry.",
                license = APACHE,
                url = "https://developer.android.com/training/data-storage/room",
                scope = CreditScope.ANDROID_RUNTIME,
            ),
            CreditEntry(
                name = "AndroidX SQLite",
                version = "2.7.0",
                purpose = "The SQLite driver interfaces Room and the encrypted database open against.",
                license = APACHE,
                url = "https://developer.android.com/jetpack/androidx/releases/sqlite",
                scope = CreditScope.ANDROID_RUNTIME,
            ),
            CreditEntry(
                name = "SQLCipher for Android",
                version = "4.17.0",
                purpose = "Encrypts the on-device database with the user's passphrase.",
                license = "Zetetic SQLCipher licence (BSD-style)",
                url = "https://www.zetetic.net/sqlcipher/",
                scope = CreditScope.ANDROID_RUNTIME,
            ),
            CreditEntry(
                name = "Kotlin Coroutines",
                version = "1.10.2",
                purpose = "Structured concurrency for database reads, background work and tracking.",
                license = APACHE,
                url = "https://github.com/Kotlin/kotlinx.coroutines",
                scope = CreditScope.ANDROID_RUNTIME,
            ),
            CreditEntry(
                name = "Kotlin Serialization",
                version = "1.9.0",
                purpose = "JSON encoding for backup metadata, update checks and export manifests.",
                license = APACHE,
                url = "https://github.com/Kotlin/kotlinx.serialization",
                scope = CreditScope.ANDROID_RUNTIME,
            ),
            CreditEntry(
                name = "AndroidX DataStore",
                version = "1.2.1",
                purpose = "Persists the app's preferences without a database round trip.",
                license = APACHE,
                url = "https://developer.android.com/topic/libraries/architecture/datastore",
                scope = CreditScope.ANDROID_RUNTIME,
            ),
            CreditEntry(
                name = "AndroidX WorkManager",
                version = "2.11.1",
                purpose = "Runs background work - reminders, backups - that must survive a restart.",
                license = APACHE,
                url = "https://developer.android.com/topic/libraries/architecture/workmanager",
                scope = CreditScope.ANDROID_RUNTIME,
            ),
            CreditEntry(
                name = "AndroidX Biometric",
                version = "1.1.0",
                purpose = "Fingerprint and face unlock for the app lock, with the device credential fallback.",
                license = APACHE,
                url = "https://developer.android.com/jetpack/androidx/releases/biometric",
                scope = CreditScope.ANDROID_RUNTIME,
            ),
            CreditEntry(
                name = "Vico",
                version = "3.1.0",
                purpose = "The charts the time and lens screens draw their history with.",
                license = APACHE,
                url = "https://github.com/patrykandpatrick/vico",
                scope = CreditScope.ANDROID_RUNTIME,
            ),
            CreditEntry(
                name = "Timber",
                version = "5.0.1",
                purpose = "The logging facade whose logs the in-app debug viewer and log export read.",
                license = APACHE,
                url = "https://github.com/JakeWharton/timber",
                scope = CreditScope.ANDROID_RUNTIME,
            ),

            // ------------------------------------------------------------------ desktop runtime
            CreditEntry(
                name = "Compose Multiplatform",
                version = "1.10.2",
                purpose = "Draws the desktop window with the same Compose UI the Android app uses.",
                license = APACHE,
                url = "https://github.com/JetBrains/compose-multiplatform",
                scope = CreditScope.DESKTOP_RUNTIME,
            ),
            CreditEntry(
                name = "AndroidX Navigation Compose (multiplatform)",
                version = "2.9.2",
                purpose = "The navigation graph shared by the Android and desktop builds.",
                license = APACHE,
                url = "https://github.com/JetBrains/compose-multiplatform-core",
                scope = CreditScope.DESKTOP_RUNTIME,
            ),
            CreditEntry(
                name = "Kotlin Coroutines (Swing)",
                version = "1.10.2",
                purpose = "Dispatches coroutines onto the desktop app's Swing event loop.",
                license = APACHE,
                url = "https://github.com/Kotlin/kotlinx.coroutines",
                scope = CreditScope.DESKTOP_RUNTIME,
            ),
            CreditEntry(
                name = "SQLite JDBC",
                version = "3.50.3.0",
                purpose = "Reads and writes the desktop app's SQLite database.",
                license = APACHE,
                url = "https://github.com/xerial/sqlite-jdbc",
                scope = CreditScope.DESKTOP_RUNTIME,
            ),
            CreditEntry(
                name = "SQLCipher Multiplatform JDBC",
                version = "0.2.13",
                purpose = "Opens the Android-exported encrypted database on the desktop, per platform.",
                license = "MIT",
                url = "https://github.com/s0d3s/sqlcipher-multiplatform-jdbc",
                scope = CreditScope.DESKTOP_RUNTIME,
            ),

            // ------------------------------------------------------------------ build and packaging
            CreditEntry(
                name = "Android Gradle Plugin",
                version = "9.3.0",
                purpose = "Builds, packages and signs the Android app.",
                license = APACHE,
                url = "https://developer.android.com/build",
                scope = CreditScope.BUILD,
            ),
            CreditEntry(
                name = "Kotlin",
                version = "2.3.20",
                purpose = "The language, compiler and Gradle plugin every module is written in.",
                license = APACHE,
                url = "https://kotlinlang.org",
                scope = CreditScope.BUILD,
            ),
            CreditEntry(
                name = "Kotlin Symbol Processing (KSP)",
                version = "2.3.10",
                purpose = "Generates the Hilt and Room code at build time.",
                license = APACHE,
                url = "https://github.com/google/ksp",
                scope = CreditScope.BUILD,
            ),
            CreditEntry(
                name = "Hilt compiler",
                version = "2.59.2",
                purpose = "Generates the dependency-injection graph the runtime Hilt row resolves.",
                license = APACHE,
                url = "https://dagger.dev/hilt/",
                scope = CreditScope.BUILD,
            ),
            CreditEntry(
                name = "Room compiler",
                version = "2.7.2",
                purpose = "Generates the DAO and database implementations from the annotated schema.",
                license = APACHE,
                url = "https://developer.android.com/training/data-storage/room",
                scope = CreditScope.BUILD,
            ),

            // ------------------------------------------------------------------ tests and tooling
            CreditEntry(
                name = "JUnit 4",
                version = "4.13.2",
                purpose = "The unit-test runner for the JVM and Android test suites.",
                license = "EPL-1.0",
                url = "https://junit.org/junit4/",
                scope = CreditScope.TEST_AND_TOOLING,
            ),
            CreditEntry(
                name = "AndroidX Test",
                version = "core 1.7.0, ext-junit 1.3.0",
                purpose = "Instrumentation plumbing and the JUnit rule that runs Android tests.",
                license = APACHE,
                url = "https://developer.android.com/training/testing",
                scope = CreditScope.TEST_AND_TOOLING,
            ),
            CreditEntry(
                name = "Espresso",
                version = "3.7.0",
                purpose = "Drives the Android UI in the instrumentation suite.",
                license = APACHE,
                url = "https://developer.android.com/training/testing/espresso",
                scope = CreditScope.TEST_AND_TOOLING,
            ),
            CreditEntry(
                name = "Robolectric",
                version = "4.16.1",
                purpose = "Runs Android framework tests on the JVM without a device.",
                license = APACHE,
                url = "https://robolectric.org",
                scope = CreditScope.TEST_AND_TOOLING,
            ),
            CreditEntry(
                name = "Mockito",
                version = "5.23.0 (mockito-kotlin 5.4.0)",
                purpose = "Stands in for collaborators in unit tests.",
                license = "MIT",
                url = "https://site.mockito.org",
                scope = CreditScope.TEST_AND_TOOLING,
            ),
            CreditEntry(
                name = "Truth",
                version = "1.4.5",
                purpose = "The fluent assertions the JVM test suites read best with.",
                license = APACHE,
                url = "https://truth.dev",
                scope = CreditScope.TEST_AND_TOOLING,
            ),
            CreditEntry(
                name = "Kotlin Coroutines Test",
                version = "1.10.2",
                purpose = "Virtual time and a test dispatcher for the coroutine-heavy suites.",
                license = APACHE,
                url = "https://github.com/Kotlin/kotlinx.coroutines/tree/master/kotlinx-coroutines-test",
                scope = CreditScope.TEST_AND_TOOLING,
            ),
            CreditEntry(
                name = "Room testing",
                version = "2.7.2",
                purpose = "Builds in-memory databases for the DAO tests.",
                license = APACHE,
                url = "https://developer.android.com/training/data-storage/room/testing-db",
                scope = CreditScope.TEST_AND_TOOLING,
            ),
            CreditEntry(
                name = "Compose UI Test",
                version = "BOM 2026.02.01",
                purpose = "Renders and drives the shared Compose screens in tests.",
                license = APACHE,
                url = "https://developer.android.com/develop/ui/compose/testing",
                scope = CreditScope.TEST_AND_TOOLING,
            ),
            CreditEntry(
                name = "org.json",
                version = "20240303",
                purpose = "Parses the GitHub release feed in the update checker's tests.",
                license = "Public domain",
                url = "https://github.com/stleary/JSON-java",
                scope = CreditScope.TEST_AND_TOOLING,
            ),
            CreditEntry(
                name = "detekt",
                version = "1.23.8",
                purpose = "Static analysis; the rules the CI build enforces.",
                license = APACHE,
                url = "https://detekt.dev",
                scope = CreditScope.TEST_AND_TOOLING,
            ),
            CreditEntry(
                name = "Spotless",
                version = "8.4.0",
                purpose = "The formatting check every Kotlin file passes before commit.",
                license = APACHE,
                url = "https://github.com/diffplug/spotless",
                scope = CreditScope.TEST_AND_TOOLING,
            ),
            CreditEntry(
                name = "ktlint",
                version = "1.8.0",
                purpose = "The Kotlin style rules Spotless applies.",
                license = APACHE,
                url = "https://ktlint.github.io",
                scope = CreditScope.TEST_AND_TOOLING,
            ),
        )

    /** The list in the order About shows it: one block per scope, in the enum's own order. */
    fun byScope(): List<Pair<CreditScope, List<CreditEntry>>> =
        CreditScope.entries.mapNotNull { scope ->
            val rows = all.filter { it.scope == scope }
            if (rows.isEmpty()) null else scope to rows
        }
}
