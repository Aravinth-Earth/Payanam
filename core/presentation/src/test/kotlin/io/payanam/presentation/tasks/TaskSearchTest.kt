//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.tasks

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Regression tests for the shared title-only search matcher used by the
 * Tasks and Habits tab search fields. Locks the "search what the listing
 * shows" contract: title only, case-insensitive, substring (fuzzy) match.
 *
 * Ported from `app/.../TasksSearchRegressionTest.kt` (plain JVM, no Android deps).
 */
class TaskSearchTest {
    @Test
    fun blankQuery_matchesEverything() {
        assertThat(task(title = "Morning Run").matchesTaskSearch("")).isTrue()
        assertThat(task(title = "Morning Run").matchesTaskSearch("   ")).isTrue()
    }

    @Test
    fun titleSubstring_isCaseInsensitive() {
        assertThat(task(title = "Morning Run").matchesTaskSearch("morning")).isTrue()
        assertThat(task(title = "Morning Run").matchesTaskSearch("MORNING")).isTrue()
        assertThat(task(title = "Morning Run").matchesTaskSearch("Run")).isTrue()
        assertThat(task(title = "Morning Run").matchesTaskSearch("run")).isTrue()
    }

    @Test
    fun partialSubstring_matches() {
        // Fuzzy: any char sequence in title matches from the first character
        assertThat(task(title = "Morning Run").matchesTaskSearch("m")).isTrue()
        assertThat(task(title = "Morning Run").matchesTaskSearch("mo")).isTrue()
        assertThat(task(title = "Morning Run").matchesTaskSearch("ning Ru")).isTrue()
    }

    @Test
    fun nonMatchingQuery_returnsFalse() {
        assertThat(task(title = "Morning Run").matchesTaskSearch("xyz")).isFalse()
        assertThat(task(title = "Read 20 pages").matchesTaskSearch("novel")).isFalse()
    }

    @Test
    fun statusField_doesNotPolluteResults() {
        // Regression: previously status ("pending" contains i/n) matched everything
        assertThat(task(title = "Gym", status = "pending").matchesTaskSearch("pending")).isFalse()
        assertThat(task(title = "Gym", status = "pending").matchesTaskSearch("in")).isFalse()
        assertThat(task(title = "Gym", status = "completed").matchesTaskSearch("completed")).isFalse()
    }

    @Test
    fun internalFields_doNotPolluteResults() {
        // Regression: dimensionId / lifeIntentionCategory / description are not visible in listing
        assertThat(task(title = "Gym", description = "leg day", dimensionId = "health").matchesTaskSearch("health")).isFalse()
        assertThat(task(title = "Gym", description = "leg day").matchesTaskSearch("leg")).isFalse()
        assertThat(task(title = "Gym", lifeIntentionCategory = "Career & Work").matchesTaskSearch("career")).isFalse()
    }

    @Test
    fun titleOnly_searchableField() {
        // Only title matters — description match must NOT return true
        val t = task(title = "Meditate", description = "breathe deeply")
        assertThat(t.matchesTaskSearch("meditate")).isTrue()
        assertThat(t.matchesTaskSearch("breathe")).isFalse()
    }

    @Test
    fun fullTitleSubstring_matches() {
        // Multi-word title still substring-matched; callers trim before calling
        assertThat(task(title = "Morning Run").matchesTaskSearch("morning run")).isTrue()
        assertThat(task(title = "Morning Run").matchesTaskSearch("run")).isTrue()
    }
}
