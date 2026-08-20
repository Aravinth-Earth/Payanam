//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import io.payanam.domain.model.Task
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Regression tests for the shared title-only search matcher used by the
 * Tasks and Habits tab search fields. Locks the "search what the listing
 * shows" contract: title only, case-insensitive, substring (fuzzy) match.
 */
class TasksSearchRegressionTest {

    private val baseTime = LocalDateTime.of(2026, 8, 8, 10, 0)

    private fun task(
        id: String = "t1",
        /** Title. */
        title: String,
        status: String = "pending",
        description: String? = null,
        dimensionId: String? = null,
        lifeIntentionCategory: String = "Career & Work",
    ): Task = Task(
        id = id,
        title = title,
        description = description,
        status = status,
        createdAt = baseTime,
        updatedAt = baseTime,
        dimensionId = dimensionId,
        lifeIntentionCategory = lifeIntentionCategory,
    )

    @Test
    /**
     * Blank query matches everything.
     */
    fun blankQuery_matchesEverything() {
        /** Assert true. */
        assertTrue(task(title = "Morning Run").matchesTaskSearch(""))
        /** Assert true. */
        assertTrue(task(title = "Morning Run").matchesTaskSearch("   "))
    }

    @Test
    /**
     * Title substring is case insensitive.
     */
    fun titleSubstring_isCaseInsensitive() {
        /** Assert true. */
        assertTrue(task(title = "Morning Run").matchesTaskSearch("morning"))
        /** Assert true. */
        assertTrue(task(title = "Morning Run").matchesTaskSearch("MORNING"))
        /** Assert true. */
        assertTrue(task(title = "Morning Run").matchesTaskSearch("Run"))
        /** Assert true. */
        assertTrue(task(title = "Morning Run").matchesTaskSearch("run"))
    }

    @Test
    /**
     * Partial substring matches.
     */
    fun partialSubstring_matches() {
        // Fuzzy: any char sequence in title matches from the first character
        /** Assert true. */
        assertTrue(task(title = "Morning Run").matchesTaskSearch("m"))
        /** Assert true. */
        assertTrue(task(title = "Morning Run").matchesTaskSearch("mo"))
        /** Assert true. */
        assertTrue(task(title = "Morning Run").matchesTaskSearch("ning Ru"))
    }

    @Test
    /**
     * Non matching query returns false.
     */
    fun nonMatchingQuery_returnsFalse() {
        /** Assert false. */
        assertFalse(task(title = "Morning Run").matchesTaskSearch("xyz"))
        /** Assert false. */
        assertFalse(task(title = "Read 20 pages").matchesTaskSearch("novel"))
    }

    @Test
    /**
     * Status field does not pollute results.
     */
    fun statusField_doesNotPolluteResults() {
        // Regression: previously status ("pending" contains i/n) matched everything
        /** Assert false. */
        assertFalse(task(title = "Gym", status = "pending").matchesTaskSearch("pending"))
        /** Assert false. */
        assertFalse(task(title = "Gym", status = "pending").matchesTaskSearch("in"))
        /** Assert false. */
        assertFalse(task(title = "Gym", status = "completed").matchesTaskSearch("completed"))
    }

    @Test
    /**
     * Internal fields do not pollute results.
     */
    fun internalFields_doNotPolluteResults() {
        // Regression: dimensionId / lifeIntentionCategory / description are not visible in listing
        /** Assert false. */
        assertFalse(task(title = "Gym", description = "leg day", dimensionId = "health").matchesTaskSearch("health"))
        /** Assert false. */
        assertFalse(task(title = "Gym", description = "leg day").matchesTaskSearch("leg"))
        /** Assert false. */
        assertFalse(task(title = "Gym", lifeIntentionCategory = "Career & Work").matchesTaskSearch("career"))
    }

    @Test
    /**
     * Title only searchable field.
     */
    fun titleOnly_searchableField() {
        // Only title matters — description match must NOT return true
        /** T. */
        val t = task(title = "Meditate", description = "breathe deeply")
        /** Assert true. */
        assertTrue(t.matchesTaskSearch("meditate"))
        /** Assert false. */
        assertFalse(t.matchesTaskSearch("breathe"))
    }

    @Test
    /**
     * Full title substring matches.
     */
    fun fullTitleSubstring_matches() {
        // Multi-word title still substring-matched; callers trim before calling
        /** Assert true. */
        assertTrue(task(title = "Morning Run").matchesTaskSearch("morning run"))
        /** Assert true. */
        assertTrue(task(title = "Morning Run").matchesTaskSearch("run"))
    }
}
