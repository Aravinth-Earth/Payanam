//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("LargeClass")

package io.payanam.database.entity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
/**
 * EntityDefaultsTest.
 */
class EntityDefaultsTest {
    private lateinit var logger: UnifiedLogger

    @Before
    /**
     * Setup.
     */
    fun setup() {
        logger = initLogger()
        logger.d("EntityDefaultsTest.setup", "Logger initialized for tests")
    }

    @Test
    /**
     * Task occurrence defaults are applied.
     */
    fun taskOccurrence_defaults_areApplied() {
        /** Occurrence. */
        val occurrence =
            /** Task occurrence entity. */
            TaskOccurrenceEntity(
                id = "occ-1",
                taskId = "task-1",
                dueDate = "2026-01-31T09:00:00",
                status = "completed",
                createdAt = "2026-01-31T09:00:00",
            )
        /** Assert that. */
        assertThat(occurrence.id).isEqualTo("occ-1")
        /** Assert that. */
        assertThat(occurrence.taskId).isEqualTo("task-1")
        /** Assert that. */
        assertThat(occurrence.dueDate).isEqualTo("2026-01-31T09:00:00")
        /** Assert that. */
        assertThat(occurrence.status).isEqualTo("completed")
        /** Assert that. */
        assertThat(occurrence.createdAt).isEqualTo("2026-01-31T09:00:00")
        /** Assert that. */
        assertThat(occurrence.completionRate).isNull()
        /** Assert that. */
        assertThat(occurrence.note).isNull()
    }

    @Test
    /**
     * Task reschedule stores values.
     */
    fun taskReschedule_storesValues() {
        /** Reschedule. */
        val reschedule =
            /** Task reschedule entity. */
            TaskRescheduleEntity(
                id = "res-1",
                taskId = "task-1",
                previousDueDate = "2026-01-31T09:00:00",
                newDueDate = "2026-02-01T09:00:00",
                rescheduledAt = "2026-01-30T09:00:00",
                wasOverdue = 0,
            )
        /** Assert that. */
        assertThat(reschedule.id).isEqualTo("res-1")
        /** Assert that. */
        assertThat(reschedule.taskId).isEqualTo("task-1")
        /** Assert that. */
        assertThat(reschedule.previousDueDate).isEqualTo("2026-01-31T09:00:00")
        /** Assert that. */
        assertThat(reschedule.newDueDate).isEqualTo("2026-02-01T09:00:00")
        /** Assert that. */
        assertThat(reschedule.rescheduledAt).isEqualTo("2026-01-30T09:00:00")
        /** Assert that. */
        assertThat(reschedule.wasOverdue).isEqualTo(0)
    }

    @Test
    /**
     * Journal entities hold values.
     */
    fun journal_entities_holdValues() {
        /** Entry. */
        val entry =
            /** Day journal entry entity. */
            DayJournalEntryEntity(
                id = "entry-1",
                entryDate = "2026-01-31",
                createdAt = "2026-01-31T09:00:00",
                updatedAt = "2026-01-31T09:00:00",
            )
        /** Response. */
        val response =
            /** Day journal response entity. */
            DayJournalResponseEntity(
                id = "resp-1",
                entryId = entry.id,
                scope = "OVERALL",
                dimensionKey = "health",
                dimensionId = "dim_health_wellness",
                promptKey = "gratitude",
                responseText = "Thanks",
                createdAt = "2026-01-31T09:00:00",
                updatedAt = "2026-01-31T09:00:00",
            )
        /** Assert that. */
        assertThat(entry.id).isEqualTo("entry-1")
        /** Assert that. */
        assertThat(entry.entryDate).isEqualTo("2026-01-31")
        /** Assert that. */
        assertThat(entry.createdAt).isEqualTo("2026-01-31T09:00:00")
        /** Assert that. */
        assertThat(entry.updatedAt).isEqualTo("2026-01-31T09:00:00")
        /** Assert that. */
        assertThat(response.entryId).isEqualTo(entry.id)
        /** Assert that. */
        assertThat(response.scope).isEqualTo("OVERALL")
        /** Assert that. */
        assertThat(response.dimensionKey).isEqualTo("health")
        /** Assert that. */
        assertThat(response.dimensionId).isEqualTo("dim_health_wellness")
        /** Assert that. */
        assertThat(response.promptKey).isEqualTo("gratitude")
        /** Assert that. */
        assertThat(response.responseText).isEqualTo("Thanks")
        /** Assert that. */
        assertThat(response.createdAt).isEqualTo("2026-01-31T09:00:00")
        /** Assert that. */
        assertThat(response.updatedAt).isEqualTo("2026-01-31T09:00:00")
    }

    @Test
    /**
     * App setting entity stores value.
     */
    fun appSetting_entity_storesValue() {
        /** Setting. */
        val setting =
            /** App setting entity. */
            AppSettingEntity(
                key = "notificationsEnabled",
                value = "true",
                updatedAt = "2026-01-31T09:00:00",
            )
        /** Assert that. */
        assertThat(setting.key).isEqualTo("notificationsEnabled")
        /** Assert that. */
        assertThat(setting.value).isEqualTo("true")
        /** Assert that. */
        assertThat(setting.updatedAt).isEqualTo("2026-01-31T09:00:00")
    }

    @Test
    /**
     * Scheduled notification defaults are applied.
     */
    fun scheduledNotification_defaults_areApplied() {
        /** Notification. */
        val notification =
            /** Scheduled notification entity. */
            ScheduledNotificationEntity(
                id = "notif-1",
                taskId = "task-1",
                scheduledAt = "2026-01-31T09:00:00",
                notificationType = "task_reminder",
                title = "Title",
                body = "Body",
                createdAt = "2026-01-31T09:00:00",
            )
        /** Assert that. */
        assertThat(notification.id).isEqualTo("notif-1")
        /** Assert that. */
        assertThat(notification.taskId).isEqualTo("task-1")
        /** Assert that. */
        assertThat(notification.scheduledAt).isEqualTo("2026-01-31T09:00:00")
        /** Assert that. */
        assertThat(notification.notificationType).isEqualTo("task_reminder")
        /** Assert that. */
        assertThat(notification.title).isEqualTo("Title")
        /** Assert that. */
        assertThat(notification.body).isEqualTo("Body")
        /** Assert that. */
        assertThat(notification.createdAt).isEqualTo("2026-01-31T09:00:00")
        /** Assert that. */
        assertThat(notification.isDelivered).isEqualTo(0)
    }

    @Test
    /**
     * Time entry entity exposes fields.
     */
    fun timeEntry_entity_exposesFields() {
        /** Entry. */
        val entry =
            /** Time entry entity. */
            TimeEntryEntity(
                id = "entry-1",
                lifeIntentionCategory = "Learning",
                dimensionId = "dim_learning",
                dayKey = "2026-01-31",
                taskId = "task-1",
                startedAt = "2026-01-31T08:00:00",
                endedAt = "2026-01-31T08:30:00",
                focusRating = 0.85,
                focusNote = "Deep work block",
                focusRatedAt = "2026-01-31T08:31:00",
                importSource = "custom",
                importId = "record_124",
                importedAt = "2026-01-31T08:40:00",
                importBatchId = "batch_1",
                createdAt = "2026-01-31T08:00:00",
                updatedAt = "2026-01-31T08:30:00",
            )
        /** Assert that. */
        assertThat(entry.id).isEqualTo("entry-1")
        /** Assert that. */
        assertThat(entry.lifeIntentionCategory).isEqualTo("Learning")
        /** Assert that. */
        assertThat(entry.dimensionId).isEqualTo("dim_learning")
        /** Assert that. */
        assertThat(entry.dayKey).isEqualTo("2026-01-31")
        /** Assert that. */
        assertThat(entry.taskId).isEqualTo("task-1")
        /** Assert that. */
        assertThat(entry.startedAt).isEqualTo("2026-01-31T08:00:00")
        /** Assert that. */
        assertThat(entry.endedAt).isEqualTo("2026-01-31T08:30:00")
        /** Assert that. */
        assertThat(entry.focusRating).isEqualTo(0.85)
        /** Assert that. */
        assertThat(entry.focusNote).isEqualTo("Deep work block")
        /** Assert that. */
        assertThat(entry.focusRatedAt).isEqualTo("2026-01-31T08:31:00")
        /** Assert that. */
        assertThat(entry.importSource).isEqualTo("custom")
        /** Assert that. */
        assertThat(entry.importId).isEqualTo("record_124")
        /** Assert that. */
        assertThat(entry.importedAt).isEqualTo("2026-01-31T08:40:00")
        /** Assert that. */
        assertThat(entry.importBatchId).isEqualTo("batch_1")
        /** Assert that. */
        assertThat(entry.createdAt).isEqualTo("2026-01-31T08:00:00")
        /** Assert that. */
        assertThat(entry.updatedAt).isEqualTo("2026-01-31T08:30:00")
    }

    @Test
    /**
     * Time entry entity default optional fields are null.
     */
    fun timeEntry_entity_default_optional_fields_are_null() {
        /** Entry. */
        val entry =
            /** Time entry entity. */
            TimeEntryEntity(
                id = "entry-2",
                lifeIntentionCategory = "Learning",
                startedAt = "2026-01-31T09:00:00",
                endedAt = null,
                createdAt = "2026-01-31T09:00:00",
                updatedAt = "2026-01-31T09:00:00",
            )
        /** Assert that. */
        assertThat(entry.dimensionId).isNull()
        /** Assert that. */
        assertThat(entry.dayKey).isNull()
        /** Assert that. */
        assertThat(entry.taskId).isNull()
        /** Assert that. */
        assertThat(entry.focusRating).isNull()
        /** Assert that. */
        assertThat(entry.focusNote).isNull()
        /** Assert that. */
        assertThat(entry.focusRatedAt).isNull()
        /** Assert that. */
        assertThat(entry.importSource).isNull()
        /** Assert that. */
        assertThat(entry.importId).isNull()
        /** Assert that. */
        assertThat(entry.importedAt).isNull()
        /** Assert that. */
        assertThat(entry.importBatchId).isNull()
    }

    @Test
    /**
     * Note entity exposes fields.
     */
    fun note_entity_exposesFields() {
        /** Note. */
        val note =
            /** Note entity. */
            NoteEntity(
                id = "note-1",
                title = "Title",
                details = "Details",
                lifeIntentionCategory = "Career & Work",
                dimensionId = "dim_career_work",
                dayKey = "2026-01-31",
                createdAt = "2026-01-31T08:00:00",
                updatedAt = "2026-01-31T08:15:00",
            )
        /** Assert that. */
        assertThat(note.id).isEqualTo("note-1")
        /** Assert that. */
        assertThat(note.title).isEqualTo("Title")
        /** Assert that. */
        assertThat(note.details).isEqualTo("Details")
        /** Assert that. */
        assertThat(note.lifeIntentionCategory).isEqualTo("Career & Work")
        /** Assert that. */
        assertThat(note.dimensionId).isEqualTo("dim_career_work")
        /** Assert that. */
        assertThat(note.dayKey).isEqualTo("2026-01-31")
        /** Assert that. */
        assertThat(note.createdAt).isEqualTo("2026-01-31T08:00:00")
        /** Assert that. */
        assertThat(note.updatedAt).isEqualTo("2026-01-31T08:15:00")
    }

    @Test
    /**
     * Note entity default optional fields are null.
     */
    fun note_entity_default_optional_fields_are_null() {
        /** Note. */
        val note =
            /** Note entity. */
            NoteEntity(
                id = "note-2",
                title = "Title",
                details = "Details",
                lifeIntentionCategory = "Career & Work",
                createdAt = "2026-01-31T09:00:00",
                updatedAt = "2026-01-31T09:00:00",
            )
        /** Assert that. */
        assertThat(note.dimensionId).isNull()
        /** Assert that. */
        assertThat(note.dayKey).isNull()
    }

    @Test
    /**
     * Task entity dimension id defaults and explicit values work.
     */
    fun taskEntity_dimensionId_defaults_and_explicit_values_work() {
        /** Task with defaults. */
        val taskWithDefaults =
            /** Task entity. */
            TaskEntity(
                id = "task-default",
                title = "Default Task",
                createdAt = "2026-01-31T08:00:00",
                updatedAt = "2026-01-31T08:00:00",
            )
        /** Assert that. */
        assertThat(taskWithDefaults.dimensionId).isNull()
        /** Assert that. */
        assertThat(taskWithDefaults.dayKey).isNull()
        /** Assert that. */
        assertThat(taskWithDefaults.importSource).isNull()
        /** Assert that. */
        assertThat(taskWithDefaults.importId).isNull()
        /** Assert that. */
        assertThat(taskWithDefaults.importedAt).isNull()
        /** Assert that. */
        assertThat(taskWithDefaults.importBatchId).isNull()

        /** Task with dimension. */
        val taskWithDimension =
            taskWithDefaults.copy(
                id = "task-dim",
                lifeIntentionCategory = "Learning",
                dimensionId = "dim_learning",
                dayKey = "2026-01-31",
                importSource = "uhabits",
                importId = "habit_42",
                importedAt = "2026-01-31T08:10:00",
                importBatchId = "batch_2",
            )
        /** Assert that. */
        assertThat(taskWithDimension.dimensionId).isEqualTo("dim_learning")
        /** Assert that. */
        assertThat(taskWithDimension.lifeIntentionCategory).isEqualTo("Learning")
        /** Assert that. */
        assertThat(taskWithDimension.dayKey).isEqualTo("2026-01-31")
        /** Assert that. */
        assertThat(taskWithDimension.importSource).isEqualTo("uhabits")
        /** Assert that. */
        assertThat(taskWithDimension.importId).isEqualTo("habit_42")
        /** Assert that. */
        assertThat(taskWithDimension.importedAt).isEqualTo("2026-01-31T08:10:00")
        /** Assert that. */
        assertThat(taskWithDimension.importBatchId).isEqualTo("batch_2")
    }

    @Test
    /**
     * Life dimension and user preference entities store values.
     */
    fun lifeDimension_and_userPreference_entities_store_values() {
        /** Dimension. */
        val dimension =
            /** Life dimension entity. */
            LifeDimensionEntity(
                id = "dim_unassigned",
                key = "unassigned",
                label = "Unassigned",
                description = "Fallback",
                color = "#9E9E9E",
                icon = "help_outline",
                sortOrder = 9999,
                isActive = 1,
                createdAt = "2026-01-31T08:00:00",
                updatedAt = "2026-01-31T08:00:00",
            )
        /** Assert that. */
        assertThat(dimension.id).isEqualTo("dim_unassigned")
        /** Assert that. */
        assertThat(dimension.key).isEqualTo("unassigned")
        /** Assert that. */
        assertThat(dimension.label).isEqualTo("Unassigned")
        /** Assert that. */
        assertThat(dimension.sortOrder).isEqualTo(9999)
        /** Assert that. */
        assertThat(dimension.isActive).isEqualTo(1)

        /** Pref. */
        val pref =
            /** User preference entity. */
            UserPreferenceEntity(
                key = "time_format",
                valueType = "string",
                stringValue = "24h",
                intValue = null,
                doubleValue = null,
                boolValue = null,
                updatedAt = "2026-01-31T08:05:00",
            )
        /** Assert that. */
        assertThat(pref.key).isEqualTo("time_format")
        /** Assert that. */
        assertThat(pref.valueType).isEqualTo("string")
        /** Assert that. */
        assertThat(pref.stringValue).isEqualTo("24h")
        /** Assert that. */
        assertThat(pref.updatedAt).isEqualTo("2026-01-31T08:05:00")
    }

    @Test
    /**
     * Life dimension entity default optional fields are applied.
     */
    fun lifeDimension_entity_default_optional_fields_are_applied() {
        /** Dimension. */
        val dimension =
            /** Life dimension entity. */
            LifeDimensionEntity(
                id = "dim_learning",
                key = "learning",
                label = "Learning",
                color = "#42A5F5",
                sortOrder = 3,
                createdAt = "2026-01-31T10:00:00",
                updatedAt = "2026-01-31T10:00:00",
            )
        /** Assert that. */
        assertThat(dimension.description).isNull()
        /** Assert that. */
        assertThat(dimension.icon).isNull()
        /** Assert that. */
        assertThat(dimension.isActive).isEqualTo(1)
    }

    @Test
    /**
     * User preference entity default optional fields are applied.
     */
    fun userPreference_entity_default_optional_fields_are_applied() {
        /** Preference. */
        val preference =
            /** User preference entity. */
            UserPreferenceEntity(
                key = "week_starts_on",
                valueType = "string",
                updatedAt = "2026-01-31T10:05:00",
            )
        /** Assert that. */
        assertThat(preference.stringValue).isNull()
        /** Assert that. */
        assertThat(preference.intValue).isNull()
        /** Assert that. */
        assertThat(preference.doubleValue).isNull()
        /** Assert that. */
        assertThat(preference.boolValue).isNull()
    }

    @Test
    /**
     * User preference entity numeric and boolean values are supported.
     */
    fun userPreference_entity_numeric_and_boolean_values_are_supported() {
        /** Int preference. */
        val intPreference =
            /** User preference entity. */
            UserPreferenceEntity(
                key = "timescale_minutes",
                valueType = "int",
                intValue = 30,
                updatedAt = "2026-01-31T10:10:00",
            )
        /** Assert that. */
        assertThat(intPreference.intValue).isEqualTo(30)

        /** Double preference. */
        val doublePreference =
            /** User preference entity. */
            UserPreferenceEntity(
                key = "focus_threshold",
                valueType = "double",
                doubleValue = 0.65,
                updatedAt = "2026-01-31T10:11:00",
            )
        /** Assert that. */
        assertThat(doublePreference.doubleValue).isWithin(0.0001).of(0.65)

        /** Bool preference. */
        val boolPreference =
            /** User preference entity. */
            UserPreferenceEntity(
                key = "use_system_language",
                valueType = "boolean",
                boolValue = 1,
                updatedAt = "2026-01-31T10:12:00",
            )
        /** Assert that. */
        assertThat(boolPreference.boolValue).isEqualTo(1)
    }

    @Test
    /**
     * Import batch entity stores values.
     */
    fun importBatch_entity_stores_values() {
        /** Batch. */
        val batch =
            /** Import batch entity. */
            ImportBatchEntity(
                id = "batch_100",
                source = "custom",
                importedAt = "2026-01-31T10:20:00",
                version = "1.2.3",
                fileHash = "sha256:deadbeef",
                notes = "Initial migration import",
            )
        /** Assert that. */
        assertThat(batch.id).isEqualTo("batch_100")
        /** Assert that. */
        assertThat(batch.source).isEqualTo("custom")
        /** Assert that. */
        assertThat(batch.importedAt).isEqualTo("2026-01-31T10:20:00")
        /** Assert that. */
        assertThat(batch.version).isEqualTo("1.2.3")
        /** Assert that. */
        assertThat(batch.fileHash).isEqualTo("sha256:deadbeef")
        /** Assert that. */
        assertThat(batch.notes).isEqualTo("Initial migration import")
    }

    @Test
    /**
     * Import batch entity defaults and copy work.
     */
    fun importBatch_entity_defaults_and_copy_work() {
        /** Minimal. */
        val minimal =
            /** Import batch entity. */
            ImportBatchEntity(
                id = "batch_min",
                source = "uhabits",
                importedAt = "2026-02-01T00:00:00",
            )

        /** Assert that. */
        assertThat(minimal.version).isNull()
        /** Assert that. */
        assertThat(minimal.fileHash).isNull()
        /** Assert that. */
        assertThat(minimal.notes).isNull()

        /** Copied. */
        val copied =
            minimal.copy(
                version = "2.0",
                fileHash = "sha256:abcd",
                notes = "copied",
            )
        /** Id. */
        val id = copied.component1()
        /** Source. */
        val source = copied.component2()
        /** Imported at. */
        val importedAt = copied.component3()
        /** Version. */
        val version = copied.component4()
        /** File hash. */
        val fileHash = copied.component5()
        /** Notes. */
        val notes = copied.component6()

        /** Assert that. */
        assertThat(id).isEqualTo("batch_min")
        /** Assert that. */
        assertThat(source).isEqualTo("uhabits")
        /** Assert that. */
        assertThat(importedAt).isEqualTo("2026-02-01T00:00:00")
        /** Assert that. */
        assertThat(version).isEqualTo("2.0")
        /** Assert that. */
        assertThat(fileHash).isEqualTo("sha256:abcd")
        /** Assert that. */
        assertThat(notes).isEqualTo("copied")
        /** Assert that. */
        assertThat(copied.toString()).contains("batch_min")
    }

    @Test
    /**
     * Daily insight entity stores values.
     */
    fun dailyInsight_entity_stores_values() {
        /** Insight. */
        val insight =
            /** Daily insight entity. */
            DailyInsightEntity(
                id = "insight-2026-01-31-time-dim_learning",
                dayKey = "2026-01-31",
                module = "time",
                dimensionId = "dim_learning",
                plannedMinutes = 180,
                actualMinutes = 150,
                focusedMinutes = 120,
                completedCount = 3,
                totalCount = 4,
                summaryJson = "{\"focusPct\":0.8}",
                generatedAt = "2026-01-31T23:59:00",
            )

        /** Assert that. */
        assertThat(insight.id).isEqualTo("insight-2026-01-31-time-dim_learning")
        /** Assert that. */
        assertThat(insight.dayKey).isEqualTo("2026-01-31")
        /** Assert that. */
        assertThat(insight.module).isEqualTo("time")
        /** Assert that. */
        assertThat(insight.dimensionId).isEqualTo("dim_learning")
        /** Assert that. */
        assertThat(insight.plannedMinutes).isEqualTo(180)
        /** Assert that. */
        assertThat(insight.actualMinutes).isEqualTo(150)
        /** Assert that. */
        assertThat(insight.focusedMinutes).isEqualTo(120)
        /** Assert that. */
        assertThat(insight.completedCount).isEqualTo(3)
        /** Assert that. */
        assertThat(insight.totalCount).isEqualTo(4)
        /** Assert that. */
        assertThat(insight.summaryJson).isEqualTo("{\"focusPct\":0.8}")
        /** Assert that. */
        assertThat(insight.generatedAt).isEqualTo("2026-01-31T23:59:00")
    }

    @Test
    /**
     * Daily insight entity defaults and copy work.
     */
    fun dailyInsight_entity_defaults_and_copy_work() {
        /** Minimal. */
        val minimal =
            /** Daily insight entity. */
            DailyInsightEntity(
                id = "insight-min",
                dayKey = "2026-02-01",
                module = "overall",
                generatedAt = "2026-02-01T23:59:00",
            )

        /** Assert that. */
        assertThat(minimal.dimensionId).isNull()
        /** Assert that. */
        assertThat(minimal.plannedMinutes).isNull()
        /** Assert that. */
        assertThat(minimal.actualMinutes).isNull()
        /** Assert that. */
        assertThat(minimal.focusedMinutes).isNull()
        /** Assert that. */
        assertThat(minimal.completedCount).isNull()
        /** Assert that. */
        assertThat(minimal.totalCount).isNull()
        /** Assert that. */
        assertThat(minimal.summaryJson).isNull()

        /** Copied. */
        val copied =
            minimal.copy(
                dimensionId = "dim_learning",
                plannedMinutes = 60,
                actualMinutes = 45,
                focusedMinutes = 40,
                completedCount = 2,
                totalCount = 3,
                summaryJson = "{\"adherence\":0.75}",
            )
        /** Id. */
        val id = copied.component1()
        /** Day key. */
        val dayKey = copied.component2()
        /** Module. */
        val module = copied.component3()
        /** Dimension id. */
        val dimensionId = copied.component4()
        /** Planned minutes. */
        val plannedMinutes = copied.component5()
        /** Actual minutes. */
        val actualMinutes = copied.component6()
        /** Focused minutes. */
        val focusedMinutes = copied.component7()
        /** Completed count. */
        val completedCount = copied.component8()
        /** Total count. */
        val totalCount = copied.component9()
        /** Summary json. */
        val summaryJson = copied.component10()
        /** Generated at. */
        val generatedAt = copied.component11()

        /** Assert that. */
        assertThat(id).isEqualTo("insight-min")
        /** Assert that. */
        assertThat(dayKey).isEqualTo("2026-02-01")
        /** Assert that. */
        assertThat(module).isEqualTo("overall")
        /** Assert that. */
        assertThat(dimensionId).isEqualTo("dim_learning")
        /** Assert that. */
        assertThat(plannedMinutes).isEqualTo(60)
        /** Assert that. */
        assertThat(actualMinutes).isEqualTo(45)
        /** Assert that. */
        assertThat(focusedMinutes).isEqualTo(40)
        /** Assert that. */
        assertThat(completedCount).isEqualTo(2)
        /** Assert that. */
        assertThat(totalCount).isEqualTo(3)
        /** Assert that. */
        assertThat(summaryJson).isEqualTo("{\"adherence\":0.75}")
        /** Assert that. */
        assertThat(generatedAt).isEqualTo("2026-02-01T23:59:00")
        /** Assert that. */
        assertThat(copied.toString()).contains("insight-min")
    }

    @Test
    /**
     * Tag and mapping entities store values.
     */
    fun tag_and_mapping_entities_store_values() {
        /** Tag. */
        val tag =
            /** Tag entity. */
            TagEntity(
                id = "tag-1",
                name = "Deep Work",
                normalizedName = "deep work",
                usageCount = 3,
                lastUsedAt = "2026-02-08T08:00:00",
                createdAt = "2026-02-01T08:00:00",
                updatedAt = "2026-02-08T08:00:00",
            )
        /** Assert that. */
        assertThat(tag.id).isEqualTo("tag-1")
        /** Assert that. */
        assertThat(tag.name).isEqualTo("Deep Work")
        /** Assert that. */
        assertThat(tag.normalizedName).isEqualTo("deep work")
        /** Assert that. */
        assertThat(tag.usageCount).isEqualTo(3)
        /** Assert that. */
        assertThat(tag.lastUsedAt).isEqualTo("2026-02-08T08:00:00")

        /** Task tag. */
        val taskTag =
            /** Task tag entity. */
            TaskTagEntity(
                taskId = "task-1",
                tagId = "tag-1",
                createdAt = "2026-02-08T08:01:00",
            )
        /** Note tag. */
        val noteTag =
            /** Note tag entity. */
            NoteTagEntity(
                noteId = "note-1",
                tagId = "tag-1",
                createdAt = "2026-02-08T08:02:00",
            )
        /** Time entry tag. */
        val timeEntryTag =
            /** Time entry tag entity. */
            TimeEntryTagEntity(
                timeEntryId = "time-1",
                tagId = "tag-1",
                createdAt = "2026-02-08T08:03:00",
            )
        /** Assert that. */
        assertThat(taskTag.taskId).isEqualTo("task-1")
        /** Assert that. */
        assertThat(taskTag.tagId).isEqualTo("tag-1")
        /** Assert that. */
        assertThat(noteTag.noteId).isEqualTo("note-1")
        /** Assert that. */
        assertThat(noteTag.tagId).isEqualTo("tag-1")
        /** Assert that. */
        assertThat(timeEntryTag.timeEntryId).isEqualTo("time-1")
        /** Assert that. */
        assertThat(timeEntryTag.tagId).isEqualTo("tag-1")
    }

    @Test
    /**
     * Tag and mapping entities copy component and to string work.
     */
    fun tag_and_mapping_entities_copy_component_and_toString_work() {
        /** Tag. */
        val tag =
            /** Tag entity. */
            TagEntity(
                id = "tag-2",
                name = "Reading",
                normalizedName = "reading",
                usageCount = 0,
                createdAt = "2026-02-08T09:00:00",
                updatedAt = "2026-02-08T09:00:00",
            )
        /** Copied tag. */
        val copiedTag = tag.copy(usageCount = 1, lastUsedAt = "2026-02-08T10:00:00")
        /** Tag id. */
        val tagId = copiedTag.component1()
        /** Tag name. */
        val tagName = copiedTag.component2()
        /** Normalized name. */
        val normalizedName = copiedTag.component3()
        /** Usage count. */
        val usageCount = copiedTag.component4()
        /** Last used at. */
        val lastUsedAt = copiedTag.component5()
        /** Assert that. */
        assertThat(tagId).isEqualTo("tag-2")
        /** Assert that. */
        assertThat(tagName).isEqualTo("Reading")
        /** Assert that. */
        assertThat(normalizedName).isEqualTo("reading")
        /** Assert that. */
        assertThat(usageCount).isEqualTo(1)
        /** Assert that. */
        assertThat(lastUsedAt).isEqualTo("2026-02-08T10:00:00")
        /** Assert that. */
        assertThat(copiedTag.toString()).contains("tag-2")

        /** Task tag. */
        val taskTag = TaskTagEntity("task-2", "tag-2", "2026-02-08T09:05:00")
        /** Copied task tag. */
        val copiedTaskTag = taskTag.copy(createdAt = "2026-02-08T10:05:00")
        /** Assert that. */
        assertThat(copiedTaskTag.component1()).isEqualTo("task-2")
        /** Assert that. */
        assertThat(copiedTaskTag.component2()).isEqualTo("tag-2")
        /** Assert that. */
        assertThat(copiedTaskTag.component3()).isEqualTo("2026-02-08T10:05:00")
        /** Assert that. */
        assertThat(copiedTaskTag.toString()).contains("task-2")

        /** Note tag. */
        val noteTag = NoteTagEntity("note-2", "tag-2", "2026-02-08T09:06:00")
        /** Copied note tag. */
        val copiedNoteTag = noteTag.copy(createdAt = "2026-02-08T10:06:00")
        /** Assert that. */
        assertThat(copiedNoteTag.component1()).isEqualTo("note-2")
        /** Assert that. */
        assertThat(copiedNoteTag.component2()).isEqualTo("tag-2")
        /** Assert that. */
        assertThat(copiedNoteTag.component3()).isEqualTo("2026-02-08T10:06:00")
        /** Assert that. */
        assertThat(copiedNoteTag.toString()).contains("note-2")

        /** Time entry tag. */
        val timeEntryTag = TimeEntryTagEntity("time-2", "tag-2", "2026-02-08T09:07:00")
        /** Copied time entry tag. */
        val copiedTimeEntryTag = timeEntryTag.copy(createdAt = "2026-02-08T10:07:00")
        /** Assert that. */
        assertThat(copiedTimeEntryTag.component1()).isEqualTo("time-2")
        /** Assert that. */
        assertThat(copiedTimeEntryTag.component2()).isEqualTo("tag-2")
        /** Assert that. */
        assertThat(copiedTimeEntryTag.component3()).isEqualTo("2026-02-08T10:07:00")
        /** Assert that. */
        assertThat(copiedTimeEntryTag.toString()).contains("time-2")
    }

    @Test
    /**
     * Time goal and time rule entities store values and defaults.
     */
    fun timeGoal_and_timeRule_entities_store_values_and_defaults() {
        /** Goal. */
        val goal =
            /** Time goal entity. */
            TimeGoalEntity(
                id = "goal-1",
                name = "Deep Work Daily",
                dimensionId = "dim_career_work",
                targetMinutes = 180,
                period = "daily",
                isActive = 1,
                notes = "Primary focus",
                createdAt = "2026-02-08T11:00:00",
                updatedAt = "2026-02-08T11:00:00",
            )
        /** Assert that. */
        assertThat(goal.id).isEqualTo("goal-1")
        /** Assert that. */
        assertThat(goal.dimensionId).isEqualTo("dim_career_work")
        /** Assert that. */
        assertThat(goal.targetMinutes).isEqualTo(180)
        /** Assert that. */
        assertThat(goal.period).isEqualTo("daily")
        /** Assert that. */
        assertThat(goal.isActive).isEqualTo(1)
        /** Assert that. */
        assertThat(goal.notes).isEqualTo("Primary focus")

        /** Minimal goal. */
        val minimalGoal =
            /** Time goal entity. */
            TimeGoalEntity(
                id = "goal-2",
                name = "Learning Weekly",
                targetMinutes = 420,
                period = "weekly",
                createdAt = "2026-02-08T12:00:00",
                updatedAt = "2026-02-08T12:00:00",
            )
        /** Assert that. */
        assertThat(minimalGoal.dimensionId).isNull()
        /** Assert that. */
        assertThat(minimalGoal.notes).isNull()
        /** Assert that. */
        assertThat(minimalGoal.isActive).isEqualTo(1)

        /** Copied goal. */
        val copiedGoal = goal.copy(targetMinutes = 200)
        /** Assert that. */
        assertThat(copiedGoal.component1()).isEqualTo("goal-1")
        /** Assert that. */
        assertThat(copiedGoal.component4()).isEqualTo(200)
        /** Assert that. */
        assertThat(copiedGoal.toString()).contains("goal-1")

        /** Rule. */
        val rule =
            /** Time rule entity. */
            TimeRuleEntity(
                id = "rule-1",
                name = "Max Continuous Block",
                ruleType = "max_continuous_minutes",
                configJson = "{\"minutes\":120}",
                isActive = 1,
                createdAt = "2026-02-08T11:30:00",
                updatedAt = "2026-02-08T11:30:00",
            )
        /** Assert that. */
        assertThat(rule.id).isEqualTo("rule-1")
        /** Assert that. */
        assertThat(rule.ruleType).isEqualTo("max_continuous_minutes")
        /** Assert that. */
        assertThat(rule.configJson).isEqualTo("{\"minutes\":120}")
        /** Assert that. */
        assertThat(rule.isActive).isEqualTo(1)

        /** Copied rule. */
        val copiedRule = rule.copy(isActive = 0)
        /** Assert that. */
        assertThat(copiedRule.component1()).isEqualTo("rule-1")
        /** Assert that. */
        assertThat(copiedRule.component5()).isEqualTo(0)
        /** Assert that. */
        assertThat(copiedRule.toString()).contains("rule-1")
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
