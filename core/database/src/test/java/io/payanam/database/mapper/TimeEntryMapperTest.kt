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
        val entity =
            TimeEntryEntity(
                id = "entry-1",
                lifeIntentionCategory = "Career & Work",
                taskId = "task-1",
                startedAt = "2026-01-31T08:00:00Z",
                endedAt = "2026-01-31T09:00:00Z",
                createdAt = "2026-01-31T08:00:00Z",
                updatedAt = "2026-01-31T09:00:00Z",
            )
        val domain = entity.toDomain()
        assertThat(domain.startedAt.hour).isEqualTo(8)
        assertThat(domain.endedAt?.hour).isEqualTo(9)
    }

    @Test
    /**
     * To domain handles blank end time.
     */
    fun toDomain_handlesBlankEndTime() {
        val entity =
            TimeEntryEntity(
                id = "entry-blank",
                lifeIntentionCategory = "Career & Work",
                taskId = "task-1",
                startedAt = "2026-01-31T08:00:00Z",
                endedAt = "",
                createdAt = "2026-01-31T08:00:00Z",
                updatedAt = "2026-01-31T09:00:00Z",
            )
        val domain = entity.toDomain()
        assertThat(domain.endedAt).isNull()
    }

    @Test
    /**
     * Round trip preserves fields.
     */
    fun roundTrip_preservesFields() {
        val now = LocalDateTime.of(2026, 1, 31, 8, 0)
        val entry =
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
        val entity = entry.toEntity()
        val roundTrip = entity.toDomain()
        assertThat(roundTrip.lifeIntentionCategory).isEqualTo(entry.lifeIntentionCategory)
        assertThat(roundTrip.dimensionId).isEqualTo("dim_learning")
        assertThat(roundTrip.taskId).isEqualTo(entry.taskId)
        assertThat(entity.dimensionId).isEqualTo("dim_learning")
        assertThat(entity.dayKey).isEqualTo("2026-01-31")
        assertThat(roundTrip.endedAt?.minute).isEqualTo(30)
    }

    @Test
    /**
     * To entity handles null end time.
     */
    fun toEntity_handlesNullEndTime() {
        val now = LocalDateTime.of(2026, 1, 31, 8, 0)
        val entry =
            TimeEntry(
                id = "entry-3",
                lifeIntentionCategory = "Learning",
                startedAt = now,
                createdAt = now,
                updatedAt = now,
            )
        val entity = entry.toEntity()
        assertThat(entity.endedAt).isNull()
    }

    private fun initLogger(): UnifiedLogger {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        return UnifiedLogger.getInstance()
    }
}
