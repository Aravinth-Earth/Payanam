//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.usecase

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Frequency
import io.payanam.domain.model.RecurrenceConfig
import io.payanam.domain.model.RecurrenceType
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.scoring.CompletionStats
import io.payanam.scoring.FrequencyWindowSummary
import io.payanam.scoring.RecurrenceScoreCalculator
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.max
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages recurring task lifecycle including:
 * - Auto-advancing past-due tasks
 * - Auto-generating missed occurrences for legacy recurring tasks
 * - Calculating decay scores
 * - Day boundary handling
 */
@Singleton
/**
 * RecurrenceManager.
 */
class RecurrenceManager @Inject constructor(
    private val taskRepository: TaskRepository,
    private val taskOccurrenceRepository: TaskOccurrenceRepository,
) {
    private val logger = UnifiedLogger.getInstance()
    private data class FrequencyWindowState(
        /** Start. */
        val start: LocalDate,
        /** End. */
        val end: LocalDate,
        /** Completed count. */
        val completedCount: Int,
        /** Skipped count. */
        val skippedCount: Int,
        /** Target count. */
        val targetCount: Int,
        /** Effective target count. */
        val effectiveTargetCount: Int,
    ) {
        /** Is satisfied. */
        val isSatisfied: Boolean get() = completedCount >= effectiveTargetCount
    }

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
            /** Map of. */
            mapOf(
                "globalDayBoundaryHour" to globalDayBoundaryHour,
            ),
        )

        /** Effective today. */
        val effectiveToday = getEffectiveToday(globalDayBoundaryHour)
        /** Tasks. */
        val tasks = taskRepository.getRecurringTasks()
        /** Processed count. */
        var processedCount = 0
        /** Skipped no date. */
        var skippedNoDate = 0
        /** Skipped not pending. */
        var skippedNotPending = 0
        /** Skipped not overdue. */
        var skippedNotOverdue = 0

        /** For. */
        for (task in tasks) {
            /** If. */
            if (isFrequencyHabit(task)) {
                /** Sync frequency habit state. */
                syncFrequencyHabitState(task)
                processedCount++
                /** Continue. */
                continue
            }
            logger.d(
                "RecurrenceManager.autoAdvanceRecurringTasks",
                "Processing task",
                /** Map of. */
                mapOf(
                    "taskId" to task.id,
                    "title" to task.title,
                    "dueDate" to (task.dueDate?.toString() ?: "none"),
                    "status" to (task.status ?: "none"),
                    "recurrenceEnabled" to task.recurrenceEnabled,
                ),
            )

            /** Task due date. */
            val taskDueDate = task.dueDate
            /** If. */
            if (taskDueDate == null) {
                skippedNoDate++
                logger.d(
                    "RecurrenceManager.autoAdvanceRecurringTasks",
                    "Skipped - no dueDate",
                    /** Map of. */
                    mapOf(
                        "taskId" to task.id,
                        "title" to task.title,
                    ),
                )
                /** Continue. */
                continue
            }
            /** If. */
            if (task.status != "pending") {
                skippedNotPending++
                logger.d(
                    "RecurrenceManager.autoAdvanceRecurringTasks",
                    "Skipped - not pending",
                    /** Map of. */
                    mapOf(
                        "taskId" to task.id,
                        "title" to task.title,
                        "status" to (task.status ?: "null"),
                    ),
                )
                /** Continue. */
                continue
            }
            /** If. */
            if (taskDueDate != null && task.status == "pending") {
                /** Effective day boundary. */
                val effectiveDayBoundary = if (task.dayBoundaryHour > 0) {
                    task.dayBoundaryHour
                } else {
                    /** Global day boundary hour. */
                    globalDayBoundaryHour
                }
                /** Task effective today. */
                val taskEffectiveToday = getEffectiveToday(effectiveDayBoundary)
                /** Due date. */
                val dueDate = taskDueDate.toLocalDate()

                /** If. */
                if (dueDate < taskEffectiveToday) {
                    /** Process overdue task. */
                    processOverdueTask(task, dueDate, taskEffectiveToday)
                    processedCount++
                } else {
                    skippedNotOverdue++
                    logger.d(
                        "RecurrenceManager.autoAdvanceRecurringTasks",
                        "Skipped - not overdue",
                        /** Map of. */
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
            /** Map of. */
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

        /** Tasks. */
        val tasks = taskRepository.getRecurringTasks()
        /** Repaired count. */
        var repairedCount = 0
        /** Today. */
        val today = LocalDate.now()

        /** For. */
        for (task in tasks) {
            /** If. */
            if (isFrequencyHabit(task)) {
                /** Sync frequency habit state. */
                syncFrequencyHabitState(task)
                repairedCount++
                /** Continue. */
                continue
            }
            // Recurring tasks should always be "pending" - they recur forever
            // If status is "skipped", "completed", etc., reset to "pending" and advance due date
            /** If. */
            if (task.status != "pending" && task.status != "archived") {
                /** New due date. */
                val newDueDate = task.dueDate?.let { dueDate ->
                    /** Due local date. */
                    val dueLocalDate = dueDate.toLocalDate()
                    /** If. */
                    if (dueLocalDate < today) {
                        // Advance to today at original time
                        today.atTime(dueDate.toLocalTime())
                    } else {
                        /** Due date. */
                        dueDate
                    }
                } ?: today.atTime(9, 0) // Default 9 AM if no due date

                logger.i(
                    "RecurrenceManager.repairStuckRecurringTasks",
                    "Repairing task",
                    /** Map of. */
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
            /** Map of. */
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
        /** Task. */
        task: Task,
        /** Overdue date. */
        overdueDate: LocalDate,
        /** Effective today. */
        effectiveToday: LocalDate,
    ) {
        /** Frequency. */
        val frequency = RecurrenceScoreCalculator.fromRule(task.recurrenceRule)
        /** Days missed. */
        val daysMissed = ChronoUnit.DAYS.between(overdueDate, effectiveToday).toInt()

        logger.d(
            "RecurrenceManager.processOverdueTask",
            "Processing overdue task",
            /** Map of. */
            mapOf(
                "taskId" to task.id,
                "title" to task.title,
                "overdueDate" to overdueDate.toString(),
                "effectiveToday" to effectiveToday.toString(),
                "daysMissed" to daysMissed,
            ),
        )

        // Create missed occurrences for each day (limit to prevent huge batch)
        /** Max missed to create. */
        val maxMissedToCreate = minOf(daysMissed, 30) // Cap at 30 days
        /** For. */
        for (i in 0 until maxMissedToCreate) {
            /** Missed date. */
            val missedDate = overdueDate.plusDays(i.toLong())
            /** Create missed occurrence. */
            createMissedOccurrence(task.id, missedDate)
        }

        // Decay scoring removed (Inc 3) — missed rows are scored 0.0 by the
        // score roll-up catch-up; currentScore bridged by ScoreRollupCascadeService.

        // Calculate next due date (today at original time, or tomorrow for frequency-based)
        /** Original time. */
        val originalTime = task.dueDate?.toLocalTime() ?: LocalTime.of(9, 0)
        /** New due date. */
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
            /** Map of. */
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
        /** Existing. */
        val existing = taskOccurrenceRepository.getOccurrenceForDate(taskId, date)
        /** If. */
        if (existing != null) {
            logger.d(
                "RecurrenceManager.createMissedOccurrence",
                "SKIP auto-miss — user row exists",
                /** Map of. */
                mapOf(
                    "taskId" to taskId,
                    "date" to date.toString(),
                    "existingStatus" to existing.status,
                ),
            )
            /** Return. */
            return
        }
        logger.d(
            "RecurrenceManager.createMissedOccurrence",
            "CREATE auto-miss — gap fill",
            /** Map of. */
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
    suspend fun onTaskCompleted(task: Task, note: String? = null, reason: String? = null, nextDueStrategy: String? = null) {
        /** If. */
        if (!task.recurrenceEnabled) return

        /** If. */
        if (isFrequencyHabit(task)) {
            /** Sync frequency habit state. */
            syncFrequencyHabitState(task)
            /** Return. */
            return
        }

        // Decay scoring removed (Inc 3) — currentScore is now bridged from the
        // score roll-up L1 by ScoreRollupCascadeService; due-date advancement kept.
        /** New due date. */
        val newDueDate = calculateNextDueDate(task, nextDueStrategy)

        taskRepository.updateRecurrenceState(
            taskId = task.id,
            newDueDate = newDueDate,
            lastOccurrenceDate = LocalDateTime.now(),
        )

        logger.i(
            "RecurrenceManager.onTaskCompleted",
            "Task completed, due date advanced",
            /** Map of. */
            mapOf(
                "taskId" to task.id,
                "nextDueDate" to newDueDate.toString(),
            ),
        )
    }

    /**
     * Handle task skip with decay applied (uHabits style).
     */
    suspend fun onTaskSkipped(task: Task, note: String? = null, reason: String? = null, nextDueStrategy: String? = null) {
        /** If. */
        if (!task.recurrenceEnabled) return

        /** If. */
        if (isFrequencyHabit(task)) {
            /** Sync frequency habit state. */
            syncFrequencyHabitState(task)
            /** Return. */
            return
        }

        // Decay scoring removed (Inc 3) — currentScore is bridged from the
        // score roll-up L1 by ScoreRollupCascadeService; due-date advancement kept.
        /** New due date. */
        val newDueDate = calculateNextDueDate(task, nextDueStrategy)
        taskRepository.updateRecurrenceState(
            taskId = task.id,
            newDueDate = newDueDate,
            lastOccurrenceDate = LocalDateTime.now(),
        )

        logger.i(
            "RecurrenceManager.onTaskSkipped",
            "Task skipped, due date advanced",
            /** Map of. */
            mapOf(
                "taskId" to task.id,
                "nextDueDate" to newDueDate.toString(),
            ),
        )
    }

    /**
     * Handle task miss with due-date advancement (decay scoring removed in Inc 3).
     */
    suspend fun onTaskMissed(task: Task, note: String? = null, reason: String? = null, nextDueStrategy: String? = null) {
        /** If. */
        if (!task.recurrenceEnabled) return

        /** If. */
        if (isFrequencyHabit(task)) {
            /** Sync frequency habit state. */
            syncFrequencyHabitState(task)
            /** Return. */
            return
        }

        /** New due date. */
        val newDueDate = calculateNextDueDate(task, nextDueStrategy)

        taskRepository.updateRecurrenceState(
            taskId = task.id,
            newDueDate = newDueDate,
            lastOccurrenceDate = LocalDateTime.now(),
        )

        logger.i(
            "RecurrenceManager.onTaskMissed",
            "Task missed, due date advanced",
            /** Map of. */
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
            /** Map of. */
            mapOf(
                "taskId" to task.id,
                "recurrenceRule" to task.recurrenceRule,
                "recurrenceStrategy" to task.recurrenceStrategy,
                "currentDueDate" to task.dueDate.toString(),
            ),
        )

        // Parse recurrence rule into flexible config
        /** Recurrence config. */
        val recurrenceConfig = RecurrenceConfig.parse(task.recurrenceRule)

        // Determine the base date and search range based on strategy
        /** Strategy. */
        val strategy = overrideStrategy ?: task.recurrenceStrategy
        /** Base date. */
        val baseDate = when (strategy) {
            "actual" -> LocalDate.now()

            // Calculate from today for "actual" strategy
            else -> task.dueDate?.toLocalDate() ?: LocalDate.now() // "planned" or default: from original due date
        }

        // Get the time to preserve for all occurrences
        /** Preserved time. */
        val preservedTime = task.dueDate?.toLocalTime() ?: LocalTime.NOON

        // Find the next scheduled occurrence starting from the day after the base date
        /** Search start. */
        val searchStart = baseDate.plusDays(1)
        /** Search end. */
        val searchEnd = baseDate.plusYears(1) // Look ahead up to 1 year for next occurrence

        /** Next scheduled date. */
        val nextScheduledDate = recurrenceConfig
            .getScheduledDatesInRange(searchStart, searchEnd)
            .firstOrNull()
            ?.atTime(preservedTime)
            ?: run {
                // Fallback: If no scheduled date found in the next year, advance by simple day count
                logger.w(
                    "RecurrenceManager.calculateNextDueDate",
                    "No scheduled date found in next year, using fallback",
                    /** Map of. */
                    mapOf(
                        "taskId" to task.id,
                        "recurrenceRule" to task.recurrenceRule,
                        "baseDate" to baseDate.toString(),
                    ),
                )
                /** Frequency. */
                val frequency = RecurrenceScoreCalculator.fromRule(task.recurrenceRule)
                /** Days to add. */
                val daysToAdd = frequency.denominator.toLong() / frequency.numerator
                baseDate.plusDays(daysToAdd).atTime(preservedTime)
            }

        logger.i(
            "RecurrenceManager.calculateNextDueDate",
            "Next due date calculated",
            /** Map of. */
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
        /** Now. */
        val now = LocalDateTime.now()
        /** Boundary hour. */
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
    suspend fun getCompletionStats(task: Task): CompletionStats {
        /** Occurrences. */
        val occurrences = taskOccurrenceRepository.getOccurrencesByTaskId(task.id)
        /** Today. */
        val today = LocalDate.now()

        /** If. */
        if (isFrequencyHabit(task)) {
            /** Frequency. */
            val frequency = Frequency.legacyParse(task.recurrenceRule)
            /** Anchor date. */
            val anchorDate = frequency.anchorDate ?: task.dueDate?.toLocalDate() ?: task.createdAt.toLocalDate()
            /** Occurrence map. */
            val occurrenceMap = occurrences.associateNotNull { occurrence ->
                /** Parse occurrence date. */
                parseOccurrenceDate(occurrence)?.let { it to occurrence.status }
            }
            return RecurrenceScoreCalculator.calculateFrequencyAwareStats(
                occurrences = occurrenceMap,
                frequency = frequency,
                anchorDate = anchorDate,
                today = today,
            )
        }

        // Parse recurrence config for frequency-aware calculation
        /** Recurrence config. */
        val recurrenceConfig = RecurrenceConfig.parse(task.recurrenceRule)

        // Build map of date -> status
        /** Occurrence map. */
        val occurrenceMap = mutableMapOf<LocalDate, String>()
        /** First occurrence date. */
        var firstOccurrenceDate: LocalDate? = null

        /** For. */
        for (occ in occurrences) {
            try {
                /** Occ date. */
                val occDate = LocalDate.parse(occ.occurrenceDate.take(10))
                occurrenceMap[occDate] = occ.status

                // Track first occurrence date (only completed/skipped/missed count)
                /** If. */
                if (occ.status in listOf("completed", "skipped", "missed")) {
                    /** If. */
                    if (firstOccurrenceDate == null || occDate.isBefore(firstOccurrenceDate)) {
                        firstOccurrenceDate = occDate
                    }
                }
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.w(
                    "RecurrenceManager.getCompletionStats",
                    "Failed to parse occurrence date",
                    /** Map of. */
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
            /** Indexed occurrences. */
            val indexedOccurrences = occurrences.mapNotNull { occ ->
                try {
                    /** Occ date. */
                    val occDate = LocalDate.parse(occ.occurrenceDate.take(10))
                    /** Day index. */
                    val dayIndex = ChronoUnit.DAYS.between(occDate, today).toInt()
                    dayIndex to occ.status
                } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                    /** Null. */
                    null
                }
            }
            RecurrenceScoreCalculator.calculateCompletionStats(indexedOccurrences)
        }
    }

    /**
     * Is frequency habit.
     */
    fun isFrequencyHabit(task: Task): Boolean {
        /** If. */
        if (!task.recurrenceEnabled) return false
        /** If. */
        if (Frequency.isSerializedRule(task.recurrenceRule)) return true
        return RecurrenceConfig.parse(task.recurrenceRule).type == RecurrenceType.FREQUENCY
    }

    /**
     * Refresh frequency habit state.
     */
    suspend fun refreshFrequencyHabitState(taskId: String) {
        /** Task. */
        val task = taskRepository.getTaskById(taskId) ?: return
        /** If. */
        if (isFrequencyHabit(task)) {
            /** Sync frequency habit state. */
            syncFrequencyHabitState(task)
        }
    }

    private suspend fun syncFrequencyHabitState(
        /** Task. */
        task: Task,
    ) {
        /** Frequency rule. */
        val frequencyRule = Frequency.legacyParse(task.recurrenceRule)
        /** Occurrences. */
        val occurrences = taskOccurrenceRepository.getOccurrencesByTaskId(task.id)
        /** Today. */
        val today = LocalDate.now()
        /** Window state. */
        val windowState = evaluateFrequencyWindow(task, frequencyRule, occurrences, today)
        /** Occurrence map. */
        val occurrenceMap = occurrences.associateNotNull { occurrence ->
            /** Parse occurrence date. */
            parseOccurrenceDate(occurrence)?.let { it to occurrence.status }
        }
        /** Anchor date. */
        val anchorDate = frequencyRule.anchorDate ?: task.dueDate?.toLocalDate() ?: task.createdAt.toLocalDate()
        /** Stats. */
        val stats = RecurrenceScoreCalculator.calculateFrequencyAwareStats(
            occurrences = occurrenceMap,
            frequency = frequencyRule,
            anchorDate = anchorDate,
            today = today,
        )
        /** Next reminder date. */
        val nextReminderDate = if (windowState.isSatisfied) {
            windowState.end.plusDays(1)
        } else {
            /** Today reminder date time. */
            val todayReminderDateTime = today.atTime(task.dueDate?.toLocalTime() ?: LocalTime.of(9, 0))
            /** If. */
            if (!todayReminderDateTime.isBefore(LocalDateTime.now())) today else today.plusDays(1)
        }
        /** Reminder time. */
        val reminderTime = task.dueDate?.toLocalTime() ?: LocalTime.of(9, 0)
        /** New due date. */
        val newDueDate = nextReminderDate.atTime(reminderTime)
        /** Latest occurrence date. */
        val latestOccurrenceDate = occurrences
            .mapNotNull { parseOccurrenceDate(it) }
            .maxOrNull()
            ?.atTime(reminderTime)
            ?: task.lastOccurrenceDate
            ?: LocalDateTime.now()
        // Inc 4b: decay derived score removed — score roll-up (L1) owns scoring now.

        taskRepository.updateRecurrenceState(
            taskId = task.id,
            newDueDate = newDueDate,
            lastOccurrenceDate = latestOccurrenceDate,
        )

        logger.i(
            "RecurrenceManager.syncFrequencyHabitState",
            "Synced frequency habit state without occurrence generation",
            /** Map of. */
            mapOf(
                "taskId" to task.id,
                "windowStart" to windowState.start.toString(),
                "windowEnd" to windowState.end.toString(),
                "completedCount" to windowState.completedCount,
                "skippedCount" to windowState.skippedCount,
                "targetCount" to windowState.targetCount,
                "effectiveTargetCount" to windowState.effectiveTargetCount,
                "windowSatisfied" to windowState.isSatisfied,
                "nextReminderDate" to newDueDate.toString(),
                "currentStreak" to stats.currentStreak,
            ),
        )
    }

    private fun evaluateFrequencyWindow(
        /** Task. */
        task: Task,
        /** Frequency. */
        frequency: Frequency,
        occurrences: List<TaskOccurrence>,
        /** Today. */
        today: LocalDate,
    ): FrequencyWindowState {
        /** Anchor date. */
        val anchorDate = frequency.anchorDate ?: task.dueDate?.toLocalDate() ?: task.createdAt.toLocalDate()
        /** Denominator. */
        val denominator = max(1, frequency.denominator)
        /** Days since anchor. */
        val daysSinceAnchor = max(0L, ChronoUnit.DAYS.between(anchorDate, today))
        /** Window offset. */
        val windowOffset = (daysSinceAnchor / denominator) * denominator
        /** Window start. */
        val windowStart = anchorDate.plusDays(windowOffset)
        /** Window end. */
        val windowEnd = windowStart.plusDays((denominator - 1).toLong())
        /** Occurrence map. */
        val occurrenceMap = occurrences.associateNotNull { occurrence ->
            /** Parse occurrence date. */
            parseOccurrenceDate(occurrence)?.let { it to occurrence.status }
        }
        /** Window summary. */
        val windowSummary = RecurrenceScoreCalculator.buildFrequencyWindows(
            occurrences = occurrenceMap,
            frequency = frequency,
            anchorDate = anchorDate,
            rangeStart = windowStart,
            rangeEnd = windowEnd,
        ).firstOrNull() ?: buildFallbackWindowSummary(anchorDate, frequency, today)

        return FrequencyWindowState(
            start = windowSummary.start,
            end = windowSummary.end,
            completedCount = windowSummary.completedCount,
            skippedCount = windowSummary.skippedCount,
            targetCount = windowSummary.targetCount,
            effectiveTargetCount = windowSummary.effectiveTargetCount,
        )
    }

    private fun parseOccurrenceDate(occurrence: TaskOccurrence): LocalDate? =
        runCatching { LocalDate.parse(occurrence.occurrenceDate.take(10)) }.getOrNull()

    private fun buildFallbackWindowSummary(
        /** Anchor date. */
        anchorDate: LocalDate,
        /** Frequency. */
        frequency: Frequency,
        /** Today. */
        today: LocalDate,
    ): FrequencyWindowSummary {
        /** Denominator. */
        val denominator = max(1, frequency.denominator)
        /** Days since anchor. */
        val daysSinceAnchor = max(0L, ChronoUnit.DAYS.between(anchorDate, today))
        /** Window offset. */
        val windowOffset = (daysSinceAnchor / denominator) * denominator
        /** Window start. */
        val windowStart = anchorDate.plusDays(windowOffset)
        return FrequencyWindowSummary(
            start = windowStart,
            end = windowStart.plusDays((denominator - 1).toLong()),
            coveredDays = denominator,
            completedCount = 0,
            skippedCount = 0,
            targetCount = max(1, frequency.numerator),
            effectiveTargetCount = max(1, frequency.numerator),
        )
    }

    private inline fun <T, R> Iterable<T>.associateNotNull(transform: (T) -> Pair<LocalDate, R>?): Map<LocalDate, R> =
        buildMap {
            /** For. */
            for (item in this@associateNotNull) {
                /** Pair. */
                val pair = transform(item) ?: continue
                /** Put. */
                put(pair.first, pair.second)
            }
        }
}
