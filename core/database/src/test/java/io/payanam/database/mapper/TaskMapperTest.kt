//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.mapper

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.entity.TaskEntity
import io.payanam.database.mapper.TaskMapper.toDomain
import io.payanam.database.mapper.TaskMapper.toEntity
import io.payanam.domain.model.Task
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
/**
 * TaskMapperTest.
 */
class TaskMapperTest {
    private lateinit var logger: UnifiedLogger

    @Before
    /**
     * Setup.
     */
    fun setup() {
        logger = initLogger()
        logger.d("TaskMapperTest.setup", "Logger initialized for tests")
    }

    @Test
    /**
     * To domain parses zulu date.
     */
    fun toDomain_parsesZuluDate() {
        val entity =
            TaskEntity(
                id = "task-1",
                title = "Test",
                createdAt = "2026-01-31T09:00:00Z",
                updatedAt = "2026-01-31T09:15:00Z",
                dueDate = "2026-01-31T10:00:00Z",
            )
        val domain = entity.toDomain()
        assertThat(domain.dueDate?.hour).isEqualTo(10)
        assertThat(domain.createdAt.year).isEqualTo(2026)
    }

    @Test
    /**
     * To domain parses date only last occurrence date at start of day.
     */
    fun toDomain_parsesDateOnlyLastOccurrenceDateAtStartOfDay() {
        val entity =
            TaskEntity(
                id = "task-date-only",
                title = "Test",
                createdAt = "2026-01-31T09:00:00",
                updatedAt = "2026-01-31T09:15:00",
                lastOccurrenceDate = "2026-01-30",
            )
        val domain = entity.toDomain()
        assertThat(domain.lastOccurrenceDate).isEqualTo(LocalDateTime.of(2026, 1, 30, 0, 0))
    }

    @Test
    /**
     * Round trip preserves fields.
     */
    fun roundTrip_preservesFields() {
        val now = LocalDateTime.of(2026, 1, 31, 9, 0)
        val task =
            Task(
                id = "task-2",
                title = "RoundTrip",
                createdAt = now,
                updatedAt = now,
                dueDate = now.plusHours(2),
                impactLevel = "High Impact",
                goalAlignment = "Strong Alignment",
                energyLevel = "High",
                controlLevel = "Mostly Controllable",
                lifeIntentionCategory = "Health & Wellness",
                dimensionId = "dim_health_wellness",
                durationMinutes = 30,
                notificationMode = "custom",
                customNotificationMinutes = 20,
            )
        val entity = task.toEntity()
        val roundTrip = entity.toDomain()
        assertThat(roundTrip.title).isEqualTo(task.title)
        assertThat(roundTrip.lifeIntentionCategory).isEqualTo(task.lifeIntentionCategory)
        assertThat(roundTrip.dimensionId).isEqualTo("dim_health_wellness")
        assertThat(entity.dimensionId).isEqualTo("dim_health_wellness")
        assertThat(entity.dayKey).isEqualTo("2026-01-31")
        assertThat(roundTrip.notificationMode).isEqualTo("custom")
        assertThat(roundTrip.customNotificationMinutes).isEqualTo(20)
    }

    @Test
    /**
     * To entity handles null due date.
     */
    fun toEntity_handlesNullDueDate() {
        val now = LocalDateTime.of(2026, 1, 31, 9, 0)
        val task =
            Task(
                id = "task-3",
                title = "No Due",
                createdAt = now,
                updatedAt = now,
                dueDate = null,
            )
        val entity = task.toEntity()
        assertThat(entity.dueDate).isNull()
        assertThat(entity.dayKey).isNull()
    }

    @Test
    /**
     * To entity sets recurrence enabled flag.
     */
    fun toEntity_setsRecurrenceEnabledFlag() {
        val now = LocalDateTime.of(2026, 1, 31, 9, 0)
        val task =
            Task(
                id = "task-4",
                title = "Recurring",
                createdAt = now,
                updatedAt = now,
                recurrenceEnabled = true,
                recurrenceRule = "FREQ=DAILY",
            )
        val entity = task.toEntity()
        assertThat(entity.recurrenceEnabled).isEqualTo(1)
        assertThat(entity.recurrenceRule).isEqualTo("FREQ=DAILY")
    }

    private fun initLogger(): UnifiedLogger {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        return UnifiedLogger.getInstance()
    }
}
