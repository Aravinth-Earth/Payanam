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
 * ModelDefaultsTest.
 */
class ModelDefaultsTest {

    private lateinit var logger: UnifiedLogger

    @Before
    /**
     * Setup.
     */
    fun setup() {
        logger = initLogger()
        logger.d("ModelDefaultsTest.setup", "Logger initialized for tests")
    }

    @Test
    /**
     * Task defaults are applied.
     */
    fun task_defaults_areApplied() {
        /** Now. */
        val now = LocalDateTime.of(2026, 1, 31, 9, 0)
        /** Task. */
        val task = Task(
            id = "task-1",
            title = "Title",
            createdAt = now,
            updatedAt = now
        )
        /** Assert that. */
        assertThat(task.status).isEqualTo("pending")
        /** Assert that. */
        assertThat(task.durationMinutes).isEqualTo(10)
        /** Assert that. */
        assertThat(task.notificationMode).isEqualTo("auto")
    }

    @Test
    /**
     * Task exposes all fields.
     */
    fun task_exposesAllFields() {
        /** Now. */
        val now = LocalDateTime.of(2026, 1, 31, 9, 0)
        /** Due. */
        val due = now.plusHours(2)
        /** Archived. */
        val archived = now.plusDays(1)
        /** Completed. */
        val completed = now.plusMinutes(30)
        /** Task. */
        val task = Task(
            id = "task-full",
            title = "Full Task",
            description = "Details",
            status = "completed",
            dueDate = due,
            createdAt = now,
            updatedAt = now,
            completedAt = completed,
            archivedAt = archived,
            recurrenceEnabled = true,
            recurrenceRule = "FREQ=WEEKLY",
            durationMinutes = 25,
            impactLevel = "High Impact",
            goalAlignment = "Strong Alignment",
            energyLevel = "High",
            controlLevel = "Self",
            lifeIntentionCategory = "Health & Wellness",
            explicitUrgency = 0.7,
            focusRequired = 0.5,
            recurrenceStrategy = "planned",
            blockedReason = "WAITING",
            completionRate = 0.8,
            externalDependency = "Vendor",
            notificationMode = "custom",
            customNotificationMinutes = 10,
            taskScore = 0.95
        )

        /** Assert that. */
        assertThat(task.id).isEqualTo("task-full")
        /** Assert that. */
        assertThat(task.title).isEqualTo("Full Task")
        /** Assert that. */
        assertThat(task.description).isEqualTo("Details")
        /** Assert that. */
        assertThat(task.status).isEqualTo("completed")
        /** Assert that. */
        assertThat(task.dueDate).isEqualTo(due)
        /** Assert that. */
        assertThat(task.createdAt).isEqualTo(now)
        /** Assert that. */
        assertThat(task.updatedAt).isEqualTo(now)
        /** Assert that. */
        assertThat(task.completedAt).isEqualTo(completed)
        /** Assert that. */
        assertThat(task.archivedAt).isEqualTo(archived)
        /** Assert that. */
        assertThat(task.recurrenceEnabled).isTrue()
        /** Assert that. */
        assertThat(task.recurrenceRule).isEqualTo("FREQ=WEEKLY")
        /** Assert that. */
        assertThat(task.durationMinutes).isEqualTo(25)
        /** Assert that. */
        assertThat(task.impactLevel).isEqualTo("High Impact")
        /** Assert that. */
        assertThat(task.goalAlignment).isEqualTo("Strong Alignment")
        /** Assert that. */
        assertThat(task.energyLevel).isEqualTo("High")
        /** Assert that. */
        assertThat(task.controlLevel).isEqualTo("Self")
        /** Assert that. */
        assertThat(task.lifeIntentionCategory).isEqualTo("Health & Wellness")
        /** Assert that. */
        assertThat(task.explicitUrgency).isEqualTo(0.7)
        /** Assert that. */
        assertThat(task.focusRequired).isEqualTo(0.5)
        /** Assert that. */
        assertThat(task.recurrenceStrategy).isEqualTo("planned")
        /** Assert that. */
        assertThat(task.blockedReason).isEqualTo("WAITING")
        /** Assert that. */
        assertThat(task.completionRate).isEqualTo(0.8)
        /** Assert that. */
        assertThat(task.externalDependency).isEqualTo("Vendor")
        /** Assert that. */
        assertThat(task.notificationMode).isEqualTo("custom")
        /** Assert that. */
        assertThat(task.customNotificationMinutes).isEqualTo(10)
        /** Assert that. */
        assertThat(task.taskScore).isEqualTo(0.95)
    }

    @Test
    /**
     * Note defaults are applied.
     */
    fun note_defaults_areApplied() {
        /** Now. */
        val now = LocalDateTime.of(2026, 1, 31, 9, 0)
        /** Note. */
        val note = Note(
            id = "note-1",
            title = "Note",
            lifeIntentionCategory = "Learning",
            createdAt = now,
            updatedAt = now
        )
        /** Assert that. */
        assertThat(note.details).isNull()
    }

    @Test
    /**
     * Note exposes fields.
     */
    fun note_exposesFields() {
        /** Now. */
        val now = LocalDateTime.of(2026, 1, 31, 9, 0)
        /** Note. */
        val note = Note(
            id = "note-2",
            title = "Idea",
            details = "Details",
            lifeIntentionCategory = "Recreation",
            createdAt = now,
            updatedAt = now.plusMinutes(5)
        )

        /** Assert that. */
        assertThat(note.id).isEqualTo("note-2")
        /** Assert that. */
        assertThat(note.title).isEqualTo("Idea")
        /** Assert that. */
        assertThat(note.details).isEqualTo("Details")
        /** Assert that. */
        assertThat(note.lifeIntentionCategory).isEqualTo("Recreation")
        /** Assert that. */
        assertThat(note.createdAt).isEqualTo(now)
        /** Assert that. */
        assertThat(note.updatedAt).isEqualTo(now.plusMinutes(5))
    }

    @Test
    /**
     * Time entry optional fields default to null.
     */
    fun timeEntry_optionalFieldsDefaultToNull() {
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
        assertThat(entry.endedAt).isNull()
        /** Assert that. */
        assertThat(entry.taskId).isNull()
    }

    @Test
    /**
     * Task occurrence defaults are applied.
     */
    fun taskOccurrence_defaults_areApplied() {
        /** Occurrence. */
        val occurrence = TaskOccurrence(
            id = "occ-1",
            taskId = "task-1",
            occurrenceDate = "2026-01-31",
            status = "completed"
        )
        /** Assert that. */
        assertThat(occurrence.statusNote).isNull()
        /** Assert that. */
        assertThat(occurrence.statusReason).isNull()
    }

    @Test
    /**
     * Task occurrence exposes fields.
     */
    fun taskOccurrence_exposesFields() {
        /** Now. */
        val now = LocalDateTime.of(2026, 1, 31, 9, 0)
        /** Occurrence. */
        val occurrence = TaskOccurrence(
            id = "occ-2",
            taskId = "task-2",
            occurrenceDate = "2026-02-01",
            status = "skipped",
            statusNote = "Busy",
            statusReason = "NO_TIME",
            completedAt = "2026-02-01T10:00:00",
            skippedAt = "2026-02-01T09:30:00",
            dueDate = now.plusDays(1),
            createdAt = now,
            completionRate = 0.6,
            note = "Short note"
        )

        /** Assert that. */
        assertThat(occurrence.id).isEqualTo("occ-2")
        /** Assert that. */
        assertThat(occurrence.taskId).isEqualTo("task-2")
        /** Assert that. */
        assertThat(occurrence.occurrenceDate).isEqualTo("2026-02-01")
        /** Assert that. */
        assertThat(occurrence.status).isEqualTo("skipped")
        /** Assert that. */
        assertThat(occurrence.statusNote).isEqualTo("Busy")
        /** Assert that. */
        assertThat(occurrence.statusReason).isEqualTo("NO_TIME")
        /** Assert that. */
        assertThat(occurrence.completedAt).isEqualTo("2026-02-01T10:00:00")
        /** Assert that. */
        assertThat(occurrence.skippedAt).isEqualTo("2026-02-01T09:30:00")
        /** Assert that. */
        assertThat(occurrence.dueDate).isEqualTo(now.plusDays(1))
        /** Assert that. */
        assertThat(occurrence.createdAt).isEqualTo(now)
        /** Assert that. */
        assertThat(occurrence.completionRate).isEqualTo(0.6)
        /** Assert that. */
        assertThat(occurrence.note).isEqualTo("Short note")
    }

    @Test
    /**
     * Journal models hold values.
     */
    fun journal_models_holdValues() {
        /** Entry. */
        val entry = DayJournalEntry(
            id = "entry-1",
            entryDate = "2026-01-31",
            createdAt = "2026-01-31T09:00:00",
            updatedAt = "2026-01-31T09:00:00"
        )
        /** Response. */
        val response = DayJournalResponse(
            id = "response-1",
            entryId = entry.id,
            scope = "overall",
            dimensionKey = "health",
            promptKey = "gratitude",
            responseText = "Thanks"
        )
        /** Assert that. */
        assertThat(response.entryId).isEqualTo(entry.id)
        /** Assert that. */
        assertThat(entry.id).isEqualTo("entry-1")
        /** Assert that. */
        assertThat(entry.entryDate).isEqualTo("2026-01-31")
        /** Assert that. */
        assertThat(entry.createdAt).isEqualTo("2026-01-31T09:00:00")
        /** Assert that. */
        assertThat(entry.updatedAt).isEqualTo("2026-01-31T09:00:00")
        /** Assert that. */
        assertThat(response.id).isEqualTo("response-1")
        /** Assert that. */
        assertThat(response.scope).isEqualTo("overall")
        /** Assert that. */
        assertThat(response.dimensionKey).isEqualTo("health")
        /** Assert that. */
        assertThat(response.promptKey).isEqualTo("gratitude")
        /** Assert that. */
        assertThat(response.responseText).isEqualTo("Thanks")
    }

    @Test
    /**
     * Task reschedule holds values.
     */
    fun taskReschedule_holdsValues() {
        /** Now. */
        val now = LocalDateTime.of(2026, 1, 31, 9, 0)
        /** Reschedule. */
        val reschedule = TaskReschedule(
            id = "res-1",
            taskId = "task-1",
            previousDueDate = now,
            newDueDate = now.plusDays(1),
            rescheduledAt = now,
            wasOverdue = false
        )
        /** Assert that. */
        assertThat(reschedule.taskId).isEqualTo("task-1")
        /** Assert that. */
        assertThat(reschedule.previousDueDate).isEqualTo(now)
        /** Assert that. */
        assertThat(reschedule.newDueDate).isEqualTo(now.plusDays(1))
        /** Assert that. */
        assertThat(reschedule.rescheduledAt).isEqualTo(now)
        /** Assert that. */
        assertThat(reschedule.wasOverdue).isFalse()
    }

    @Test
    /**
     * Tag model defaults copy and components work.
     */
    fun tag_model_defaults_copy_and_components_work() {
        /** Created at. */
        val createdAt = LocalDateTime.of(2026, 2, 8, 10, 0)
        /** Tag. */
        val tag = Tag(
            id = "tag-1",
            name = "Deep Work",
            normalizedName = "deep work",
            usageCount = 0,
            createdAt = createdAt,
            updatedAt = createdAt
        )
        /** Assert that. */
        assertThat(tag.id).isEqualTo("tag-1")
        /** Assert that. */
        assertThat(tag.lastUsedAt).isNull()

        /** Updated. */
        val updated = tag.copy(
            usageCount = 4,
            lastUsedAt = createdAt.plusHours(1),
            updatedAt = createdAt.plusHours(1)
        )
        /** Id. */
        val id = updated.component1()
        /** Name. */
        val name = updated.component2()
        /** Normalized. */
        val normalized = updated.component3()
        /** Usage count. */
        val usageCount = updated.component4()
        /** Last used at. */
        val lastUsedAt = updated.component5()
        /** Assert that. */
        assertThat(id).isEqualTo("tag-1")
        /** Assert that. */
        assertThat(name).isEqualTo("Deep Work")
        /** Assert that. */
        assertThat(normalized).isEqualTo("deep work")
        /** Assert that. */
        assertThat(usageCount).isEqualTo(4)
        /** Assert that. */
        assertThat(lastUsedAt).isEqualTo(createdAt.plusHours(1))
        /** Assert that. */
        assertThat(updated.toString()).contains("tag-1")
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
