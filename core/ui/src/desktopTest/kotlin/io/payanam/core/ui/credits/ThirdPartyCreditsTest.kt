//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.credits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The credits list is data three readers depend on, so the properties that make it usable as a credit are
 * pinned here rather than discovered in a review: a name a screen can show, a version a person can match
 * against the catalogue, a purpose in one line, a licence and a page that actually exists under https.
 *
 * The coverage test is the one that matters for drift: it names the dependencies the build genuinely uses
 * today, so a row deleted (or a library added and never credited) fails here instead of shipping an About
 * card that credits somebody else's work by omission.
 */
class ThirdPartyCreditsTest {
    @Test
    fun everyCreditIsRenderable() {
        ThirdPartyCredits.all.forEach { entry ->
            assertTrue("${entry.name}: name must not be blank", entry.name.isNotBlank())
            assertTrue("${entry.name}: version must not be blank", entry.version.isNotBlank())
            assertTrue("${entry.name}: purpose must not be blank", entry.purpose.isNotBlank())
            assertTrue("${entry.name}: licence must not be blank", entry.license.isNotBlank())
            assertTrue(
                "${entry.name}: url must be an https page, was '${entry.url}'",
                entry.url.startsWith("https://") && entry.url.length > "https://".length,
            )
            assertTrue(
                "${entry.name}: a purpose is one line, was ${entry.purpose.lines().size}",
                entry.purpose.lines().size == 1,
            )
        }
    }

    @Test
    fun namesAreUnique() {
        val duplicates = ThirdPartyCredits.all
            .groupingBy { it.name }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        assertEquals("a library credited twice reads as two different libraries", emptySet<String>(), duplicates)
    }

    @Test
    fun everyScopeHasRowsAndTheBlocksFollowTheEnum() {
        val blocks = ThirdPartyCredits.byScope()
        assertEquals(CreditScope.entries.size, blocks.size)
        assertEquals(CreditScope.entries.toList(), blocks.map { it.first })
        blocks.forEach { (scope, rows) ->
            assertTrue("${scope.heading} must not be an empty block", rows.isNotEmpty())
            assertTrue("${scope.heading} rows must declare that scope", rows.all { it.scope == scope })
        }
    }

    @Test
    fun everyDependencyTheBuildUsesIsCredited() {
        // Named individually rather than by count: a count passes when one row is swapped for another.
        val required = listOf(
            // Android runtime
            "AndroidX Core KTX",
            "AndroidX Activity Compose",
            "AndroidX Lifecycle",
            "AndroidX Navigation Compose",
            "Jetpack Compose",
            "Compose Material 3",
            "Hilt",
            "Hilt Navigation Compose",
            "Room",
            "AndroidX SQLite",
            "SQLCipher for Android",
            "Kotlin Coroutines",
            "Kotlin Serialization",
            "AndroidX DataStore",
            "AndroidX WorkManager",
            "AndroidX Biometric",
            "Vico",
            "Timber",
            // desktop runtime
            "Compose Multiplatform",
            "AndroidX Navigation Compose (multiplatform)",
            "Kotlin Coroutines (Swing)",
            "SQLite JDBC",
            "SQLCipher Multiplatform JDBC",
            // build and packaging
            "Android Gradle Plugin",
            "Kotlin",
            "Kotlin Symbol Processing (KSP)",
            "Hilt compiler",
            "Room compiler",
            // tests and tooling
            "JUnit 4",
            "AndroidX Test",
            "Espresso",
            "Robolectric",
            "Mockito",
            "Truth",
            "Kotlin Coroutines Test",
            "Room testing",
            "Compose UI Test",
            "org.json",
            "detekt",
            "Spotless",
            "ktlint",
        )
        val credited = ThirdPartyCredits.all.map { it.name }.toSet()
        val missing = required.filterNot { it in credited }
        assertEquals("shipped dependencies with no credit", emptyList<String>(), missing)
    }
}
