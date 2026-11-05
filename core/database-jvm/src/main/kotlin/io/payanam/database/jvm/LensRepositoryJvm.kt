//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import io.payanam.database.entity.LensReflectionEntity
import io.payanam.domain.lens.DailyStatsCalculator
import io.payanam.domain.lens.buildDimensionTrendBlocks
import io.payanam.domain.lens.buildHeatmapDays
import io.payanam.domain.lens.buildMinutePatternData
import io.payanam.domain.lens.buildWeekGridData
import io.payanam.domain.lens.computePlanCompletenessScore
import io.payanam.domain.lens.dayBoundedDurationMinutes
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.Task
import io.payanam.domain.repository.AverageDailyTimeTableData
import io.payanam.domain.repository.DailyFocusStat
import io.payanam.domain.repository.DailyFocusedHoursStat
import io.payanam.domain.repository.DailyTrackedTimeStat
import io.payanam.domain.repository.DayPlanRepository
import io.payanam.domain.repository.DimensionTrendBlock
import io.payanam.domain.repository.HabitPlanItem
import io.payanam.domain.repository.HabitRealityItem
import io.payanam.domain.repository.HeatmapDayData
import io.payanam.domain.repository.LensReflectionRecord
import io.payanam.domain.repository.LensRepository
import io.payanam.domain.repository.MinutePatternData
import io.payanam.domain.repository.PlanningLensData
import io.payanam.domain.repository.RealityLensData
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskPlanItem
import io.payanam.domain.repository.TaskRealityItem
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TimeEntryRepository
import io.payanam.domain.repository.UnifiedLensSnapshot
import io.payanam.domain.repository.WeekGridData
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * JDBC implementation of [LensRepository] over the encrypted database — the day's planning and reality
 * halves, their cached snapshot, and the reflection cards derived from them.
 *
 * It composes the other repositories rather than querying their tables: the same
 * [TaskRepository]/[TimeEntryRepository]/[TaskOccurrenceRepository]/[DayPlanRepository] contracts both
 * platforms implement, so the lens cannot disagree with the task list that produced it. The pure analytics
 * and the snapshot codec come from `:core:domain`.
 *
 * Behaviours that are easy to lose in a port, and are therefore pinned by tests:
 *
 *  - **A snapshot is CACHED and only recomputed when the day is dirty.** The dirty marker is cleared after a
 *    successful recompute, so a clean day costs one read. A not-dirty day with no cache entry still computes
 *    — otherwise a day whose cache was never written would show as empty forever.
 *  - **Budget dominates planned time.** `totalPlannedMinutes` is the budget when there is one and the sum of
 *    planned task/habit minutes otherwise, and plan completeness separately records whether a budget exists.
 *  - **Habit minutes are split between entries and occurrences.** A habit's minutes count from its time
 *    entry when one exists for that task, and from its occurrence's `actualDurationMinutes` when it does not —
 *    never both, or the same work would be counted twice.
 *  - **`untrackedMinutes` is `1440 - totalActualMinutes`**, with no floor: a day tracked past midnight reports
 *    a negative untracked remainder rather than being silently clamped.
 *  - **A reflection card is regenerated from scratch.** `generateReflectionCards` deletes the day's cards and
 *    rebuilds them, so a card that is no longer warranted disappears and an addressed card is replaced by a
 *    fresh unaddressed one.
 *
 * @param session an already-open session owned by the caller.
 * @param pollIntervalMs how often a subscribed read re-checks; shorten it in tests.
 */
@Suppress("LongParameterList", "TooManyFunctions", "LongMethod", "CyclomaticComplexMethod")
class LensRepositoryJvm(
    private val session: PayanamDatabaseSession,
    private val taskRepository: TaskRepository,
    private val timeEntryRepository: TimeEntryRepository,
    private val taskOccurrenceRepository: TaskOccurrenceRepository,
    private val dayPlanRepository: DayPlanRepository,
    private val pollIntervalMs: Long = JdbcFlows.DEFAULT_POLL_INTERVAL_MS,
) : LensRepository {
    private val logger = AppLogger.current

    /** Earliest day any time entry was logged, for the lens range selectors. */
    override suspend fun getFirstTrackedDate(): LocalDate? {
        logger.d("LensRepositoryJvm.getFirstTrackedDate", "Fetching first tracked date")
        val result =
            timeEntryRepository
                .getAllTimeEntries()
                .first()
                .minByOrNull { it.startedAt }
                ?.startedAt
                ?.toLocalDate()
        logger.d(
            "LensRepositoryJvm.getFirstTrackedDate",
            "First tracked date resolved",
            mapOf("date" to (result?.toString() ?: "null")),
        )
        return result
    }

    /** Per-day average of focused minutes across all logged entries. */
    override suspend fun getDailyFocusAverages(): List<DailyFocusStat> {
        logger.d("LensRepositoryJvm.getDailyFocusAverages", "Fetching daily focus averages")
        val all = timeEntryRepository.getAllTimeEntries().first()
        return DailyStatsCalculator.calculateDailyFocusAverages(all)
    }

    /** Per-day total tracked minutes (focused + untracked). */
    override suspend fun getDailyTrackedTimeStats(): List<DailyTrackedTimeStat> {
        logger.d("LensRepositoryJvm.getDailyTrackedTimeStats", "Fetching daily tracked time stats")
        val all = timeEntryRepository.getAllTimeEntries().first()
        return DailyStatsCalculator.calculateDailyTrackedTimeStats(all)
    }

    /** Per-day focused-hours total, distinct from the focus average. */
    override suspend fun getDailyFocusedHoursStats(): List<DailyFocusedHoursStat> {
        logger.d("LensRepositoryJvm.getDailyFocusedHoursStats", "Fetching daily focused hours stats")
        val all = timeEntryRepository.getAllTimeEntries().first()
        return DailyStatsCalculator.calculateDailyFocusedHoursStats(all)
    }

    /** Averaged time split across dimensions for a "typical day". */
    override suspend fun getAverageDailyTimeTableData(): AverageDailyTimeTableData? {
        logger.d("LensRepositoryJvm.getAverageDailyTimeTableData", "Fetching average daily time table")
        val all = timeEntryRepository.getAllTimeEntries().first()
        return DailyStatsCalculator.calculateAverageDailyTimeTable(all)
    }

    /** Rolling per-dimension time trends over the last [windowDays] days. */
    override suspend fun getDimensionTrendBlocks(windowDays: Int): List<DimensionTrendBlock> {
        val allEntries = timeEntryRepository.getAllTimeEntries().first()
        return buildDimensionTrendBlocks(allEntries, windowDays, logger)
    }

    /** One row per calendar day with its tracked-minute intensity. */
    override suspend fun getHeatmapDays(): List<HeatmapDayData> {
        val allEntries = timeEntryRepository.getAllTimeEntries().first()
        return buildHeatmapDays(allEntries, logger)
    }

    /** Week-by-week grid of tracked time. */
    override suspend fun getWeekGridData(excludeEmptyDays: Boolean): WeekGridData {
        val allEntries = timeEntryRepository.getAllTimeEntries().first()
        return buildWeekGridData(allEntries, excludeEmptyDays, logger)
    }

    /** Time-of-day distribution of tracked minutes. */
    override suspend fun getMinutePatternData(excludeEmptyDays: Boolean): MinutePatternData {
        val allEntries = timeEntryRepository.getAllTimeEntries().first()
        return buildMinutePatternData(allEntries, excludeEmptyDays, logger)
    }

    /** Total tracked minutes grouped by dimension within [start]..[end]. */
    override suspend fun getDimensionSplitForRange(
        start: LocalDate,
        end: LocalDate,
    ): Map<String?, Int> {
        logger.d(
            "LensRepositoryJvm.getDimensionSplitForRange",
            "Fetching dimension split for range",
            mapOf("start" to start.toString(), "end" to end.toString()),
        )
        val all = timeEntryRepository.getAllTimeEntries().first()
        return DailyStatsCalculator.calculateDimensionSplit(all, start, end)
    }

    /**
     * The day's planning+reality snapshot, recomputed only when the day is dirty, then persisted with the
     * dirty marker cleared.
     */
    override suspend fun calculateUnifiedSnapshot(dayKey: String): UnifiedLensSnapshot {
        val isDirty = loadLensDirtyDayMetadataSync(session, dayKey) != null
        if (!isDirty) {
            loadSnapshotFromPersistentCache(session, dayKey)?.let { cached ->
                logger.d(
                    "LensRepositoryJvm.calculateUnifiedSnapshot",
                    "Using cached unified snapshot",
                    mapOf("dayKey" to dayKey),
                )
                return cached
            }
        }
        val computed = buildUnifiedSnapshot(dayKey)
        persistSnapshotToDailyInsightCache(session, dayKey, computed)
        if (isDirty) {
            clearLensDayDirtySync(session, dayKey, logger)
        }
        return computed
    }

    /** The planning half of [calculateUnifiedSnapshot]. */
    override suspend fun calculatePlanningData(dayKey: String): PlanningLensData {
        logger.d("LensRepositoryJvm.calculatePlanningData", "Calculating planning data", mapOf("dayKey" to dayKey))
        return calculateUnifiedSnapshot(dayKey).planning
    }

    /** Reactive planning data: recomputes whenever the task list changes. */
    override fun observePlanningData(dayKey: String): Flow<PlanningLensData> {
        logger.d(
            "LensRepositoryJvm.observePlanningData",
            "Subscribing to planning data",
            mapOf("dayKey" to dayKey),
        )
        return taskRepository.getAllTasks().map { calculatePlanningData(dayKey) }
    }

    /** The reality half of [calculateUnifiedSnapshot]. */
    override suspend fun calculateRealityData(dayKey: String): RealityLensData {
        logger.d("LensRepositoryJvm.calculateRealityData", "Calculating reality data", mapOf("dayKey" to dayKey))
        return calculateUnifiedSnapshot(dayKey).reality
    }

    /** Reactive reality data: recomputes whenever a time entry changes. */
    override fun observeRealityData(dayKey: String): Flow<RealityLensData> {
        logger.d(
            "LensRepositoryJvm.observeRealityData",
            "Subscribing to reality data",
            mapOf("dayKey" to dayKey),
        )
        return timeEntryRepository.getAllTimeEntries().map { calculateRealityData(dayKey) }
    }

    /**
     * Wipes and regenerates [dayKey]'s reflection cards: untracked time over 8h, missed tasks, a focus gap
     * over 60m, and per-dimension gaps over 60m against the budget (or the planned time when there is none).
     */
    override suspend fun generateReflectionCards(dayKey: String) {
        logger.d(
            "LensRepositoryJvm.generateReflectionCards",
            "Generating reflection cards",
            mapOf("dayKey" to dayKey),
        )
        val snapshot = calculateUnifiedSnapshot(dayKey)
        val planning = snapshot.planning
        val reality = snapshot.reality
        val reflections = mutableListOf<LensReflectionEntity>()
        val now = LocalDateTime.now().toString()

        if (reality.untrackedMinutes > UNTRACKED_REFLECTION_THRESHOLD_MINUTES) {
            reflections.add(
                reflectionEntity(
                    dayKey = dayKey,
                    dimensionId = null,
                    reflectionType = "untracked_time",
                    title = "Significant Untracked Time",
                    description =
                        "You have ${reality.untrackedMinutes / 60}h ${reality.untrackedMinutes % 60}m untracked. " +
                            "Consider logging your activities.",
                    gapMinutes = reality.untrackedMinutes,
                    relatedEntityId = null,
                    now = now,
                ),
            )
        }

        reality.completedTasks.filter { it.status == "missed" }.forEach { task ->
            reflections.add(
                reflectionEntity(
                    dayKey = dayKey,
                    dimensionId = task.dimensionId,
                    reflectionType = "missed_task",
                    title = "Missed Task: ${task.title}",
                    description =
                        "This task was due but not completed. Consider rescheduling or marking as skipped.",
                    gapMinutes = null,
                    relatedEntityId = task.taskId,
                    now = now,
                ),
            )
        }

        if (reality.focusGapMinutes > FOCUS_GAP_REFLECTION_THRESHOLD_MINUTES) {
            reflections.add(
                reflectionEntity(
                    dayKey = dayKey,
                    dimensionId = null,
                    reflectionType = "focus_gap",
                    title = "Focus Gap Detected",
                    description =
                        "You planned ${reality.focusGapMinutes} more minutes of focused work than achieved.",
                    gapMinutes = reality.focusGapMinutes,
                    relatedEntityId = null,
                    now = now,
                ),
            )
        }

        // A dimension is checked when EITHER side knows about it: a budget with no planned time still
        // warrants a card, and so does planned time with no budget.
        val dimensionsToCheck =
            (planning.plannedTimeByDimension.keys + planning.budgetAllocationsByDimension.keys).toSet()
        dimensionsToCheck.forEach { dimId ->
            val actualMinutes = reality.actualTimeByDimension[dimId] ?: 0
            val plannedMinutes = planning.plannedTimeByDimension[dimId] ?: 0
            val budgetMinutes = planning.budgetAllocationsByDimension[dimId]
            val targetMinutes = budgetMinutes ?: plannedMinutes
            val gap = targetMinutes - actualMinutes
            if (gap > DIMENSION_GAP_REFLECTION_THRESHOLD_MINUTES && targetMinutes > 0) {
                val dimensionLabel = DimensionTaxonomyCatalog.fromCanonicalId(dimId)?.fallbackLabel ?: "Unknown"
                val gapType = if (budgetMinutes != null) "budget gap" else "planned gap"
                reflections.add(
                    reflectionEntity(
                        dayKey = dayKey,
                        dimensionId = dimId,
                        reflectionType = "dimension_gap",
                        title = "$dimensionLabel Time Gap",
                        description =
                            "Budget: ${targetMinutes}m, Tracked: ${actualMinutes}m. Gap: ${gap}m ($gapType).",
                        gapMinutes = gap,
                        relatedEntityId = null,
                        now = now,
                    ),
                )
            }
        }

        // Delete-then-insert in one transaction: the delete must not land without the rebuild, or the day
        // loses its cards entirely.
        JdbcQueries.transaction(session) {
            JdbcQueries.write(session, DELETE_REFLECTIONS_FOR_DAY_SQL, arrayOf(dayKey))
            reflections.forEach { reflection ->
                JdbcQueries.write(session, INSERT_REFLECTION_SQL, reflection.toReflectionBindings())
            }
        }
        logger.i(
            "LensRepositoryJvm.generateReflectionCards",
            "Reflection cards generated",
            mapOf("dayKey" to dayKey, "count" to reflections.size),
        )
    }

    /** Emits [dayKey]'s reflection cards, newest first. */
    override fun observeReflections(dayKey: String): Flow<List<LensReflectionRecord>> {
        logger.d("LensRepositoryJvm.observeReflections", "Subscribing to reflections", mapOf("dayKey" to dayKey))
        return JdbcFlows
            .observe(pollIntervalMs) { readReflectionsForDay(session, dayKey) }
            .map { entities -> entities.map { it.toRecord() } }
    }

    /** Marks a reflection addressed and stores the user's [note]. */
    override suspend fun markReflectionAddressed(
        id: String,
        note: String?,
    ) {
        logger.d("LensRepositoryJvm.markReflectionAddressed", "Marking reflection addressed", mapOf("id" to id))
        JdbcFlows.onIo {
            JdbcQueries.write(session, MARK_REFLECTION_ADDRESSED_SQL, arrayOf(note, id))
        }
    }

    /** 0-1 score of how fully [dayKey]'s plan was specified. */
    override suspend fun calculatePlanCompleteness(dayKey: String): Float {
        logger.d(
            "LensRepositoryJvm.calculatePlanCompleteness",
            "Calculating plan completeness",
            mapOf("dayKey" to dayKey),
        )
        return calculateUnifiedSnapshot(dayKey).planning.planCompletenessScore
    }

    /** 0-1 score of how closely [dayKey]'s reality matched its plan. */
    override suspend fun calculateAdherence(dayKey: String): Float {
        logger.d("LensRepositoryJvm.calculateAdherence", "Calculating adherence", mapOf("dayKey" to dayKey))
        return calculateUnifiedSnapshot(dayKey).reality.adherenceScore
    }

    /** Filters [dayKeys] to those still flagged dirty. */
    override suspend fun getDirtyDayKeys(dayKeys: Set<String>): Set<String> {
        logger.d("LensRepositoryJvm.getDirtyDayKeys", "Fetching dirty day keys", mapOf("inputCount" to dayKeys.size))
        return getLensDirtyDayKeysSync(session, dayKeys)
    }

    /** True when [dayKey] still carries a dirty marker. */
    override suspend fun isDayDirty(dayKey: String): Boolean = loadLensDirtyDayMetadataSync(session, dayKey) != null

    // ---- private composition ----

    private suspend fun buildUnifiedSnapshot(dayKey: String): UnifiedLensSnapshot {
        logger.d(
            "LensRepositoryJvm.calculateUnifiedSnapshot",
            "Calculating unified snapshot",
            mapOf("dayKey" to dayKey),
        )
        val date = LocalDate.parse(dayKey)
        val budgetsByDimension = loadBudgetAllocationsByDimension(dayKey)
        val allTasks = taskRepository.getAllTasks().first()
        val tasksDueForReality = allTasks.filter { task -> task.dueDate?.toLocalDate()?.isEqual(date) == true }
        val planning = buildPlanningData(dayKey, date, budgetsByDimension, allTasks)
        val reality =
            buildRealityData(
                dayKey = dayKey,
                date = date,
                budgetsByDimension = budgetsByDimension,
                allTasks = allTasks,
                tasksDueForReality = tasksDueForReality,
                planning = planning,
            )
        logger.i(
            "LensRepositoryJvm.calculateUnifiedSnapshot",
            "Unified snapshot calculated",
            mapOf(
                "dayKey" to dayKey,
                "plannedMinutes" to planning.totalPlannedMinutes,
                "actualMinutes" to reality.totalActualMinutes,
                "planCompleteness" to planning.planCompletenessScore,
                "adherence" to reality.adherenceScore,
            ),
        )
        return UnifiedLensSnapshot(planning = planning, reality = reality)
    }

    private suspend fun loadBudgetAllocationsByDimension(dayKey: String): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        dayPlanRepository.getEffectiveAllocationsForDay(dayKey).forEach { allocation ->
            val normalizedId = canonicalId(allocation.dimensionId)
            result[normalizedId] = (result[normalizedId] ?: 0) + allocation.plannedMinutes
        }
        return result
    }

    private fun buildPlanningData(
        dayKey: String,
        date: LocalDate,
        budgetsByDimension: Map<String, Int>,
        allTasks: List<Task>,
    ): PlanningLensData {
        val tasksDueForPlanning =
            allTasks.filter { task ->
                val taskDueDate = task.dueDate?.toLocalDate()
                taskDueDate != null && taskDueDate.isEqual(date) && task.status != "archived"
            }
        val taskPlanItems =
            tasksDueForPlanning
                .filterNot { it.recurrenceEnabled }
                .map { task ->
                    TaskPlanItem(
                        taskId = task.id,
                        title = task.title,
                        dimensionId = task.dimensionId,
                        estimatedMinutes = task.durationMinutes,
                        dueDate = task.dueDate.toString(),
                        priority = determinePriority(task.taskScore ?: 0.0),
                    )
                }
        val habitPlanItems =
            tasksDueForPlanning
                .filter { it.recurrenceEnabled }
                .map { habit ->
                    HabitPlanItem(
                        habitId = habit.id,
                        title = habit.title,
                        dimensionId = habit.dimensionId,
                        estimatedMinutes = habit.durationMinutes,
                        recurrenceRule = habit.recurrenceRule ?: "",
                    )
                }
        val taskAndHabitMinutes =
            taskPlanItems.sumOf { it.estimatedMinutes } + habitPlanItems.sumOf { it.estimatedMinutes }
        val budgetMinutes = budgetsByDimension.values.sum()
        val totalPlannedMinutes = if (budgetMinutes > 0) budgetMinutes else taskAndHabitMinutes
        val plannedTimeByDim = mutableMapOf<String, Int>()
        (
            taskPlanItems.map { it.dimensionId to it.estimatedMinutes } +
                habitPlanItems.map { it.dimensionId to it.estimatedMinutes }
        ).forEach { (dimensionId, minutes) ->
            dimensionId?.let { rawId ->
                val normalizedId = canonicalId(rawId)
                plannedTimeByDim[normalizedId] = (plannedTimeByDim[normalizedId] ?: 0) + minutes
            }
        }

        return PlanningLensData(
            dayKey = dayKey,
            totalPlannedMinutes = totalPlannedMinutes,
            plannedTimeByDimension = plannedTimeByDim,
            budgetAllocationsByDimension = budgetsByDimension,
            plannedTasks = taskPlanItems,
            plannedHabits = habitPlanItems,
            timeGoals = emptyList(),
            planCompletenessScore =
                computePlanCompletenessScore(
                    totalPlannedMinutes = totalPlannedMinutes,
                    hasBudgetAllocations = budgetsByDimension.isNotEmpty(),
                    plannedTaskCount = taskPlanItems.size,
                    hasPlannedHabits = habitPlanItems.isNotEmpty(),
                ),
        )
    }

    private suspend fun buildRealityData(
        dayKey: String,
        date: LocalDate,
        budgetsByDimension: Map<String, Int>,
        allTasks: List<Task>,
        tasksDueForReality: List<Task>,
        planning: PlanningLensData,
    ): RealityLensData {
        val entriesForDay = timeEntryRepository.getTimeEntriesForDate(date).first()
        val totalEntryMinutes = entriesForDay.sumOf { entry -> boundedMinutes(entry, date) }
        val tasksById = allTasks.associateBy { it.id }
        val tasksWithTrackedEntries = entriesForDay.mapNotNull { it.taskId }.toSet()
        val occurrencesForDay = taskOccurrenceRepository.getOccurrencesForDate(date).first()

        // A habit's minutes come from its time entry when there is one and from its occurrence when there is
        // not. Counting both would double the same work.
        val supplementalHabitMinutesByDim = mutableMapOf<String, Int>()
        occurrencesForDay.forEach { occurrence ->
            val task = tasksById[occurrence.taskId] ?: return@forEach
            if (!task.recurrenceEnabled) return@forEach
            val actualDuration = occurrence.actualDurationMinutes?.coerceAtLeast(0) ?: 0
            if (actualDuration <= 0 || occurrence.taskId in tasksWithTrackedEntries) return@forEach
            val rawDimensionId = task.dimensionId ?: return@forEach
            val dimensionId = canonicalId(rawDimensionId)
            supplementalHabitMinutesByDim[dimensionId] =
                (supplementalHabitMinutesByDim[dimensionId] ?: 0) + actualDuration
        }
        val supplementalHabitMinutes = supplementalHabitMinutesByDim.values.sum()
        val totalActualMinutes = totalEntryMinutes + supplementalHabitMinutes

        var actualTimeOnlyMinutes = 0
        var actualTaskMinutes = 0
        var actualHabitEntryMinutes = 0
        entriesForDay.forEach { entry ->
            val minutes = boundedMinutes(entry, date)
            if (minutes <= 0) return@forEach
            val task = entry.taskId?.let { tasksById[it] }
            when {
                entry.taskId == null -> actualTimeOnlyMinutes += minutes
                task?.recurrenceEnabled == true -> actualHabitEntryMinutes += minutes
                else -> actualTaskMinutes += minutes
            }
        }
        val actualHabitMinutes = actualHabitEntryMinutes + supplementalHabitMinutes
        logger.d(
            "LensRepositoryJvm.buildRealityData",
            "Computed reality time split",
            mapOf(
                "dayKey" to dayKey,
                "entryTotalMinutes" to totalEntryMinutes,
                "supplementalHabitMinutes" to supplementalHabitMinutes,
                "actualTimeOnlyMinutes" to actualTimeOnlyMinutes,
                "actualTaskMinutes" to actualTaskMinutes,
                "actualHabitMinutes" to actualHabitMinutes,
                "totalActualMinutes" to totalActualMinutes,
            ),
        )

        val taskRealityItems =
            tasksDueForReality
                .filterNot { it.recurrenceEnabled }
                .map { task ->
                    val actualMinutes =
                        entriesForDay
                            .filter { it.taskId == task.id }
                            .sumOf { entry -> boundedMinutes(entry, date) }
                    TaskRealityItem(
                        taskId = task.id,
                        title = task.title,
                        dimensionId = task.dimensionId,
                        actualMinutes = if (actualMinutes > 0) actualMinutes else null,
                        completedAt = task.completedAt?.toString(),
                        status = task.status,
                        adherenceGap = if (actualMinutes > 0) task.durationMinutes - actualMinutes else null,
                    )
                }
        val habitRealityItems =
            occurrencesForDay
                .map { occurrence ->
                    val task = tasksById[occurrence.taskId]
                    if (task?.recurrenceEnabled != true) return@map null
                    HabitRealityItem(
                        habitId = occurrence.taskId,
                        title = task.title,
                        dimensionId = task.dimensionId,
                        actualMinutes = occurrence.actualDurationMinutes,
                        completedAt = occurrence.actualCompletedAt?.toString(),
                        status = occurrence.status,
                    )
                }.filterNotNull()
        val plannedFocusMinutes =
            tasksDueForReality.filter { (it.focusRequired ?: 0.0) > FOCUS_THRESHOLD }.sumOf { it.durationMinutes }
        val actualFocusMinutes =
            entriesForDay
                .filter { (it.focusRating ?: 0.0) >= FOCUS_THRESHOLD }
                .sumOf { entry -> boundedMinutes(entry, date) }
        val actualTimeByDim = mutableMapOf<String, Int>()
        entriesForDay.forEach { entry ->
            entry.dimensionId?.let { rawId ->
                val normalizedId = canonicalId(rawId)
                actualTimeByDim[normalizedId] =
                    (actualTimeByDim[normalizedId] ?: 0) + boundedMinutes(entry, date)
            }
        }
        supplementalHabitMinutesByDim.forEach { (dimensionId, minutes) ->
            actualTimeByDim[dimensionId] = (actualTimeByDim[dimensionId] ?: 0) + minutes
        }

        return RealityLensData(
            dayKey = dayKey,
            totalActualMinutes = totalActualMinutes,
            actualTimeByDimension = actualTimeByDim,
            budgetAllocationsByDimension = budgetsByDimension,
            completedTasks = taskRealityItems,
            completedHabits = habitRealityItems,
            untrackedMinutes = MINUTES_PER_DAY - totalActualMinutes,
            focusGapMinutes = maxOf(0, plannedFocusMinutes - actualFocusMinutes),
            adherenceScore =
                calculateAdherence(
                    planning = planning,
                    completedTasks = taskRealityItems,
                    completedHabits = habitRealityItems,
                    totalActualMinutes = totalActualMinutes,
                ),
            supplementalActualMinutes = supplementalHabitMinutes,
            supplementalActualByDimension = supplementalHabitMinutesByDim,
            actualTimeOnlyMinutes = actualTimeOnlyMinutes,
            actualTaskMinutes = actualTaskMinutes,
            actualHabitMinutes = actualHabitMinutes,
        )
    }

    /**
     * Half time, half task completion, a fifth habit completion — 50/30/20.
     *
     * A day with no planned tasks scores full marks on the task component rather than zero, because there was
     * nothing to miss.
     */
    private fun calculateAdherence(
        planning: PlanningLensData,
        completedTasks: List<TaskRealityItem>,
        completedHabits: List<HabitRealityItem>,
        totalActualMinutes: Int,
    ): Float {
        if (planning.totalPlannedMinutes == 0) return 0f
        var score = 0f
        val timeRatio = totalActualMinutes.toFloat() / planning.totalPlannedMinutes
        score += TIME_ADHERENCE_WEIGHT * minOf(timeRatio, 1.0f)
        val totalTasks = planning.plannedTasks.size
        val completedTasksCount = completedTasks.count { it.status == "completed" }
        score +=
            if (totalTasks > 0) {
                TASK_ADHERENCE_WEIGHT * (completedTasksCount.toFloat() / totalTasks)
            } else {
                TASK_ADHERENCE_WEIGHT
            }
        val totalHabits = planning.plannedHabits.size
        val completedHabitsCount = completedHabits.count { it.status == "completed" }
        score +=
            if (totalHabits > 0) {
                HABIT_ADHERENCE_WEIGHT * (completedHabitsCount.toFloat() / totalHabits)
            } else {
                HABIT_ADHERENCE_WEIGHT
            }
        return score
    }

    private fun boundedMinutes(
        entry: io.payanam.domain.model.TimeEntry,
        date: LocalDate,
    ): Int =
        dayBoundedDurationMinutes(
            startedAt = entry.startedAt,
            endedAt = entry.endedAt,
            day = date,
        )

    private fun canonicalId(rawId: String): String = DimensionTaxonomyCatalog.fromCanonicalId(rawId)?.id ?: rawId

    private fun determinePriority(taskScore: Double): String =
        when {
            taskScore >= HIGH_PRIORITY_SCORE -> "High"
            taskScore >= MEDIUM_PRIORITY_SCORE -> "Medium"
            else -> "Low"
        }

    private fun reflectionEntity(
        dayKey: String,
        dimensionId: String?,
        reflectionType: String,
        title: String,
        description: String?,
        gapMinutes: Int?,
        relatedEntityId: String?,
        now: String,
    ): LensReflectionEntity =
        LensReflectionEntity(
            id = UUID.randomUUID().toString(),
            dayKey = dayKey,
            dimensionId = dimensionId,
            reflectionType = reflectionType,
            title = title,
            description = description,
            gapMinutes = gapMinutes,
            relatedEntityId = relatedEntityId,
            // Regenerated cards are always unaddressed: the user addressed the card that was replaced.
            isAddressed = 0,
            userNote = null,
            createdAt = now,
        )

    private companion object {
        const val MINUTES_PER_DAY = 24 * 60
        const val UNTRACKED_REFLECTION_THRESHOLD_MINUTES = 480
        const val FOCUS_GAP_REFLECTION_THRESHOLD_MINUTES = 60
        const val DIMENSION_GAP_REFLECTION_THRESHOLD_MINUTES = 60
        const val FOCUS_THRESHOLD = 0.7
        const val HIGH_PRIORITY_SCORE = 80.0
        const val MEDIUM_PRIORITY_SCORE = 50.0
        const val TIME_ADHERENCE_WEIGHT = 0.5f
        const val TASK_ADHERENCE_WEIGHT = 0.3f
        const val HABIT_ADHERENCE_WEIGHT = 0.2f
    }
}
