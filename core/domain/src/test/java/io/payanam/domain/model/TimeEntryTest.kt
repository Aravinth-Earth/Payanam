//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
/**
 * TimeEntryTest.
 */
class TimeEntryTest {

    private lateinit var logger: UnifiedLogger

    @Before
    /**
     * Setup.
     */
    fun setup() {
        logger = initLogger()
        logger.d("TimeEntryTest.setup", "Logger initialized for tests")
    }

    @Test
    /**
     * Is active true when no end time.
     */
    fun isActive_trueWhenNoEndTime() {
        /** Now. */
        val now = LocalDateTime.of(2026, 1, 31, 9, 0)
        /** Entry. */
        val entry = TimeEntry(
            id = "entry-1",
            lifeIntentionCategory = "Career & Work",
            startedAt = now,
            createdAt = now,
            updatedAt = now
        )
        /** Assert that. */
        assertThat(entry.isActive).isTrue()
    }

    @Test
    /**
     * Is active false when ended.
     */
    fun isActive_falseWhenEnded() {
        /** Start. */
        val start = LocalDateTime.of(2026, 1, 31, 9, 0)
        /** End. */
        val end = start.plusMinutes(10)
        /** Entry. */
        val entry = TimeEntry(
            id = "entry-1b",
            lifeIntentionCategory = "Career & Work",
            startedAt = start,
            endedAt = end,
            createdAt = start,
            updatedAt = start
        )
        /** Assert that. */
        assertThat(entry.isActive).isFalse()
    }

    @Test
    /**
     * Duration minutes uses end time when present.
     */
    fun durationMinutes_usesEndTimeWhenPresent() {
        /** Start. */
        val start = LocalDateTime.of(2026, 1, 31, 9, 0)
        /** End. */
        val end = start.plusMinutes(45)
        /** Entry. */
        val entry = TimeEntry(
            id = "entry-2",
            lifeIntentionCategory = "Learning",
            startedAt = start,
            endedAt = end,
            createdAt = start,
            updatedAt = start
        )
        /** Assert that. */
        assertThat(entry.durationMinutes()).isEqualTo(45)
    }

    @Test
    /**
     * Duration minutes uses provided now for active entry.
     */
    fun durationMinutes_usesProvidedNowForActiveEntry() {
        /** Start. */
        val start = LocalDateTime.of(2026, 1, 31, 9, 0)
        /** Now. */
        val now = start.plusMinutes(20)
        /** Entry. */
        val entry = TimeEntry(
            id = "entry-3",
            lifeIntentionCategory = "Learning",
            startedAt = start,
            createdAt = start,
            updatedAt = start
        )
        /** Assert that. */
        assertThat(entry.durationMinutes(now)).isEqualTo(20)
    }

    @Test
    /**
     * Time entry exposes fields.
     */
    fun timeEntry_exposesFields() {
        /** Start. */
        val start = LocalDateTime.of(2026, 1, 31, 10, 0)
        /** End. */
        val end = start.plusMinutes(30)
        /** Entry. */
        val entry = TimeEntry(
            id = "entry-4",
            lifeIntentionCategory = "Focus",
            taskId = "task-9",
            startedAt = start,
            endedAt = end,
            createdAt = start,
            updatedAt = end
        )

        /** Assert that. */
        assertThat(entry.id).isEqualTo("entry-4")
        /** Assert that. */
        assertThat(entry.lifeIntentionCategory).isEqualTo("Focus")
        /** Assert that. */
        assertThat(entry.taskId).isEqualTo("task-9")
        /** Assert that. */
        assertThat(entry.startedAt).isEqualTo(start)
        /** Assert that. */
        assertThat(entry.endedAt).isEqualTo(end)
        /** Assert that. */
        assertThat(entry.createdAt).isEqualTo(start)
        /** Assert that. */
        assertThat(entry.updatedAt).isEqualTo(end)
    }

    @Test
    /**
     * Time entry input holds values.
     */
    fun timeEntryInput_holdsValues() {
        /** Start. */
        val start = LocalDateTime.of(2026, 1, 31, 10, 0)
        /** End. */
        val end = start.plusMinutes(45)
        /** Input. */
        val input = TimeEntryInput(
            lifeIntentionCategory = "Health & Wellness",
            taskId = "task-7",
            startedAt = start,
            endedAt = end
        )
        /** Assert that. */
        assertThat(input.lifeIntentionCategory).isEqualTo("Health & Wellness")
        /** Assert that. */
        assertThat(input.taskId).isEqualTo("task-7")
        /** Assert that. */
        assertThat(input.startedAt).isEqualTo(start)
        /** Assert that. */
        assertThat(input.endedAt).isEqualTo(end)
    }

    private fun initLogger(): UnifiedLogger {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()
        /** If. */
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        return UnifiedLogger.getInstance()
    }
}
