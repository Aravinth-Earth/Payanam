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
class ModelDefaultsTest {

    private lateinit var logger: UnifiedLogger

    @Before
    fun setup() {
        logger = initLogger()
        logger.d("ModelDefaultsTest.setup", "Logger initialized for tests")
    }

    @Test
    fun task_defaults_areApplied() {
        val now = LocalDateTime.of(2026, 1, 31, 9, 0)
        val task = Task(
            id = "task-1",
            title = "Title",
            createdAt = now,
            updatedAt = now
        )
        assertThat(task.status).isEqualTo("pending")
        assertThat(task.durationMinutes).isEqualTo(10)
        assertThat(task.notificationMode).isEqualTo("auto")
    }

    @Test
    fun task_exposesAllFields() {
        val now = LocalDateTime.of(2026, 1, 31, 9, 0)
        val due = now.plusHours(2)
        val archived = now.plusDays(1)
        val completed = now.plusMinutes(30)
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

        assertThat(task.id).isEqualTo("task-full")
        assertThat(task.title).isEqualTo("Full Task")
        assertThat(task.description).isEqualTo("Details")
        assertThat(task.status).isEqualTo("completed")
        assertThat(task.dueDate).isEqualTo(due)
        assertThat(task.createdAt).isEqualTo(now)
        assertThat(task.updatedAt).isEqualTo(now)
        assertThat(task.completedAt).isEqualTo(completed)
        assertThat(task.archivedAt).isEqualTo(archived)
        assertThat(task.recurrenceEnabled).isTrue()
        assertThat(task.recurrenceRule).isEqualTo("FREQ=WEEKLY")
        assertThat(task.durationMinutes).isEqualTo(25)
        assertThat(task.impactLevel).isEqualTo("High Impact")
        assertThat(task.goalAlignment).isEqualTo("Strong Alignment")
        assertThat(task.energyLevel).isEqualTo("High")
        assertThat(task.controlLevel).isEqualTo("Self")
        assertThat(task.lifeIntentionCategory).isEqualTo("Health & Wellness")
        assertThat(task.explicitUrgency).isEqualTo(0.7)
        assertThat(task.focusRequired).isEqualTo(0.5)
        assertThat(task.recurrenceStrategy).isEqualTo("planned")
        assertThat(task.blockedReason).isEqualTo("WAITING")
        assertThat(task.completionRate).isEqualTo(0.8)
        assertThat(task.externalDependency).isEqualTo("Vendor")
        assertThat(task.notificationMode).isEqualTo("custom")
        assertThat(task.customNotificationMinutes).isEqualTo(10)
        assertThat(task.taskScore).isEqualTo(0.95)
    }

    @Test
    fun note_defaults_areApplied() {
        val now = LocalDateTime.of(2026, 1, 31, 9, 0)
        val note = Note(
            id = "note-1",
            title = "Note",
            lifeIntentionCategory = "Learning",
            createdAt = now,
            updatedAt = now
        )
        assertThat(note.details).isNull()
    }

    @Test
    fun note_exposesFields() {
        val now = LocalDateTime.of(2026, 1, 31, 9, 0)
        val note = Note(
            id = "note-2",
            title = "Idea",
            details = "Details",
            lifeIntentionCategory = "Recreation",
            createdAt = now,
            updatedAt = now.plusMinutes(5)
        )

        assertThat(note.id).isEqualTo("note-2")
        assertThat(note.title).isEqualTo("Idea")
        assertThat(note.details).isEqualTo("Details")
        assertThat(note.lifeIntentionCategory).isEqualTo("Recreation")
        assertThat(note.createdAt).isEqualTo(now)
        assertThat(note.updatedAt).isEqualTo(now.plusMinutes(5))
    }

    @Test
    fun timeEntry_optionalFieldsDefaultToNull() {
        val now = LocalDateTime.of(2026, 1, 31, 9, 0)
        val entry = TimeEntry(
            id = "entry-1",
            lifeIntentionCategory = "Career & Work",
            startedAt = now,
            createdAt = now,
            updatedAt = now
        )
        assertThat(entry.endedAt).isNull()
        assertThat(entry.taskId).isNull()
    }

    @Test
    fun taskOccurrence_defaults_areApplied() {
        val occurrence = TaskOccurrence(
            id = "occ-1",
            taskId = "task-1",
            occurrenceDate = "2026-01-31",
            status = "completed"
        )
        assertThat(occurrence.statusNote).isNull()
        assertThat(occurrence.statusReason).isNull()
    }

    @Test
    fun taskOccurrence_exposesFields() {
        val now = LocalDateTime.of(2026, 1, 31, 9, 0)
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

        assertThat(occurrence.id).isEqualTo("occ-2")
        assertThat(occurrence.taskId).isEqualTo("task-2")
        assertThat(occurrence.occurrenceDate).isEqualTo("2026-02-01")
        assertThat(occurrence.status).isEqualTo("skipped")
        assertThat(occurrence.statusNote).isEqualTo("Busy")
        assertThat(occurrence.statusReason).isEqualTo("NO_TIME")
        assertThat(occurrence.completedAt).isEqualTo("2026-02-01T10:00:00")
        assertThat(occurrence.skippedAt).isEqualTo("2026-02-01T09:30:00")
        assertThat(occurrence.dueDate).isEqualTo(now.plusDays(1))
        assertThat(occurrence.createdAt).isEqualTo(now)
        assertThat(occurrence.completionRate).isEqualTo(0.6)
        assertThat(occurrence.note).isEqualTo("Short note")
    }

    @Test
    fun journal_models_holdValues() {
        val entry = DayJournalEntry(
            id = "entry-1",
            entryDate = "2026-01-31",
            createdAt = "2026-01-31T09:00:00",
            updatedAt = "2026-01-31T09:00:00"
        )
        val response = DayJournalResponse(
            id = "response-1",
            entryId = entry.id,
            scope = "overall",
            dimensionKey = "health",
            promptKey = "gratitude",
            responseText = "Thanks"
        )
        assertThat(response.entryId).isEqualTo(entry.id)
        assertThat(entry.id).isEqualTo("entry-1")
        assertThat(entry.entryDate).isEqualTo("2026-01-31")
        assertThat(entry.createdAt).isEqualTo("2026-01-31T09:00:00")
        assertThat(entry.updatedAt).isEqualTo("2026-01-31T09:00:00")
        assertThat(response.id).isEqualTo("response-1")
        assertThat(response.scope).isEqualTo("overall")
        assertThat(response.dimensionKey).isEqualTo("health")
        assertThat(response.promptKey).isEqualTo("gratitude")
        assertThat(response.responseText).isEqualTo("Thanks")
    }

    @Test
    fun taskReschedule_holdsValues() {
        val now = LocalDateTime.of(2026, 1, 31, 9, 0)
        val reschedule = TaskReschedule(
            id = "res-1",
            taskId = "task-1",
            previousDueDate = now,
            newDueDate = now.plusDays(1),
            rescheduledAt = now,
            wasOverdue = false
        )
        assertThat(reschedule.taskId).isEqualTo("task-1")
        assertThat(reschedule.previousDueDate).isEqualTo(now)
        assertThat(reschedule.newDueDate).isEqualTo(now.plusDays(1))
        assertThat(reschedule.rescheduledAt).isEqualTo(now)
        assertThat(reschedule.wasOverdue).isFalse()
    }

    @Test
    fun tag_model_defaults_copy_and_components_work() {
        val createdAt = LocalDateTime.of(2026, 2, 8, 10, 0)
        val tag = Tag(
            id = "tag-1",
            name = "Deep Work",
            normalizedName = "deep work",
            usageCount = 0,
            createdAt = createdAt,
            updatedAt = createdAt
        )
        assertThat(tag.id).isEqualTo("tag-1")
        assertThat(tag.lastUsedAt).isNull()

        val updated = tag.copy(
            usageCount = 4,
            lastUsedAt = createdAt.plusHours(1),
            updatedAt = createdAt.plusHours(1)
        )
        val id = updated.component1()
        val name = updated.component2()
        val normalized = updated.component3()
        val usageCount = updated.component4()
        val lastUsedAt = updated.component5()
        assertThat(id).isEqualTo("tag-1")
        assertThat(name).isEqualTo("Deep Work")
        assertThat(normalized).isEqualTo("deep work")
        assertThat(usageCount).isEqualTo(4)
        assertThat(lastUsedAt).isEqualTo(createdAt.plusHours(1))
        assertThat(updated.toString()).contains("tag-1")
    }

    private fun initLogger(): UnifiedLogger {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        return UnifiedLogger.getInstance()
    }
}
