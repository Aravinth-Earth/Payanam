//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.scoring

import io.payanam.shared.logging.AppLogger
import io.payanam.domain.model.Frequency
import io.payanam.domain.model.RecurrenceConfig
import io.payanam.domain.model.RecurrenceType
import java.time.format.DateTimeParseException
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.scoring.CompletionStats
import io.payanam.scoring.FrequencyWindowSummary
import io.payanam.scoring.RecurrenceScoreCalculator
import io.payanam.scoring.port.TaskRecurrenceHandler
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.max

/**
 * Manages recurring task lifecycle including:
 * - Auto-advancing past-due tasks
 * - Auto-generating missed occurrences for legacy recurring tasks
 * - Calculating decay scores
 * - Day boundary handling
 */
class RecurrenceManager(
    private val taskRepository: TaskRepository,
    private val taskOccurrenceRepository: TaskOccurrenceRepository,
) : TaskRecurrenceHandler {
    private val logger get() = AppLogger.current

    /** The frequency-window half of this class; see [FrequencyHabitSync] for why it is separate. */
    private val frequencyHabitSync = FrequencyHabitSync(taskRepository, taskOccurrenceRepository)
    /**
     * Auto-advance all recurring tasks that are past due.
     * Called on app launch and boot recovery.
     *
     * @param globalDayBoundaryHour Default day boundary hour (0-5)
     * @return Number of tasks processed
     */
    suspend fun autoAdvanceRecurringTasks(globalDayBoundaryHour: Int = 0): Int {
        logger.i(
            "RecurrenceManager.autoAdvanceRecurringTasks",
            "Starting auto-advance",
            mapOf(
                "globalDayBoundaryHour" to globalDayBoundaryHour,
            ),
        )
        val effectiveToday = getEffectiveToday(globalDayBoundaryHour)
        val tasks = taskRepository.getRecurringTasks()
        var processedCount = 0
        var skippedNoDate = 0
        var skippedNotPending = 0
        var skippedNotOverdue = 0
        for (task in tasks) {
            if (isFrequencyHabit(task)) {
                frequencyHabitSync.sync(task)
                processedCount++
                continue
            }
            logger.d(
                "RecurrenceManager.autoAdvanceRecurringTasks",
                "Processing task",
                mapOf(
                    "taskId" to task.id,
                    "title" to task.title,
                    "dueDate" to (task.dueDate?.toString() ?: "none"),
                    "status" to (task.status ?: "none"),
                    "recurrenceEnabled" to task.recurrenceEnabled,
                ),
            )
            val taskDueDate = task.dueDate
            if (taskDueDate == null) {
                skippedNoDate++
                logger.d(
                    "RecurrenceManager.autoAdvanceRecurringTasks",
                    "Skipped - no dueDate",
                    mapOf(
                        "taskId" to task.id,
                        "title" to task.title,
                    ),
                )
                continue
            }
            if (task.status != "pending") {
                skippedNotPending++
                logger.d(
                    "RecurrenceManager.autoAdvanceRecurringTasks",
                    "Skipped - not pending",
                    mapOf(
                        "taskId" to task.id,
                        "title" to task.title,
                        "status" to (task.status ?: "null"),
                    ),
                )
                continue
            }
            if (taskDueDate != null && task.status == "pending") {
                val effectiveDayBoundary = if (task.dayBoundaryHour > 0) {
                    task.dayBoundaryHour
                } else {
                    globalDayBoundaryHour
                }
                val taskEffectiveToday = getEffectiveToday(effectiveDayBoundary)
                val dueDate = taskDueDate.toLocalDate()
                if (dueDate < taskEffectiveToday) {
                    processOverdueTask(task, dueDate, taskEffectiveToday)
                    processedCount++
                } else {
                    skippedNotOverdue++
                    logger.d(
                        "RecurrenceManager.autoAdvanceRecurringTasks",
                        "Skipped - not overdue",
                        mapOf(
                            "taskId" to task.id,
                            "title" to task.title,
                            "dueDate" to dueDate.toString(),
                            "effectiveToday" to taskEffectiveToday.toString(),
                        ),
                    )
                }
            }
        }

        logger.i(
            "RecurrenceManager.autoAdvanceRecurringTasks",
            "Auto-advance complete",
            mapOf(
                "processedCount" to processedCount,
                "skippedNoDate" to skippedNoDate,
                "skippedNotPending" to skippedNotPending,
                "skippedNotOverdue" to skippedNotOverdue,
                "totalRecurringTasks" to tasks.size,
            ),
        )

        return processedCount
    }

    /**
     * Repair recurring tasks that have incorrect status.
     * This fixes tasks stuck with status="skipped" or "completed"
     * that should be "pending" for recurring habits.
     * @return Number of tasks repaired
     */
    suspend fun repairStuckRecurringTasks(): Int {
        logger.i("RecurrenceManager.repairStuckRecurringTasks", "Starting repair")
        val tasks = taskRepository.getRecurringTasks()
        var repairedCount = 0
        val today = LocalDate.now()
        for (task in tasks) {
            if (isFrequencyHabit(task)) {
                frequencyHabitSync.sync(task)
                repairedCount++
                continue
            }
            // Recurring tasks should always be "pending" - they recur forever
            // If status is "skipped", "completed", etc., reset to "pending" and advance due date
            if (task.status != "pending" && task.status != "archived") {
                val newDueDate = task.dueDate?.let { dueDate ->
                    val dueLocalDate = dueDate.toLocalDate()
                    if (dueLocalDate < today) {
                        // Advance to today at original time
                        today.atTime(dueDate.toLocalTime())
                    } else {
                        dueDate
                    }
                } ?: today.atTime(9, 0) // Default 9 AM if no due date

                logger.i(
                    "RecurrenceManager.repairStuckRecurringTasks",
                    "Repairing task",
                    mapOf(
                        "taskId" to task.id,
                        "title" to task.title,
                        "oldStatus" to (task.status ?: "null"),
                        "oldDueDate" to (task.dueDate?.toString() ?: "null"),
                        "newDueDate" to newDueDate.toString(),
                    ),
                )

                taskRepository.updateRecurrenceState(
                    taskId = task.id,
                    newDueDate = newDueDate,
                    lastOccurrenceDate = LocalDateTime.now(),
                )
                repairedCount++
            }
        }

        logger.i(
            "RecurrenceManager.repairStuckRecurringTasks",
            "Repair complete",
            mapOf(
                "repairedCount" to repairedCount,
                "totalRecurringTasks" to tasks.size,
            ),
        )

        return repairedCount
    }

    /**
     * Process a single overdue recurring task:
     * 1. Create missed occurrences for each day
     * 2. Apply decay to score
     * 3. Advance due date to today
     */
    private suspend fun processOverdueTask(
        task: Task,
        overdueDate: LocalDate,
        effectiveToday: LocalDate,
    ) {
        val frequency = RecurrenceScoreCalculator.fromRule(task.recurrenceRule)
        val daysMissed = ChronoUnit.DAYS.between(overdueDate, effectiveToday).toInt()

        logger.d(
            "RecurrenceManager.processOverdueTask",
            "Processing overdue task",
            mapOf(
                "taskId" to task.id,
                "title" to task.title,
                "overdueDate" to overdueDate.toString(),
                "effectiveToday" to effectiveToday.toString(),
                "daysMissed" to daysMissed,
            ),
        )

        // Create missed occurrences for each day (limit to prevent huge batch)
        val maxMissedToCreate = minOf(daysMissed, 30) // Cap at 30 days
        for (i in 0 until maxMissedToCreate) {
            val missedDate = overdueDate.plusDays(i.toLong())
            createMissedOccurrence(task.id, missedDate)
        }

        // Decay scoring removed (Inc 3) — missed rows are scored 0.0 by the
        // score roll-up catch-up; currentScore bridged by ScoreRollupCascadeService.

        // Calculate next due date (today at original time, or tomorrow for frequency-based)
        val originalTime = task.dueDate?.toLocalTime() ?: LocalTime.of(9, 0)
        val newDueDate = effectiveToday.atTime(originalTime)

        // Update task
        taskRepository.updateRecurrenceState(
            taskId = task.id,
            newDueDate = newDueDate,
            lastOccurrenceDate = effectiveToday.minusDays(1).atStartOfDay(),
        )

        logger.i(
            "RecurrenceManager.processOverdueTask",
            "Task advanced",
            mapOf(
                "taskId" to task.id,
                "newDueDate" to newDueDate.toString(),
                "missedOccurrencesCreated" to maxMissedToCreate,
            ),
        )
    }

    /**
     * Create a missed occurrence entry for a specific date.
     *
     * Skips when a user row already exists for (task, day) — auto-writes never
     * touch rows that exist (user data wins). Mirrors the self-governance
     * gap-fill rule; without this, the unconditional insert could duplicate a
     * user's row (see OCC_CHECK_EXISTING / OCC_SKIP_AUTO in the DB flow spec).
     */
    private suspend fun createMissedOccurrence(taskId: String, date: LocalDate) {
        val existing = taskOccurrenceRepository.getOccurrenceForDate(taskId, date)
        if (existing != null) {
            logger.d(
                "RecurrenceManager.createMissedOccurrence",
                "SKIP auto-miss — user row exists",
                mapOf(
                    "taskId" to taskId,
                    "date" to date.toString(),
                    "existingStatus" to existing.status,
                ),
            )
            return
        }
        logger.d(
            "RecurrenceManager.createMissedOccurrence",
            "CREATE auto-miss — gap fill",
            mapOf(
                "taskId" to taskId,
                "date" to date.toString(),
            ),
        )
        taskOccurrenceRepository.recordOccurrence(
            taskId = taskId,
            dueDate = date.atStartOfDay(),
            status = "missed",
            note = "Auto-detected missed",
        )
    }

    /**
     * Handle task completion with score update.
     */
    override suspend fun onTaskCompleted(task: Task, note: String?, reason: String?, nextDueStrategy: String?) {
        if (!task.recurrenceEnabled) return
        if (isFrequencyHabit(task)) {
            frequencyHabitSync.sync(task)
            return
        }

        // Decay scoring removed (Inc 3) — currentScore is now bridged from the
        // score roll-up L1 by ScoreRollupCascadeService; due-date advancement kept.
        val newDueDate = calculateNextDueDate(task, nextDueStrategy)

        taskRepository.updateRecurrenceState(
            taskId = task.id,
            newDueDate = newDueDate,
            lastOccurrenceDate = LocalDateTime.now(),
        )

        logger.i(
            "RecurrenceManager.onTaskCompleted",
            "Task completed, due date advanced",
            mapOf(
                "taskId" to task.id,
                "nextDueDate" to newDueDate.toString(),
            ),
        )
    }

    /**
     * Handle task skip with decay applied (uHabits style).
     */
    override suspend fun onTaskSkipped(task: Task, note: String?, reason: String?, nextDueStrategy: String?) {
        if (!task.recurrenceEnabled) return
        if (isFrequencyHabit(task)) {
            frequencyHabitSync.sync(task)
            return
        }

        // Decay scoring removed (Inc 3) — currentScore is bridged from the
        // score roll-up L1 by ScoreRollupCascadeService; due-date advancement kept.
        val newDueDate = calculateNextDueDate(task, nextDueStrategy)
        taskRepository.updateRecurrenceState(
            taskId = task.id,
            newDueDate = newDueDate,
            lastOccurrenceDate = LocalDateTime.now(),
        )

        logger.i(
            "RecurrenceManager.onTaskSkipped",
            "Task skipped, due date advanced",
            mapOf(
                "taskId" to task.id,
                "nextDueDate" to newDueDate.toString(),
            ),
        )
    }

    /**
     * Handle task miss with due-date advancement (decay scoring removed in inc 3).
     */
    override suspend fun onTaskMissed(task: Task, note: String?, reason: String?, nextDueStrategy: String?) {
        if (!task.recurrenceEnabled) return
        if (isFrequencyHabit(task)) {
            frequencyHabitSync.sync(task)
            return
        }
        val newDueDate = calculateNextDueDate(task, nextDueStrategy)

        taskRepository.updateRecurrenceState(
            taskId = task.id,
            newDueDate = newDueDate,
            lastOccurrenceDate = LocalDateTime.now(),
        )

        logger.i(
            "RecurrenceManager.onTaskMissed",
            "Task missed, due date advanced",
            mapOf(
                "taskId" to task.id,
                "nextDueDate" to newDueDate.toString(),
            ),
        )
    }

    /**
     * Calculate next due date based on recurrence rule and strategy.
     *
     * Uses recurrenceStrategy:
     * - "actual": Calculate from today (based on actual completion)
     * - "planned": Calculate from task's current dueDate (based on original plan)
     * - null: Use task's default recurrenceStrategy
     *
     * The method:
     * 1. Determines base date from strategy + task context
     * 2. Parses recurrenceRule into RecurrenceConfig (handles patterns like "every 3rd", "Mon/Wed", etc.)
     * 3. Finds next scheduled date using the recurrence pattern
     * 4. Preserves the time from original dueDate to maintain user's preferred time
     */
    private fun calculateNextDueDate(task: Task, overrideStrategy: String? = null): LocalDateTime {
        logger.d(
            "RecurrenceManager.calculateNextDueDate",
            "Calculating next due date",
            mapOf(
                "taskId" to task.id,
                "recurrenceRule" to task.recurrenceRule,
                "recurrenceStrategy" to task.recurrenceStrategy,
                "currentDueDate" to task.dueDate.toString(),
            ),
        )

        // Parse recurrence rule into flexible config
        val recurrenceConfig = RecurrenceConfig.parse(task.recurrenceRule)

        // Determine the base date and search range based on strategy
        val strategy = overrideStrategy ?: task.recurrenceStrategy
        val baseDate = when (strategy) {
            "actual" -> LocalDate.now()

            // Calculate from today for "actual" strategy
            else -> task.dueDate?.toLocalDate() ?: LocalDate.now() // "planned" or default: from original due date
        }

        // Get the time to preserve for all occurrences
        val preservedTime = task.dueDate?.toLocalTime() ?: LocalTime.NOON

        // Find the next scheduled occurrence starting from the day after the base date
        val searchStart = baseDate.plusDays(1)
        val searchEnd = baseDate.plusYears(1) // Look ahead up to 1 year for next occurrence
        val nextScheduledDate = recurrenceConfig
            .getScheduledDatesInRange(searchStart, searchEnd)
            .firstOrNull()
            ?.atTime(preservedTime)
            ?: run {
                // Fallback: If no scheduled date found in the next year, advance by simple day count
                logger.w(
                    "RecurrenceManager.calculateNextDueDate",
                    "No scheduled date found in next year, using fallback",
                    mapOf(
                        "taskId" to task.id,
                        "recurrenceRule" to task.recurrenceRule,
                        "baseDate" to baseDate.toString(),
                    ),
                )
                val frequency = RecurrenceScoreCalculator.fromRule(task.recurrenceRule)
                val daysToAdd = frequency.denominator.toLong() / frequency.numerator
                baseDate.plusDays(daysToAdd).atTime(preservedTime)
            }

        logger.i(
            "RecurrenceManager.calculateNextDueDate",
            "Next due date calculated",
            mapOf(
                "taskId" to task.id,
                "nextDueDate" to nextScheduledDate.toString(),
                "recurrenceType" to recurrenceConfig.type.name,
            ),
        )

        return nextScheduledDate
    }

    /**
     * Get effective "today" considering day boundary.
     * If current time is before boundary hour, it's still "yesterday".
     */
    private fun getEffectiveToday(dayBoundaryHour: Int): LocalDate {
        val now = LocalDateTime.now()
        val boundaryHour = dayBoundaryHour.coerceIn(0, 5)

        return if (now.hour < boundaryHour) {
            now.toLocalDate().minusDays(1)
        } else {
            now.toLocalDate()
        }
    }

    /**
     * Get completion statistics for a task.
     * Uses frequency-aware calculation that respects the recurrence schedule.
     */
    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; broad catch intentional
    override suspend fun getCompletionStats(task: Task): CompletionStats {
        val occurrences = taskOccurrenceRepository.getOccurrencesByTaskId(task.id)
        val today = LocalDate.now()
        // The frequency case belongs to FrequencyHabitSync; asking it keeps one answer for "how is this
        // habit doing", whether the caller is scoring a day or syncing a window.
        frequencyHabitSync.completionStatsOrNull(task)?.let { return it }

        // Parse recurrence config for frequency-aware calculation
        val recurrenceConfig = RecurrenceConfig.parse(task.recurrenceRule)

        // Build map of date -> status
        val occurrenceMap = mutableMapOf<LocalDate, String>()
        var firstOccurrenceDate: LocalDate? = null
        for (occ in occurrences) {
            try {
                val occDate = LocalDate.parse(occ.occurrenceDate.take(10))
                occurrenceMap[occDate] = occ.status

                // Track first occurrence date (only completed/skipped/missed count)
                if (occ.status in listOf("completed", "skipped", "missed")) {
                    if (firstOccurrenceDate == null || occDate.isBefore(firstOccurrenceDate)) {
                        firstOccurrenceDate = occDate
                    }
                }
            } catch (e: DateTimeParseException) {
                logger.w(
                    "RecurrenceManager.getCompletionStats",
                    "Failed to parse occurrence date",
                    mapOf(
                        "occurrenceDate" to occ.occurrenceDate,
                        "error" to (e.message ?: "unknown"),
                    ),
                )
            }
        }

        // Use frequency-aware stats if we have first occurrence
        return if (firstOccurrenceDate != null) {
            RecurrenceScoreCalculator.calculateFrequencyAwareStats(
                occurrences = occurrenceMap,
                recurrenceConfig = recurrenceConfig,
                firstOccurrenceDate = firstOccurrenceDate,
            )
        } else {
            // Fall back to legacy calculation
            val indexedOccurrences = occurrences.mapNotNull { occ ->
                try {
                    val occDate = LocalDate.parse(occ.occurrenceDate.take(10))
                    val dayIndex = ChronoUnit.DAYS.between(occDate, today).toInt()
                    dayIndex to occ.status
                } catch (e: DateTimeParseException) {
                    logger.w("RecurrenceManager.buildIndexedOccurrences", "Skipping occurrence with invalid date", mapOf("date" to occ.occurrenceDate))
                    null
                }
            }
            RecurrenceScoreCalculator.calculateCompletionStats(indexedOccurrences)
        }
    }
    /**
     * True when the task's recurrence rule is a frequency habit (N times per
     * M days) rather than a fixed-schedule rule.
     */
    override fun isFrequencyHabit(task: Task): Boolean = isFrequencyRule(task)

    companion object {
        /**
         * Whether [task]'s recurrence rule is a FREQUENCY habit ("3 times per 7 days") rather than an
         * interval ("every 1 day").
         *
         * In the companion rather than on the instance because [FrequencyHabitSync] has to ask the same
         * question before it syncs — and a class that answers "is this a frequency habit?" two different
         * ways is how the desktop and the phone start disagreeing about a user's habits.
         */
        fun isFrequencyRule(task: Task): Boolean {
            if (!task.recurrenceEnabled) return false
            if (Frequency.isSerializedRule(task.recurrenceRule)) return true
            return RecurrenceConfig.parse(task.recurrenceRule).type == RecurrenceType.FREQUENCY
        }
    }

    /**
     * Re-syncs one frequency habit's window state by [taskId] (no-op for non-frequency tasks).
     *
     * Delegates to [FrequencyHabitSync]: a frequency habit is RESYNCED to its window boundary, which is a
     * different concern from the occurrence-by-occurrence advance the rest of this class performs.
     */
    suspend fun refreshFrequencyHabitState(taskId: String) {
        frequencyHabitSync.refresh(taskId)
    }
}
