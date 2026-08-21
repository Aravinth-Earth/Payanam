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
        val occurrence =
            TaskOccurrenceEntity(
                id = "occ-1",
                taskId = "task-1",
                dueDate = "2026-01-31T09:00:00",
                status = "completed",
                createdAt = "2026-01-31T09:00:00",
            )
        assertThat(occurrence.id).isEqualTo("occ-1")
        assertThat(occurrence.taskId).isEqualTo("task-1")
        assertThat(occurrence.dueDate).isEqualTo("2026-01-31T09:00:00")
        assertThat(occurrence.status).isEqualTo("completed")
        assertThat(occurrence.createdAt).isEqualTo("2026-01-31T09:00:00")
        assertThat(occurrence.completionRate).isNull()
        assertThat(occurrence.note).isNull()
    }

    @Test
    /**
     * Task reschedule stores values.
     */
    fun taskReschedule_storesValues() {
        val reschedule =
            TaskRescheduleEntity(
                id = "res-1",
                taskId = "task-1",
                previousDueDate = "2026-01-31T09:00:00",
                newDueDate = "2026-02-01T09:00:00",
                rescheduledAt = "2026-01-30T09:00:00",
                wasOverdue = 0,
            )
        assertThat(reschedule.id).isEqualTo("res-1")
        assertThat(reschedule.taskId).isEqualTo("task-1")
        assertThat(reschedule.previousDueDate).isEqualTo("2026-01-31T09:00:00")
        assertThat(reschedule.newDueDate).isEqualTo("2026-02-01T09:00:00")
        assertThat(reschedule.rescheduledAt).isEqualTo("2026-01-30T09:00:00")
        assertThat(reschedule.wasOverdue).isEqualTo(0)
    }

    @Test
    /**
     * Journal entities hold values.
     */
    fun journal_entities_holdValues() {
        val entry =
            DayJournalEntryEntity(
                id = "entry-1",
                entryDate = "2026-01-31",
                createdAt = "2026-01-31T09:00:00",
                updatedAt = "2026-01-31T09:00:00",
            )
        val response =
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
        assertThat(entry.id).isEqualTo("entry-1")
        assertThat(entry.entryDate).isEqualTo("2026-01-31")
        assertThat(entry.createdAt).isEqualTo("2026-01-31T09:00:00")
        assertThat(entry.updatedAt).isEqualTo("2026-01-31T09:00:00")
        assertThat(response.entryId).isEqualTo(entry.id)
        assertThat(response.scope).isEqualTo("OVERALL")
        assertThat(response.dimensionKey).isEqualTo("health")
        assertThat(response.dimensionId).isEqualTo("dim_health_wellness")
        assertThat(response.promptKey).isEqualTo("gratitude")
        assertThat(response.responseText).isEqualTo("Thanks")
        assertThat(response.createdAt).isEqualTo("2026-01-31T09:00:00")
        assertThat(response.updatedAt).isEqualTo("2026-01-31T09:00:00")
    }

    @Test
    /**
     * App setting entity stores value.
     */
    fun appSetting_entity_storesValue() {
        val setting =
            AppSettingEntity(
                key = "notificationsEnabled",
                value = "true",
                updatedAt = "2026-01-31T09:00:00",
            )
        assertThat(setting.key).isEqualTo("notificationsEnabled")
        assertThat(setting.value).isEqualTo("true")
        assertThat(setting.updatedAt).isEqualTo("2026-01-31T09:00:00")
    }

    @Test
    /**
     * Scheduled notification defaults are applied.
     */
    fun scheduledNotification_defaults_areApplied() {
        val notification =
            ScheduledNotificationEntity(
                id = "notif-1",
                taskId = "task-1",
                scheduledAt = "2026-01-31T09:00:00",
                notificationType = "task_reminder",
                title = "Title",
                body = "Body",
                createdAt = "2026-01-31T09:00:00",
            )
        assertThat(notification.id).isEqualTo("notif-1")
        assertThat(notification.taskId).isEqualTo("task-1")
        assertThat(notification.scheduledAt).isEqualTo("2026-01-31T09:00:00")
        assertThat(notification.notificationType).isEqualTo("task_reminder")
        assertThat(notification.title).isEqualTo("Title")
        assertThat(notification.body).isEqualTo("Body")
        assertThat(notification.createdAt).isEqualTo("2026-01-31T09:00:00")
        assertThat(notification.isDelivered).isEqualTo(0)
    }

    @Test
    /**
     * Time entry entity exposes fields.
     */
    fun timeEntry_entity_exposesFields() {
        val entry =
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
        assertThat(entry.id).isEqualTo("entry-1")
        assertThat(entry.lifeIntentionCategory).isEqualTo("Learning")
        assertThat(entry.dimensionId).isEqualTo("dim_learning")
        assertThat(entry.dayKey).isEqualTo("2026-01-31")
        assertThat(entry.taskId).isEqualTo("task-1")
        assertThat(entry.startedAt).isEqualTo("2026-01-31T08:00:00")
        assertThat(entry.endedAt).isEqualTo("2026-01-31T08:30:00")
        assertThat(entry.focusRating).isEqualTo(0.85)
        assertThat(entry.focusNote).isEqualTo("Deep work block")
        assertThat(entry.focusRatedAt).isEqualTo("2026-01-31T08:31:00")
        assertThat(entry.importSource).isEqualTo("custom")
        assertThat(entry.importId).isEqualTo("record_124")
        assertThat(entry.importedAt).isEqualTo("2026-01-31T08:40:00")
        assertThat(entry.importBatchId).isEqualTo("batch_1")
        assertThat(entry.createdAt).isEqualTo("2026-01-31T08:00:00")
        assertThat(entry.updatedAt).isEqualTo("2026-01-31T08:30:00")
    }

    @Test
    /**
     * Time entry entity default optional fields are null.
     */
    fun timeEntry_entity_default_optional_fields_are_null() {
        val entry =
            TimeEntryEntity(
                id = "entry-2",
                lifeIntentionCategory = "Learning",
                startedAt = "2026-01-31T09:00:00",
                endedAt = null,
                createdAt = "2026-01-31T09:00:00",
                updatedAt = "2026-01-31T09:00:00",
            )
        assertThat(entry.dimensionId).isNull()
        assertThat(entry.dayKey).isNull()
        assertThat(entry.taskId).isNull()
        assertThat(entry.focusRating).isNull()
        assertThat(entry.focusNote).isNull()
        assertThat(entry.focusRatedAt).isNull()
        assertThat(entry.importSource).isNull()
        assertThat(entry.importId).isNull()
        assertThat(entry.importedAt).isNull()
        assertThat(entry.importBatchId).isNull()
    }

    @Test
    /**
     * Note entity exposes fields.
     */
    fun note_entity_exposesFields() {
        val note =
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
        assertThat(note.id).isEqualTo("note-1")
        assertThat(note.title).isEqualTo("Title")
        assertThat(note.details).isEqualTo("Details")
        assertThat(note.lifeIntentionCategory).isEqualTo("Career & Work")
        assertThat(note.dimensionId).isEqualTo("dim_career_work")
        assertThat(note.dayKey).isEqualTo("2026-01-31")
        assertThat(note.createdAt).isEqualTo("2026-01-31T08:00:00")
        assertThat(note.updatedAt).isEqualTo("2026-01-31T08:15:00")
    }

    @Test
    /**
     * Note entity default optional fields are null.
     */
    fun note_entity_default_optional_fields_are_null() {
        val note =
            NoteEntity(
                id = "note-2",
                title = "Title",
                details = "Details",
                lifeIntentionCategory = "Career & Work",
                createdAt = "2026-01-31T09:00:00",
                updatedAt = "2026-01-31T09:00:00",
            )
        assertThat(note.dimensionId).isNull()
        assertThat(note.dayKey).isNull()
    }

    @Test
    /**
     * Task entity dimension id defaults and explicit values work.
     */
    fun taskEntity_dimensionId_defaults_and_explicit_values_work() {
        val taskWithDefaults =
            TaskEntity(
                id = "task-default",
                title = "Default Task",
                createdAt = "2026-01-31T08:00:00",
                updatedAt = "2026-01-31T08:00:00",
            )
        assertThat(taskWithDefaults.dimensionId).isNull()
        assertThat(taskWithDefaults.dayKey).isNull()
        assertThat(taskWithDefaults.importSource).isNull()
        assertThat(taskWithDefaults.importId).isNull()
        assertThat(taskWithDefaults.importedAt).isNull()
        assertThat(taskWithDefaults.importBatchId).isNull()
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
        assertThat(taskWithDimension.dimensionId).isEqualTo("dim_learning")
        assertThat(taskWithDimension.lifeIntentionCategory).isEqualTo("Learning")
        assertThat(taskWithDimension.dayKey).isEqualTo("2026-01-31")
        assertThat(taskWithDimension.importSource).isEqualTo("uhabits")
        assertThat(taskWithDimension.importId).isEqualTo("habit_42")
        assertThat(taskWithDimension.importedAt).isEqualTo("2026-01-31T08:10:00")
        assertThat(taskWithDimension.importBatchId).isEqualTo("batch_2")
    }

    @Test
    /**
     * Life dimension and user preference entities store values.
     */
    fun lifeDimension_and_userPreference_entities_store_values() {
        val dimension =
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
        assertThat(dimension.id).isEqualTo("dim_unassigned")
        assertThat(dimension.key).isEqualTo("unassigned")
        assertThat(dimension.label).isEqualTo("Unassigned")
        assertThat(dimension.sortOrder).isEqualTo(9999)
        assertThat(dimension.isActive).isEqualTo(1)
        val pref =
            UserPreferenceEntity(
                key = "time_format",
                valueType = "string",
                stringValue = "24h",
                intValue = null,
                doubleValue = null,
                boolValue = null,
                updatedAt = "2026-01-31T08:05:00",
            )
        assertThat(pref.key).isEqualTo("time_format")
        assertThat(pref.valueType).isEqualTo("string")
        assertThat(pref.stringValue).isEqualTo("24h")
        assertThat(pref.updatedAt).isEqualTo("2026-01-31T08:05:00")
    }

    @Test
    /**
     * Life dimension entity default optional fields are applied.
     */
    fun lifeDimension_entity_default_optional_fields_are_applied() {
        val dimension =
            LifeDimensionEntity(
                id = "dim_learning",
                key = "learning",
                label = "Learning",
                color = "#42A5F5",
                sortOrder = 3,
                createdAt = "2026-01-31T10:00:00",
                updatedAt = "2026-01-31T10:00:00",
            )
        assertThat(dimension.description).isNull()
        assertThat(dimension.icon).isNull()
        assertThat(dimension.isActive).isEqualTo(1)
    }

    @Test
    /**
     * User preference entity default optional fields are applied.
     */
    fun userPreference_entity_default_optional_fields_are_applied() {
        val preference =
            UserPreferenceEntity(
                key = "week_starts_on",
                valueType = "string",
                updatedAt = "2026-01-31T10:05:00",
            )
        assertThat(preference.stringValue).isNull()
        assertThat(preference.intValue).isNull()
        assertThat(preference.doubleValue).isNull()
        assertThat(preference.boolValue).isNull()
    }

    @Test
    /**
     * User preference entity numeric and boolean values are supported.
     */
    fun userPreference_entity_numeric_and_boolean_values_are_supported() {
        val intPreference =
            UserPreferenceEntity(
                key = "timescale_minutes",
                valueType = "int",
                intValue = 30,
                updatedAt = "2026-01-31T10:10:00",
            )
        assertThat(intPreference.intValue).isEqualTo(30)
        val doublePreference =
            UserPreferenceEntity(
                key = "focus_threshold",
                valueType = "double",
                doubleValue = 0.65,
                updatedAt = "2026-01-31T10:11:00",
            )
        assertThat(doublePreference.doubleValue).isWithin(0.0001).of(0.65)
        val boolPreference =
            UserPreferenceEntity(
                key = "use_system_language",
                valueType = "boolean",
                boolValue = 1,
                updatedAt = "2026-01-31T10:12:00",
            )
        assertThat(boolPreference.boolValue).isEqualTo(1)
    }

    @Test
    /**
     * Import batch entity stores values.
     */
    fun importBatch_entity_stores_values() {
        val batch =
            ImportBatchEntity(
                id = "batch_100",
                source = "custom",
                importedAt = "2026-01-31T10:20:00",
                version = "1.2.3",
                fileHash = "sha256:deadbeef",
                notes = "Initial migration import",
            )
        assertThat(batch.id).isEqualTo("batch_100")
        assertThat(batch.source).isEqualTo("custom")
        assertThat(batch.importedAt).isEqualTo("2026-01-31T10:20:00")
        assertThat(batch.version).isEqualTo("1.2.3")
        assertThat(batch.fileHash).isEqualTo("sha256:deadbeef")
        assertThat(batch.notes).isEqualTo("Initial migration import")
    }

    @Test
    /**
     * Import batch entity defaults and copy work.
     */
    fun importBatch_entity_defaults_and_copy_work() {
        val minimal =
            ImportBatchEntity(
                id = "batch_min",
                source = "uhabits",
                importedAt = "2026-02-01T00:00:00",
            )
        assertThat(minimal.version).isNull()
        assertThat(minimal.fileHash).isNull()
        assertThat(minimal.notes).isNull()
        val copied =
            minimal.copy(
                version = "2.0",
                fileHash = "sha256:abcd",
                notes = "copied",
            )
        val id = copied.component1()
        val source = copied.component2()
        val importedAt = copied.component3()
        val version = copied.component4()
        val fileHash = copied.component5()
        val notes = copied.component6()
        assertThat(id).isEqualTo("batch_min")
        assertThat(source).isEqualTo("uhabits")
        assertThat(importedAt).isEqualTo("2026-02-01T00:00:00")
        assertThat(version).isEqualTo("2.0")
        assertThat(fileHash).isEqualTo("sha256:abcd")
        assertThat(notes).isEqualTo("copied")
        assertThat(copied.toString()).contains("batch_min")
    }

    @Test
    /**
     * Daily insight entity stores values.
     */
    fun dailyInsight_entity_stores_values() {
        val insight =
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
        assertThat(insight.id).isEqualTo("insight-2026-01-31-time-dim_learning")
        assertThat(insight.dayKey).isEqualTo("2026-01-31")
        assertThat(insight.module).isEqualTo("time")
        assertThat(insight.dimensionId).isEqualTo("dim_learning")
        assertThat(insight.plannedMinutes).isEqualTo(180)
        assertThat(insight.actualMinutes).isEqualTo(150)
        assertThat(insight.focusedMinutes).isEqualTo(120)
        assertThat(insight.completedCount).isEqualTo(3)
        assertThat(insight.totalCount).isEqualTo(4)
        assertThat(insight.summaryJson).isEqualTo("{\"focusPct\":0.8}")
        assertThat(insight.generatedAt).isEqualTo("2026-01-31T23:59:00")
    }

    @Test
    /**
     * Daily insight entity defaults and copy work.
     */
    fun dailyInsight_entity_defaults_and_copy_work() {
        val minimal =
            DailyInsightEntity(
                id = "insight-min",
                dayKey = "2026-02-01",
                module = "overall",
                generatedAt = "2026-02-01T23:59:00",
            )
        assertThat(minimal.dimensionId).isNull()
        assertThat(minimal.plannedMinutes).isNull()
        assertThat(minimal.actualMinutes).isNull()
        assertThat(minimal.focusedMinutes).isNull()
        assertThat(minimal.completedCount).isNull()
        assertThat(minimal.totalCount).isNull()
        assertThat(minimal.summaryJson).isNull()
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
        val id = copied.component1()
        val dayKey = copied.component2()
        val module = copied.component3()
        val dimensionId = copied.component4()
        val plannedMinutes = copied.component5()
        val actualMinutes = copied.component6()
        val focusedMinutes = copied.component7()
        val completedCount = copied.component8()
        val totalCount = copied.component9()
        val summaryJson = copied.component10()
        val generatedAt = copied.component11()
        assertThat(id).isEqualTo("insight-min")
        assertThat(dayKey).isEqualTo("2026-02-01")
        assertThat(module).isEqualTo("overall")
        assertThat(dimensionId).isEqualTo("dim_learning")
        assertThat(plannedMinutes).isEqualTo(60)
        assertThat(actualMinutes).isEqualTo(45)
        assertThat(focusedMinutes).isEqualTo(40)
        assertThat(completedCount).isEqualTo(2)
        assertThat(totalCount).isEqualTo(3)
        assertThat(summaryJson).isEqualTo("{\"adherence\":0.75}")
        assertThat(generatedAt).isEqualTo("2026-02-01T23:59:00")
        assertThat(copied.toString()).contains("insight-min")
    }

    @Test
    /**
     * Tag and mapping entities store values.
     */
    fun tag_and_mapping_entities_store_values() {
        val tag =
            TagEntity(
                id = "tag-1",
                name = "Deep Work",
                normalizedName = "deep work",
                usageCount = 3,
                lastUsedAt = "2026-02-08T08:00:00",
                createdAt = "2026-02-01T08:00:00",
                updatedAt = "2026-02-08T08:00:00",
            )
        assertThat(tag.id).isEqualTo("tag-1")
        assertThat(tag.name).isEqualTo("Deep Work")
        assertThat(tag.normalizedName).isEqualTo("deep work")
        assertThat(tag.usageCount).isEqualTo(3)
        assertThat(tag.lastUsedAt).isEqualTo("2026-02-08T08:00:00")
        val taskTag =
            TaskTagEntity(
                taskId = "task-1",
                tagId = "tag-1",
                createdAt = "2026-02-08T08:01:00",
            )
        val noteTag =
            NoteTagEntity(
                noteId = "note-1",
                tagId = "tag-1",
                createdAt = "2026-02-08T08:02:00",
            )
        val timeEntryTag =
            TimeEntryTagEntity(
                timeEntryId = "time-1",
                tagId = "tag-1",
                createdAt = "2026-02-08T08:03:00",
            )
        assertThat(taskTag.taskId).isEqualTo("task-1")
        assertThat(taskTag.tagId).isEqualTo("tag-1")
        assertThat(noteTag.noteId).isEqualTo("note-1")
        assertThat(noteTag.tagId).isEqualTo("tag-1")
        assertThat(timeEntryTag.timeEntryId).isEqualTo("time-1")
        assertThat(timeEntryTag.tagId).isEqualTo("tag-1")
    }

    @Test
    /**
     * Tag and mapping entities copy component and to string work.
     */
    fun tag_and_mapping_entities_copy_component_and_toString_work() {
        val tag =
            TagEntity(
                id = "tag-2",
                name = "Reading",
                normalizedName = "reading",
                usageCount = 0,
                createdAt = "2026-02-08T09:00:00",
                updatedAt = "2026-02-08T09:00:00",
            )
        val copiedTag = tag.copy(usageCount = 1, lastUsedAt = "2026-02-08T10:00:00")
        val tagId = copiedTag.component1()
        val tagName = copiedTag.component2()
        val normalizedName = copiedTag.component3()
        val usageCount = copiedTag.component4()
        val lastUsedAt = copiedTag.component5()
        assertThat(tagId).isEqualTo("tag-2")
        assertThat(tagName).isEqualTo("Reading")
        assertThat(normalizedName).isEqualTo("reading")
        assertThat(usageCount).isEqualTo(1)
        assertThat(lastUsedAt).isEqualTo("2026-02-08T10:00:00")
        assertThat(copiedTag.toString()).contains("tag-2")
        val taskTag = TaskTagEntity("task-2", "tag-2", "2026-02-08T09:05:00")
        val copiedTaskTag = taskTag.copy(createdAt = "2026-02-08T10:05:00")
        assertThat(copiedTaskTag.component1()).isEqualTo("task-2")
        assertThat(copiedTaskTag.component2()).isEqualTo("tag-2")
        assertThat(copiedTaskTag.component3()).isEqualTo("2026-02-08T10:05:00")
        assertThat(copiedTaskTag.toString()).contains("task-2")
        val noteTag = NoteTagEntity("note-2", "tag-2", "2026-02-08T09:06:00")
        val copiedNoteTag = noteTag.copy(createdAt = "2026-02-08T10:06:00")
        assertThat(copiedNoteTag.component1()).isEqualTo("note-2")
        assertThat(copiedNoteTag.component2()).isEqualTo("tag-2")
        assertThat(copiedNoteTag.component3()).isEqualTo("2026-02-08T10:06:00")
        assertThat(copiedNoteTag.toString()).contains("note-2")
        val timeEntryTag = TimeEntryTagEntity("time-2", "tag-2", "2026-02-08T09:07:00")
        val copiedTimeEntryTag = timeEntryTag.copy(createdAt = "2026-02-08T10:07:00")
        assertThat(copiedTimeEntryTag.component1()).isEqualTo("time-2")
        assertThat(copiedTimeEntryTag.component2()).isEqualTo("tag-2")
        assertThat(copiedTimeEntryTag.component3()).isEqualTo("2026-02-08T10:07:00")
        assertThat(copiedTimeEntryTag.toString()).contains("time-2")
    }

    @Test
    /**
     * Time goal and time rule entities store values and defaults.
     */
    fun timeGoal_and_timeRule_entities_store_values_and_defaults() {
        val goal =
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
        assertThat(goal.id).isEqualTo("goal-1")
        assertThat(goal.dimensionId).isEqualTo("dim_career_work")
        assertThat(goal.targetMinutes).isEqualTo(180)
        assertThat(goal.period).isEqualTo("daily")
        assertThat(goal.isActive).isEqualTo(1)
        assertThat(goal.notes).isEqualTo("Primary focus")
        val minimalGoal =
            TimeGoalEntity(
                id = "goal-2",
                name = "Learning Weekly",
                targetMinutes = 420,
                period = "weekly",
                createdAt = "2026-02-08T12:00:00",
                updatedAt = "2026-02-08T12:00:00",
            )
        assertThat(minimalGoal.dimensionId).isNull()
        assertThat(minimalGoal.notes).isNull()
        assertThat(minimalGoal.isActive).isEqualTo(1)
        val copiedGoal = goal.copy(targetMinutes = 200)
        assertThat(copiedGoal.component1()).isEqualTo("goal-1")
        assertThat(copiedGoal.component4()).isEqualTo(200)
        assertThat(copiedGoal.toString()).contains("goal-1")
        val rule =
            TimeRuleEntity(
                id = "rule-1",
                name = "Max Continuous Block",
                ruleType = "max_continuous_minutes",
                configJson = "{\"minutes\":120}",
                isActive = 1,
                createdAt = "2026-02-08T11:30:00",
                updatedAt = "2026-02-08T11:30:00",
            )
        assertThat(rule.id).isEqualTo("rule-1")
        assertThat(rule.ruleType).isEqualTo("max_continuous_minutes")
        assertThat(rule.configJson).isEqualTo("{\"minutes\":120}")
        assertThat(rule.isActive).isEqualTo(1)
        val copiedRule = rule.copy(isActive = 0)
        assertThat(copiedRule.component1()).isEqualTo("rule-1")
        assertThat(copiedRule.component5()).isEqualTo(0)
        assertThat(copiedRule.toString()).contains("rule-1")
    }

    private fun initLogger(): UnifiedLogger {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        return UnifiedLogger.getInstance()
    }
}
