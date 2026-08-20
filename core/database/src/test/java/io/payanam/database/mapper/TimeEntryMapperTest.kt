//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.mapper

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.entity.TimeEntryEntity
import io.payanam.database.mapper.TimeEntryMapper.toDomain
import io.payanam.database.mapper.TimeEntryMapper.toEntity
import io.payanam.domain.model.TimeEntry
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
/**
 * TimeEntryMapperTest.
 */
class TimeEntryMapperTest {
    private lateinit var logger: UnifiedLogger

    @Before
    /**
     * Setup.
     */
    fun setup() {
        logger = initLogger()
        logger.d("TimeEntryMapperTest.setup", "Logger initialized for tests")
    }

    @Test
    /**
     * To domain parses zulu dates.
     */
    fun toDomain_parsesZuluDates() {
        /** Entity. */
        val entity =
            /** Time entry entity. */
            TimeEntryEntity(
                id = "entry-1",
                lifeIntentionCategory = "Career & Work",
                taskId = "task-1",
                startedAt = "2026-01-31T08:00:00Z",
                endedAt = "2026-01-31T09:00:00Z",
                createdAt = "2026-01-31T08:00:00Z",
                updatedAt = "2026-01-31T09:00:00Z",
            )

        /** Domain. */
        val domain = entity.toDomain()
        /** Assert that. */
        assertThat(domain.startedAt.hour).isEqualTo(8)
        /** Assert that. */
        assertThat(domain.endedAt?.hour).isEqualTo(9)
    }

    @Test
    /**
     * To domain handles blank end time.
     */
    fun toDomain_handlesBlankEndTime() {
        /** Entity. */
        val entity =
            /** Time entry entity. */
            TimeEntryEntity(
                id = "entry-blank",
                lifeIntentionCategory = "Career & Work",
                taskId = "task-1",
                startedAt = "2026-01-31T08:00:00Z",
                endedAt = "",
                createdAt = "2026-01-31T08:00:00Z",
                updatedAt = "2026-01-31T09:00:00Z",
            )

        /** Domain. */
        val domain = entity.toDomain()
        /** Assert that. */
        assertThat(domain.endedAt).isNull()
    }

    @Test
    /**
     * Round trip preserves fields.
     */
    fun roundTrip_preservesFields() {
        /** Now. */
        val now = LocalDateTime.of(2026, 1, 31, 8, 0)
        /** Entry. */
        val entry =
            /** Time entry. */
            TimeEntry(
                id = "entry-2",
                lifeIntentionCategory = "Learning",
                taskId = "task-2",
                startedAt = now,
                endedAt = now.plusMinutes(30),
                createdAt = now,
                updatedAt = now,
                dimensionId = "dim_learning",
            )

        /** Entity. */
        val entity = entry.toEntity()
        /** Round trip. */
        val roundTrip = entity.toDomain()

        /** Assert that. */
        assertThat(roundTrip.lifeIntentionCategory).isEqualTo(entry.lifeIntentionCategory)
        /** Assert that. */
        assertThat(roundTrip.dimensionId).isEqualTo("dim_learning")
        /** Assert that. */
        assertThat(roundTrip.taskId).isEqualTo(entry.taskId)
        /** Assert that. */
        assertThat(entity.dimensionId).isEqualTo("dim_learning")
        /** Assert that. */
        assertThat(entity.dayKey).isEqualTo("2026-01-31")
        /** Assert that. */
        assertThat(roundTrip.endedAt?.minute).isEqualTo(30)
    }

    @Test
    /**
     * To entity handles null end time.
     */
    fun toEntity_handlesNullEndTime() {
        /** Now. */
        val now = LocalDateTime.of(2026, 1, 31, 8, 0)
        /** Entry. */
        val entry =
            /** Time entry. */
            TimeEntry(
                id = "entry-3",
                lifeIntentionCategory = "Learning",
                startedAt = now,
                createdAt = now,
                updatedAt = now,
            )

        /** Entity. */
        val entity = entry.toEntity()
        /** Assert that. */
        assertThat(entity.endedAt).isNull()
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
