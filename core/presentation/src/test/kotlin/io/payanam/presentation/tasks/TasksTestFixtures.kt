//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.tasks

import io.payanam.domain.model.HabitL1Summary
import io.payanam.domain.model.Tag
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskInput
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TaskReschedule
import io.payanam.domain.port.HabitTimeEntryRecorder
import io.payanam.domain.port.PerformanceTracer
import io.payanam.domain.port.ScoreCalculator
import io.payanam.domain.port.ScoreChangeStream
import io.payanam.domain.port.ScoreRollupTrigger
import io.payanam.domain.port.TaskReminderScheduler
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.HabitMetricRepository
import io.payanam.domain.repository.ScoreWindowRepository
import io.payanam.domain.repository.TagRepository
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TaskRescheduleRepository
import io.payanam.scoring.CompletionStats
import io.payanam.scoring.port.TaskRecurrenceHandler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime

// The fakes and builders the Tasks-family ViewModel tests share.
//
// They are hand-written rather than Mockito-generated because this module has neither Mockito nor
// Robolectric on its test classpath - it is a plain JVM module, and that is what keeps these tests running
// without a device. Every fake keeps its state in a MutableStateFlow, so a test can drive a change and the
// ViewModel observes it the way production does.

/**
 * The day every fixture uses, anchored to TODAY rather than to a literal calendar date.
 *
 * The code under test reads the wall clock: `todayCount`, the TODAY/FUTURE filters and the due-today map
 * all ask what day it is. A literal date therefore does the opposite of what this fixture was written for -
 * it makes the fixture expire. This was `LocalDate.of(2026, 9, 21)`, which ran green all day on 2026-09-21
 * and then failed five tests on 2026-09-22 at once: `today1` was suddenly due "yesterday", so the TODAY
 * filter returned tomorrow's task instead, and undated-but-today defaults were filtered out entirely.
 * Taking the real day keeps the suite independent of which day it runs on.
 *
 * A SECOND expiry, found on 2026-09-26 at 01:18, and this one is about the HOUR rather than the day. The
 * OVERDUE filter is the only branch that compares against an instant rather than a day
 * (`TasksViewModelSorting`: `task.dueDate?.isBefore(now)` with `now = LocalDateTime.now()`), so a task due
 * today at 09:00 is not overdue at 01:18 - correctly, but the fixture asserted it was. A 09:00 anchor is only
 * safe between 09:00 and midnight, so the suite went red on every overnight run.
 */
internal val TASKS_TEST_DAY: LocalDate = LocalDate.now()

/**
 * The instant every fixture uses - today at 00:00, the first minute of the anchored day.
 *
 * Midnight, not 09:00: `00:00` is the only time on the anchored day that is unambiguously "already reached",
 * so a task due at it counts as overdue at every hour the suite can run at. The earlier 09:00 anchor looked
 * safe because it never rolls the DAY over, which is not the comparison the OVERDUE filter makes.
 */
internal val TASKS_TEST_TIME: LocalDateTime = TASKS_TEST_DAY.atStartOfDay()

/** A task with defaults that read as "an ordinary pending task due today". */
internal fun task(
    id: String = "task",
    title: String = id,
    status: String = "pending",
    dueDate: LocalDateTime? = TASKS_TEST_TIME,
    recurrenceEnabled: Boolean = false,
    recurrenceRule: String? = null,
    dimensionId: String? = null,
    impactLevel: String = "Moderate Impact",
    taskScore: Double? = null,
    description: String? = null,
    durationMinutes: Int = 10,
    lifeIntentionCategory: String = "Career & Work",
): Task =
    Task(
        id = id,
        title = title,
        description = description,
        status = status,
        dueDate = dueDate,
        createdAt = TASKS_TEST_TIME.minusDays(1),
        updatedAt = TASKS_TEST_TIME.minusDays(1),
        recurrenceEnabled = recurrenceEnabled,
        recurrenceRule = recurrenceRule,
        durationMinutes = durationMinutes,
        impactLevel = impactLevel,
        lifeIntentionCategory = lifeIntentionCategory,
        taskScore = taskScore,
        dimensionId = dimensionId,
    )

/** A habit task: recurrence on, with the frequency rule the tests exercise. */
internal fun habit(
    id: String,
    rule: String = "1/1",
    dimensionId: String? = null,
    dueDate: LocalDateTime? = TASKS_TEST_TIME,
    impactLevel: String = "Moderate Impact",
): Task =
    task(
        id = id,
        title = id,
        dueDate = dueDate,
        recurrenceEnabled = true,
        recurrenceRule = rule,
        dimensionId = dimensionId,
        impactLevel = impactLevel,
    )

/** An occurrence row for [taskId] on [date], completed unless told otherwise. */
internal fun occurrence(
    taskId: String,
    date: LocalDate = TASKS_TEST_DAY,
    status: String = "completed",
    statusReason: String? = null,
): TaskOccurrence =
    TaskOccurrence(
        id = "$taskId-$date",
        taskId = taskId,
        occurrenceDate = "${date}T00:00:00",
        status = status,
        statusReason = statusReason,
        completedAt = if (status == "completed") "${date}T09:00:00" else null,
        dueDate = date.atStartOfDay(),
    )

/** An L1 metric row for [habitId] on [dayKey]. */
internal fun habitL1(
    habitId: String,
    dayKey: String = TASKS_TEST_DAY.toString(),
    score: Double = 0.5,
    runningAvg: Double = 0.5,
    progress: Double = 1.0,
): HabitL1Summary =
    HabitL1Summary(
        habitId = habitId,
        dayKey = dayKey,
        score = score,
        runningAvg = runningAvg,
        progress = progress,
        streakPos = 1,
        streakNet = 1,
        posContinue = 1,
    )

/** The task repository the ViewModel reads and writes through. */
internal class FakeTaskRepository : TaskRepository {
    val tasks = MutableStateFlow<List<Task>>(emptyList())

    /**
     * Failure switches, each defaulting to off so an existing test's behaviour is unchanged.
     *
     * A test that cannot make a write FAIL cannot reach the caller's error path, and an error path that
     * no test reaches is the one most likely to be wrong.
     */
    var failGetAllTasks = false
    var failTodaysTasks = false
    var failTasksDueOn = false
    var failGetById = false
    var failUpdate = false
    var failComplete = false
    var failSkip = false
    var failMiss = false
    var failArchive = false
    var failDelete = false

    /** When set, every read-back of this task reports the given recurrence flag instead of its own. */
    var recurrenceOnRead: Boolean? = null

    /** When true, the second and later `getTaskById` calls return null - as a deleted row would. */
    var dropOnSecondRead = false
    private var readCount = 0

    override fun getAllTasks(): Flow<List<Task>> =
        if (failGetAllTasks) {
            flow { throw IllegalStateException("tasks unavailable") }
        } else {
            tasks.map { list -> list.sortedBy { it.title } }
        }

    override fun getTasksByStatus(status: String): Flow<List<Task>> = tasks.map { list -> list.filter { it.status == status } }

    override fun getTasksDueOn(date: LocalDate): Flow<List<Task>> =
        if (failTasksDueOn) {
            flow { throw IllegalStateException("due unavailable") }
        } else {
            tasks.map { list -> list.filter { it.dueDate?.toLocalDate() == date } }
        }

    override suspend fun getTaskById(id: String): Task? {
        if (failGetById) throw IllegalStateException("task unavailable")
        readCount++
        if (dropOnSecondRead && readCount > 1) return null
        val found = tasks.value.firstOrNull { it.id == id } ?: return null
        val recurrence = recurrenceOnRead ?: return found
        return found.copy(recurrenceEnabled = recurrence)
    }

    /** Every input a test asserts on is carried into the stored row - a lossy fake fails the wrong test. */
    override suspend fun createTask(input: TaskInput): Task {
        val stored = applyTo(task(id = "created-${tasks.value.size + 1}", title = input.title), input)
        tasks.value = tasks.value + stored
        return stored
    }

    override suspend fun updateTask(
        id: String,
        input: TaskInput,
    ): Task {
        if (failUpdate) throw IllegalStateException("update failed")
        val updated = applyTo(requireNotNull(tasks.value.firstOrNull { it.id == id }), input)
        tasks.value = tasks.value.map { if (it.id == id) updated else it }
        return updated
    }

    override suspend fun updateTaskScore(
        id: String,
        score: Double,
    ) {
        tasks.value = tasks.value.map { if (it.id == id) it.copy(taskScore = score) else it }
    }

    override suspend fun deleteTask(id: String) {
        if (failDelete) throw IllegalStateException("delete failed")
        tasks.value = tasks.value.filterNot { it.id == id }
    }

    override suspend fun completeTask(
        id: String,
        note: String?,
    ): Task = failOr("complete failed") { setStatus(id, "completed") }

    override suspend fun skipTask(
        id: String,
        note: String?,
    ): Task = failOr("skip failed") { setStatus(id, "skipped") }

    override suspend fun missTask(
        id: String,
        note: String?,
    ): Task = failOr("miss failed") { setStatus(id, "missed") }

    override suspend fun archiveTask(id: String): Task = failOr("archive failed") { setStatus(id, "archived") }

    /** Throws [message] when the matching switch is on, so a test drives the caller's error path. */
    private inline fun failOr(
        message: String,
        block: () -> Task,
    ): Task {
        if (message.startsWith("complete") && failComplete) throw IllegalStateException(message)
        if (message.startsWith("skip") && failSkip) throw IllegalStateException(message)
        if (message.startsWith("miss") && failMiss) throw IllegalStateException(message)
        if (message.startsWith("archive") && failArchive) throw IllegalStateException(message)
        return block()
    }

    override fun getOverdueTasks(): Flow<List<Task>> =
        tasks.map { list ->
            list.filter {
                it.status == "pending" &&
                    it.dueDate?.isBefore(TASKS_TEST_TIME) == true
            }
        }

    override fun getTodaysTasks(): Flow<List<Task>> =
        if (failTodaysTasks) {
            flow { throw IllegalStateException("planned unavailable") }
        } else {
            tasks.map { list -> list.filter { it.dueDate?.toLocalDate() == TASKS_TEST_DAY } }
        }

    override suspend fun getRecurringTasks(): List<Task> = tasks.value.filter { it.recurrenceEnabled }

    override suspend fun updateRecurrenceState(
        taskId: String,
        newDueDate: LocalDateTime,
        lastOccurrenceDate: LocalDateTime,
    ) {
        tasks.value =
            tasks.value.map { if (it.id == taskId) it.copy(dueDate = newDueDate, lastOccurrenceDate = lastOccurrenceDate) else it }
    }

    private fun applyTo(
        row: Task,
        input: TaskInput,
    ): Task =
        row.copy(
            title = input.title,
            description = input.description,
            status = input.status ?: row.status,
            dueDate = input.dueDate,
            archivedAt = input.archivedAt,
            recurrenceEnabled = input.recurrenceEnabled ?: row.recurrenceEnabled,
            recurrenceRule = input.recurrenceRule,
            durationMinutes = input.durationMinutes ?: row.durationMinutes,
            impactLevel = input.impactLevel ?: row.impactLevel,
            goalAlignment = input.goalAlignment ?: row.goalAlignment,
            energyLevel = input.energyLevel ?: row.energyLevel,
            controlLevel = input.controlLevel ?: row.controlLevel,
            lifeIntentionCategory = input.lifeIntentionCategory ?: row.lifeIntentionCategory,
            explicitUrgency = input.explicitUrgency,
            focusRequired = input.focusRequired,
            recurrenceStrategy = input.recurrenceStrategy,
            blockedReason = input.blockedReason,
            completionRate = input.completionRate,
            externalDependency = input.externalDependency,
            notificationMode = input.notificationMode,
            customNotificationMinutes = input.customNotificationMinutes,
            dimensionId = input.dimensionId,
        )

    private fun setStatus(
        id: String,
        status: String,
    ): Task {
        val updated =
            requireNotNull(tasks.value.firstOrNull { it.id == id }).copy(
                status = status,
                completedAt =
                    if (status ==
                        "completed"
                    ) {
                        TASKS_TEST_TIME
                    } else {
                        null
                    },
            )
        tasks.value = tasks.value.map { if (it.id == id) updated else it }
        return updated
    }
}

/** The occurrence repository, driven by an in-memory list. */
internal class FakeTaskOccurrenceRepository : TaskOccurrenceRepository {
    val occurrences = MutableStateFlow<List<TaskOccurrence>>(emptyList())

    /** When on, a date read waits for [releaseOccurrences] instead of emitting - as a slow store would. */
    var holdOccurrences = false
    private val released = MutableStateFlow(false)

    /** Every date this fake was asked about, so a test can assert a store was NEVER read. */
    val dateQueries = mutableListOf<LocalDate>()

    /** When on, a date read fails instead of emitting. */
    var failForDate = false

    /** Lets a held read emit. */
    fun releaseOccurrences() {
        released.value = true
    }

    override suspend fun getOccurrencesByTaskId(taskId: String): List<TaskOccurrence> = occurrences.value.filter { it.taskId == taskId }

    override fun getOccurrencesForTask(taskId: String): Flow<List<TaskOccurrence>> =
        occurrences.map { list ->
            list.filter {
                it.taskId ==
                    taskId
            }
        }

    override suspend fun getOccurrencesForLastNDays(
        taskId: String,
        days: Int,
    ): List<TaskOccurrence> =
        occurrences.value.filter {
            it.taskId ==
                taskId
        }

    override suspend fun getOccurrencesForTasksInLastNDays(
        taskIds: List<String>,
        days: Int,
    ): Map<String, List<TaskOccurrence>> =
        occurrences.value
            .filter {
                it.taskId in
                    taskIds
            }.groupBy { it.taskId }

    override suspend fun getOccurrenceForDate(
        taskId: String,
        date: LocalDate,
    ): TaskOccurrence? =
        occurrences.value.firstOrNull {
            it.taskId ==
                taskId &&
                it.occurrenceDate.startsWith(date.toString())
        }

    override suspend fun toggleOccurrence(
        taskId: String,
        date: LocalDate,
        newStatus: String,
        note: String?,
        reason: String?,
        actualCompletedAt: LocalDateTime?,
        actualDurationMinutes: Int?,
    ): TaskOccurrence {
        val row = occurrence(taskId = taskId, date = date, status = newStatus, statusReason = reason)
        occurrences.value = occurrences.value.filterNot { it.taskId == taskId && it.occurrenceDate.startsWith(date.toString()) } + row
        return row
    }

    override fun getOccurrencesForDate(date: LocalDate): Flow<List<TaskOccurrence>> {
        dateQueries += date
        if (failForDate) return flow { throw IllegalStateException("occurrences unavailable") }
        if (holdOccurrences && !released.value) {
            // Gate on the release flag rather than emitting an empty list: a section that emits at all
            // has ARRIVED, and an empty emission would mark the date ready - the opposite of what
            // "held" means here.
            return released.dropWhile { !it }.flatMapConcat {
                occurrences.map { list -> list.filter { it.occurrenceDate.startsWith(date.toString()) } }
            }
        }
        return occurrences.map { list -> list.filter { it.occurrenceDate.startsWith(date.toString()) } }
    }

    override suspend fun recordOccurrence(occurrence: TaskOccurrence) {
        occurrences.value = occurrences.value.filterNot { it.id == occurrence.id } + occurrence
    }

    override suspend fun recordOccurrence(
        taskId: String,
        dueDate: LocalDateTime,
        status: String,
        note: String?,
        completionRate: Double?,
    ): TaskOccurrence {
        val row = occurrence(taskId = taskId, date = dueDate.toLocalDate(), status = status, statusReason = note)
        recordOccurrence(row)
        return row
    }

    override suspend fun deleteOccurrence(
        taskId: String,
        date: LocalDate,
    ) {
        occurrences.value = occurrences.value.filterNot { it.taskId == taskId && it.occurrenceDate.startsWith(date.toString()) }
    }

    override suspend fun deleteOccurrence(id: String) {
        occurrences.value = occurrences.value.filterNot { it.id == id }
    }
}

/** The reschedule repository. */
internal class FakeTaskRescheduleRepository : TaskRescheduleRepository {
    val reschedules = MutableStateFlow<List<TaskReschedule>>(emptyList())

    override suspend fun getReschedulesByTaskId(taskId: String): List<TaskReschedule> = reschedules.value.filter { it.taskId == taskId }

    override fun getReschedulesForTask(taskId: String): Flow<List<TaskReschedule>> =
        reschedules.map { list ->
            list.filter {
                it.taskId ==
                    taskId
            }
        }

    override suspend fun recordReschedule(reschedule: TaskReschedule) {
        reschedules.value = reschedules.value + reschedule
    }

    override suspend fun recordReschedule(
        taskId: String,
        previousDueDate: LocalDateTime,
        newDueDate: LocalDateTime,
        wasOverdue: Boolean,
    ): TaskReschedule {
        val row =
            TaskReschedule(
                id = "r-${reschedules.value.size + 1}",
                taskId = taskId,
                previousDueDate = previousDueDate,
                newDueDate = newDueDate,
                rescheduledAt = TASKS_TEST_TIME,
                wasOverdue = wasOverdue,
            )
        recordReschedule(row)
        return row
    }
}

/** The habit-metric repository. */
internal class FakeHabitMetricRepository : HabitMetricRepository {
    var latestPerHabit: Map<String, HabitL1Summary> = emptyMap()
    var rangeRows: List<HabitL1Summary> = emptyList()

    override suspend fun getLatestPerHabit(): Map<String, HabitL1Summary> = latestPerHabit

    override suspend fun getLatestForHabit(habitId: String): HabitL1Summary? = latestPerHabit[habitId]

    override suspend fun getForHabitRange(
        habitId: String,
        start: String,
        end: String,
    ): List<HabitL1Summary> =
        rangeRows.filter {
            it.habitId ==
                habitId
        }
}

/** The score-window repository. */
internal class FakeScoreWindowRepository : ScoreWindowRepository {
    var dimensionRows: List<io.payanam.domain.model.MetricWindowRow> = emptyList()
    var dayRows: List<io.payanam.domain.model.MetricWindowRow> = emptyList()
    var earliestDay: String? = null
    var earliestDimensionDay: String? = null

    override suspend fun getDimensionWindow(
        start: String,
        end: String,
    ): List<io.payanam.domain.model.MetricWindowRow> = dimensionRows

    override suspend fun getDayWindow(
        start: String,
        end: String,
    ): List<io.payanam.domain.model.MetricWindowRow> = dayRows

    override suspend fun earliestDayKey(): String? = earliestDay

    override suspend fun earliestDimensionDayKey(dimensionId: String): String? = earliestDimensionDay

    override suspend fun earliestDimensionDayKey(): String? = earliestDimensionDay
}

/** The app-settings repository, as a map a test can pre-seed. */
internal class FakeAppSettingsRepository(
    initial: Map<String, String> = emptyMap(),
) : AppSettingsRepository {
    private val values = MutableStateFlow<Map<String, String?>>(initial)
    val writes = mutableListOf<Pair<String, String?>>()

    override suspend fun getSetting(key: String): String? = values.value[key]

    override fun observeSetting(key: String): Flow<String?> = values.map { it[key] }

    override suspend fun setSetting(
        key: String,
        value: String?,
    ) {
        writes += key to value
        values.value = values.value + (key to value)
    }

    /** Emits a new value for [key] without recording a write, so a test can drive an observer. */
    fun emit(
        key: String,
        value: String?,
    ) {
        values.value = values.value + (key to value)
    }

    override suspend fun deleteSetting(key: String) {
        values.value = values.value - key
    }

    override fun getAllSettings(): Flow<Map<String, String?>> = values.asStateFlow()
}

/** A tag with sensible defaults, for the tag paths a task form reads and writes. */
internal fun tag(
    id: String,
    name: String = id,
    usageCount: Int = 1,
): Tag =
    Tag(
        id = id,
        name = name,
        normalizedName = name.lowercase(),
        usageCount = usageCount,
        lastUsedAt = TASKS_TEST_TIME,
        createdAt = TASKS_TEST_TIME,
        updatedAt = TASKS_TEST_TIME,
    )

/** The tag repository. */
internal class FakeTagRepository : TagRepository {
    val tags = MutableStateFlow<List<Tag>>(emptyList())

    /** Tags per task, so `observeTagsForTask` reflects what `replaceTaskTags` wrote. */
    val tagsByTask = MutableStateFlow<Map<String, List<Tag>>>(emptyMap())

    override fun observeAllTags(): Flow<List<Tag>> = tags

    override fun searchTagsByPrefix(
        query: String,
        limit: Int,
    ): Flow<List<Tag>> =
        tags.map { list ->
            list
                .filter {
                    it.name.startsWith(query, ignoreCase = true)
                }.take(limit)
        }

    override fun observeTagsForTask(taskId: String): Flow<List<Tag>> = tagsByTask.map { tags -> tags[taskId].orEmpty() }

    override fun observeTagsForNote(noteId: String): Flow<List<Tag>> = flowOf(emptyList())

    override suspend fun getTagNamesForNotes(noteIds: List<String>): Map<String, List<String>> = emptyMap()

    override fun observeTagsForTimeEntry(timeEntryId: String): Flow<List<Tag>> = flowOf(emptyList())

    override suspend fun replaceTaskTags(
        taskId: String,
        tagNames: List<String>,
    ) {
        val existing = tagsByTask.value[taskId].orEmpty().associateBy { it.name }
        val replacement = tagNames.map { name -> existing[name] ?: tag(id = "tag-$name", name = name) }
        tagsByTask.value = tagsByTask.value + (taskId to replacement)
    }

    override suspend fun replaceNoteTags(
        noteId: String,
        tagNames: List<String>,
    ) = Unit

    override suspend fun replaceTimeEntryTags(
        timeEntryId: String,
        tagNames: List<String>,
    ) = Unit
}

/** The reminder port: records what was asked of it and schedules nothing. */
internal class FakeReminderScheduler : TaskReminderScheduler {
    val scheduled = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    var lastSnoozed: Boolean? = null

    /** Failure switches, default off: a reminder that cannot fire is reachable only by asking for one. */
    var failSchedule = false
    var failCancel = false

    override suspend fun scheduleForTask(
        task: Task,
        overrideScheduledAt: LocalDateTime?,
        overrideDueAt: LocalDateTime?,
        isSnoozed: Boolean,
    ) {
        if (failSchedule) throw IllegalStateException("schedule failed")
        scheduled += task.id
        lastSnoozed = isSnoozed
    }

    override suspend fun cancelForTask(taskId: String) {
        if (failCancel) throw IllegalStateException("cancel failed")
        cancelled += taskId
    }
}

/** The score-roll-up port: records the dates it was asked to recompute. */
internal class FakeScoreRollupTrigger : ScoreRollupTrigger {
    val statusChanges = mutableListOf<Pair<String, LocalDate>>()
    val ruleChanges = mutableListOf<String>()
    val dayOnly = mutableListOf<LocalDate>()

    override suspend fun recalcForStatusChange(
        taskId: String,
        date: LocalDate,
    ) {
        statusChanges += taskId to date
    }

    override suspend fun recalcForRuleChange(taskId: String) {
        ruleChanges += taskId
    }

    override suspend fun recalcDayOnly(changeDate: LocalDate) {
        dayOnly += changeDate
    }
}

/** The recurrence port: records the advances and answers the two predicates a test can set. */
internal class FakeRecurrenceHandler : TaskRecurrenceHandler {
    var frequencyHabit: Boolean = false
    var completionStats: CompletionStats = CompletionStats(0.0, 0.0, 0.0, 0.0, 0, 0)
    val completed = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    val missed = mutableListOf<String>()

    override fun isFrequencyHabit(task: Task): Boolean = frequencyHabit

    override suspend fun onTaskCompleted(
        task: Task,
        note: String?,
        reason: String?,
        nextDueStrategy: String?,
    ) {
        completed += task.id
    }

    override suspend fun onTaskSkipped(
        task: Task,
        note: String?,
        reason: String?,
        nextDueStrategy: String?,
    ) {
        skipped += task.id
    }

    override suspend fun onTaskMissed(
        task: Task,
        note: String?,
        reason: String?,
        nextDueStrategy: String?,
    ) {
        missed += task.id
    }

    override suspend fun getCompletionStats(task: Task): CompletionStats = completionStats
}

/** The habit time-entry port: records the entries a completed habit produced. */
internal class FakeHabitTimeEntryRecorder : HabitTimeEntryRecorder {
    val recorded = mutableListOf<String>()

    override suspend fun invoke(
        task: Task,
        actualCompletedAt: LocalDateTime?,
        actualDurationMinutes: Int?,
    ) {
        recorded += task.id
    }
}

/** The score calculator: a fixed answer, so a test controls the number the ViewModel shows. */
internal class FakeScoreCalculator(
    var score: Double = 7.5,
) : ScoreCalculator {
    override fun calculateScore(task: Task): Double = score
}

/** The performance tracer: counts what was reported, so "the ViewModel traced" is assertable. */
internal class FakePerformanceTracer : PerformanceTracer {
    val events = mutableListOf<String>()
    val onceKeys = mutableListOf<String>()
    var queries = 0
    var recompositions = 0

    override fun markEvent(
        screen: String,
        event: String,
        data: Map<String, Any?>,
    ) {
        events += "$screen/$event"
    }

    override fun markEventOnce(
        key: String,
        screen: String,
        event: String,
        data: Map<String, Any?>,
    ) {
        onceKeys += key
    }

    override fun incrementQuery(
        screen: String,
        source: String,
        amount: Int,
    ): Int = ++queries

    override fun incrementRecomposition(
        screen: String,
        section: String,
    ): Int = ++recompositions
}

/** The score-change stream: a real hot flow a test can emit into. */
internal class FakeScoreChangeStream : ScoreChangeStream {
    private val flow = MutableSharedFlow<LocalDate>(extraBufferCapacity = 8)

    override val events: Flow<LocalDate> = flow.asSharedFlow()

    suspend fun emit(date: LocalDate) = flow.emit(date)
}
