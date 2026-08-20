//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:max-line-length", "MagicNumber")


package io.payanam.database.repository

import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.entity.LensReflectionEntity
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.repository.AverageDailyTimeTableData
import io.payanam.domain.repository.logSummary
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

private const val MINUTES_PER_DAY = 24 * 60

/**
 * Implementation of LensRepository for calculating Planning and Reality lens data.
 */
@Suppress("TooManyFunctions")
/**
 * LensRepositoryImpl.
 */
class LensRepositoryImpl
    @Inject
    /** Constructor. */
    constructor(
        private val sessionManager: DatabaseSessionManager,
        private val taskRepository: TaskRepository,
        private val timeEntryRepository: TimeEntryRepository,
        private val taskOccurrenceRepository: TaskOccurrenceRepository,
        private val dayPlanRepository: DayPlanRepository,
    ) : LensRepository {
        private val logger = UnifiedLogger.getInstance()

        override suspend fun getFirstTrackedDate(): LocalDate? {
            logger.d("LensRepository.getFirstTrackedDate", "Fetching first tracked date")
            /** Result. */
            val result =
                /** Time entry repository. */
                timeEntryRepository
                    .getAllTimeEntries()
                    .firstOrNull()
                    ?.minByOrNull { it.startedAt }
                    ?.startedAt
                    ?.toLocalDate()
            logger.d("LensRepository.getFirstTrackedDate", "First tracked date resolved", mapOf("date" to (result?.toString() ?: "null")))
            return result
        }

        override suspend fun getDailyFocusAverages(): List<DailyFocusStat> {
            logger.d("LensRepository.getDailyFocusAverages", "Fetching daily focus averages")
            /** All. */
            val all = timeEntryRepository.getAllTimeEntries().firstOrNull() ?: return emptyList()
            return DailyStatsCalculator.calculateDailyFocusAverages(all)
        }

        override suspend fun getDailyTrackedTimeStats(): List<DailyTrackedTimeStat> {
            logger.d("LensRepository.getDailyTrackedTimeStats", "Fetching daily tracked time stats")
            /** All. */
            val all = timeEntryRepository.getAllTimeEntries().firstOrNull() ?: return emptyList()
            return DailyStatsCalculator.calculateDailyTrackedTimeStats(all)
        }

        override suspend fun getDailyFocusedHoursStats(): List<DailyFocusedHoursStat> {
            logger.d("LensRepository.getDailyFocusedHoursStats", "Fetching daily focused hours stats")
            /** All. */
            val all = timeEntryRepository.getAllTimeEntries().firstOrNull() ?: return emptyList()
            return DailyStatsCalculator.calculateDailyFocusedHoursStats(all)
        }

        override suspend fun getAverageDailyTimeTableData(): AverageDailyTimeTableData? {
            logger.d("LensRepository.getAverageDailyTimeTableData", "Fetching average daily time table")
            /** All. */
            val all = timeEntryRepository.getAllTimeEntries().firstOrNull() ?: return null
            return DailyStatsCalculator.calculateAverageDailyTimeTable(all).also { result ->
                result?.logSummary(logger)
            }
        }

        override suspend fun getDimensionTrendBlocks(windowDays: Int): List<DimensionTrendBlock> {
            /** All entries. */
            val allEntries = timeEntryRepository.getAllTimeEntries().firstOrNull() ?: emptyList()
            return buildDimensionTrendBlocks(allEntries, windowDays, logger)
        }

        override suspend fun getHeatmapDays(): List<HeatmapDayData> {
            /** All entries. */
            val allEntries = timeEntryRepository.getAllTimeEntries().firstOrNull() ?: emptyList()
            return buildHeatmapDays(allEntries, logger)
        }

        override suspend fun getWeekGridData(excludeEmptyDays: Boolean): WeekGridData {
            /** All entries. */
            val allEntries = timeEntryRepository.getAllTimeEntries().firstOrNull() ?: emptyList()
            return buildWeekGridData(allEntries, excludeEmptyDays, logger)
        }

        override suspend fun getMinutePatternData(excludeEmptyDays: Boolean): MinutePatternData {
            /** All entries. */
            val allEntries = timeEntryRepository.getAllTimeEntries().firstOrNull() ?: emptyList()
            return buildMinutePatternData(allEntries, excludeEmptyDays, logger)
        }

        override suspend fun getDimensionSplitForRange(
            /** Start. */
            start: LocalDate,
            /** End. */
            end: LocalDate,
        ): Map<String?, Int> {
            logger.d(
                "LensRepository.getDimensionSplitForRange",
                "Fetching dimension split for range",
                /** Map of. */
                mapOf("start" to start.toString(), "end" to end.toString()),
            )
            /** All. */
            val all = timeEntryRepository.getAllTimeEntries().firstOrNull() ?: return emptyMap()
            return DailyStatsCalculator.calculateDimensionSplit(all, start, end)
        }

        override suspend fun calculateUnifiedSnapshot(dayKey: String): UnifiedLensSnapshot {
            /** Is dirty. */
            val isDirty = loadLensDirtyDayMetadata(sessionManager.requireDatabase().dailyInsightDao(), dayKey) != null
            /** If. */
            if (!isDirty) {
                /** Load snapshot from persistent cache. */
                loadSnapshotFromPersistentCache(sessionManager.requireDatabase().dailyInsightDao(), dayKey)?.let { cached ->
                    logger.d(
                        "LensRepository.calculateUnifiedSnapshot",
                        "Using cached unified snapshot",
                        /** Map of. */
                        mapOf("dayKey" to dayKey),
                    )
                    return cached
                }
            }
            /** Computed. */
            val computed = buildUnifiedSnapshot(dayKey)
            /** Persist snapshot to daily insight cache. */
            persistSnapshotToDailyInsightCache(sessionManager.requireDatabase().dailyInsightDao(), logger, dayKey, computed)
            /** If. */
            if (isDirty) {
                /** Clear lens day dirty. */
                clearLensDayDirty(sessionManager.requireDatabase().dailyInsightDao(), logger, dayKey)
            }
            return computed
        }

        override suspend fun calculatePlanningData(dayKey: String): PlanningLensData {
            logger.d("LensRepository.calculatePlanningData", "Calculating planning data", mapOf("dayKey" to dayKey))
            return calculateUnifiedSnapshot(dayKey).planning
        }

        override fun observePlanningData(dayKey: String): Flow<PlanningLensData> {
            logger.d("LensRepository.observePlanningData", "Subscribing to planning data", mapOf("dayKey" to dayKey))
            // Combine multiple flows and recalculate when any changes
            return taskRepository.getAllTasks().map {
                /** Calculate planning data. */
                calculatePlanningData(dayKey)
            }
        }

        override suspend fun calculateRealityData(dayKey: String): RealityLensData {
            logger.d("LensRepository.calculateRealityData", "Calculating reality data", mapOf("dayKey" to dayKey))
            return calculateUnifiedSnapshot(dayKey).reality
        }

        override fun observeRealityData(dayKey: String): Flow<RealityLensData> {
            logger.d("LensRepository.observeRealityData", "Subscribing to reality data", mapOf("dayKey" to dayKey))
            return timeEntryRepository.getAllTimeEntries().map {
                /** Calculate reality data. */
                calculateRealityData(dayKey)
            }
        }

        override suspend fun generateReflectionCards(dayKey: String) {
            logger.d("LensRepository.generateReflectionCards", "Generating reflection cards", mapOf("dayKey" to dayKey))

            // Clear existing reflections for this day to avoid duplicates
            sessionManager.requireDatabase().lensReflectionDao().deleteReflectionsForDay(dayKey)

            /** Snapshot. */
            val snapshot = calculateUnifiedSnapshot(dayKey)
            /** Planning. */
            val planning = snapshot.planning
            /** Reality. */
            val reality = snapshot.reality
            /** Reflections. */
            val reflections = mutableListOf<LensReflectionEntity>()

            // 1. Untracked time reflection
            /** If. */
            if (reality.untrackedMinutes > 480) { // > 8 hours
                reflections.add(
                    /** Create reflection entity. */
                    createReflectionEntity(
                        dayKey = dayKey,
                        dimensionId = null,
                        reflectionType = "untracked_time",
                        title = "Significant Untracked Time",
                        description = "You have ${reality.untrackedMinutes / 60}h ${reality.untrackedMinutes % 60}m untracked. Consider logging your activities.",
                        gapMinutes = reality.untrackedMinutes,
                        relatedEntityId = null,
                    ),
                )
            }

            // 2. Missed tasks reflections
            reality.completedTasks
                .filter { it.status == "missed" }
                .forEach { task ->
                    reflections.add(
                        /** Create reflection entity. */
                        createReflectionEntity(
                            dayKey = dayKey,
                            dimensionId = task.dimensionId,
                            reflectionType = "missed_task",
                            title = "Missed Task: ${task.title}",
                            description = "This task was due but not completed. Consider rescheduling or marking as skipped.",
                            gapMinutes = null,
                            relatedEntityId = task.taskId,
                        ),
                    )
                }

            // 3. Focus gap reflections
            /** If. */
            if (reality.focusGapMinutes > 60) {
                reflections.add(
                    /** Create reflection entity. */
                    createReflectionEntity(
                        dayKey = dayKey,
                        dimensionId = null,
                        reflectionType = "focus_gap",
                        title = "Focus Gap Detected",
                        description = "You planned ${reality.focusGapMinutes} more minutes of focused work than achieved.",
                        gapMinutes = reality.focusGapMinutes,
                        relatedEntityId = null,
                    ),
                )
            }

            // 4. Dimension-specific gaps (compare against budgets if available, else planned time)
            /** Dimensions to check. */
            val dimensionsToCheck = (planning.plannedTimeByDimension.keys + planning.budgetAllocationsByDimension.keys).toSet()

            dimensionsToCheck.forEach { dimId ->
                /** Actual minutes. */
                val actualMinutes = reality.actualTimeByDimension[dimId] ?: 0
                /** Planned minutes. */
                val plannedMinutes = planning.plannedTimeByDimension[dimId] ?: 0
                /** Budget minutes. */
                val budgetMinutes = planning.budgetAllocationsByDimension[dimId]

                // Prefer budget comparison if budget exists, otherwise use planned time from tasks/habits
                /** Target minutes. */
                val targetMinutes = budgetMinutes ?: plannedMinutes
                /** Gap. */
                val gap = targetMinutes - actualMinutes

                /** If. */
                if (gap > 60 && targetMinutes > 0) { // More than 1 hour gap
                    /** Dimension label. */
                    val dimensionLabel =
                        DimensionTaxonomyCatalog.fromCanonicalId(dimId)?.fallbackLabel
                            ?: "Unknown"
                    /** Gap type. */
                    val gapType = if (budgetMinutes != null) "budget gap" else "planned gap"
                    reflections.add(
                        /** Create reflection entity. */
                        createReflectionEntity(
                            dayKey = dayKey,
                            dimensionId = dimId,
                            reflectionType = "dimension_gap",
                            title = "$dimensionLabel Time Gap",
                            description = "Budget: ${targetMinutes}m, Tracked: ${actualMinutes}m. Gap: ${gap}m ($gapType).",
                            gapMinutes = gap,
                            relatedEntityId = null,
                        ),
                    )
                }
            }

            // Insert all reflections
            sessionManager.requireDatabase().lensReflectionDao().insertReflections(reflections)

            logger.i(
                "LensRepository.generateReflectionCards",
                "Reflection cards generated",
                /** Map of. */
                mapOf(
                    "dayKey" to dayKey,
                    "count" to reflections.size,
                ),
            )
        }

        override fun observeReflections(dayKey: String): Flow<List<LensReflectionRecord>> {
            logger.d("LensRepository.observeReflections", "Subscribing to reflections", mapOf("dayKey" to dayKey))
            return sessionManager.requireDatabase().lensReflectionDao().observeReflectionsForDay(dayKey).map { entities ->
                entities.map { it.toRecord() }
            }
        }

        override suspend fun markReflectionAddressed(
            /** Id. */
            id: String,
            note: String?,
        ) {
            logger.d("LensRepository.markReflectionAddressed", "Marking reflection addressed", mapOf("id" to id))
            sessionManager.requireDatabase().lensReflectionDao().markReflectionAddressed(id, note)
        }

        override suspend fun calculatePlanCompleteness(dayKey: String): Float {
            logger.d("LensRepository.calculatePlanCompleteness", "Calculating plan completeness", mapOf("dayKey" to dayKey))
            return calculateUnifiedSnapshot(dayKey).planning.planCompletenessScore
        }

        override suspend fun calculateAdherence(dayKey: String): Float {
            logger.d("LensRepository.calculateAdherence", "Calculating adherence", mapOf("dayKey" to dayKey))
            return calculateUnifiedSnapshot(dayKey).reality.adherenceScore
        }

        override suspend fun getDirtyDayKeys(dayKeys: Set<String>): Set<String> {
            logger.d("LensRepository.getDirtyDayKeys", "Fetching dirty day keys", mapOf("inputCount" to dayKeys.size))
            return getLensDirtyDayKeys(sessionManager.requireDatabase().dailyInsightDao(), dayKeys)
        }

        override suspend fun isDayDirty(dayKey: String): Boolean =
            /** Load lens dirty day metadata. */
            loadLensDirtyDayMetadata(sessionManager.requireDatabase().dailyInsightDao(), dayKey) != null

        // Private helper methods

        private fun calculatePlanCompletenessInternal(
            /** Total planned minutes. */
            totalPlannedMinutes: Int,
            /** Has budget allocations. */
            hasBudgetAllocations: Boolean,
            plannedTasks: List<TaskPlanItem>,
            plannedHabits: List<HabitPlanItem>,
        ): Float =
            /** Compute plan completeness score. */
            computePlanCompletenessScore(
                totalPlannedMinutes = totalPlannedMinutes,
                hasBudgetAllocations = hasBudgetAllocations,
                plannedTaskCount = plannedTasks.size,
                hasPlannedHabits = plannedHabits.isNotEmpty(),
            )

        private fun calculateAdherenceInternal(
            /** Planning. */
            planning: PlanningLensData,
            completedTasks: List<TaskRealityItem>,
            completedHabits: List<HabitRealityItem>,
            /** Total actual minutes. */
            totalActualMinutes: Int,
        ): Float {
            /** If. */
            if (planning.totalPlannedMinutes == 0) return 0f

            /** Score. */
            var score = 0f

            // Time adherence: 50%
            /** Time ratio. */
            val timeRatio = totalActualMinutes.toFloat() / planning.totalPlannedMinutes
            score += 0.5f * minOf(timeRatio, 1.0f)

            // Task completion: 30%
            /** Total tasks. */
            val totalTasks = planning.plannedTasks.size
            /** Completed tasks count. */
            val completedTasksCount = completedTasks.count { it.status == "completed" }
            score +=
                /** If. */
                if (totalTasks > 0) {
                    0.3f * (completedTasksCount.toFloat() / totalTasks)
                } else {
                    0.3f // No tasks = perfect adherence
                }

            // Habit completion: 20%
            /** Total habits. */
            val totalHabits = planning.plannedHabits.size
            /** Completed habits count. */
            val completedHabitsCount = completedHabits.count { it.status == "completed" }
            score +=
                /** If. */
                if (totalHabits > 0) {
                    0.2f * (completedHabitsCount.toFloat() / totalHabits)
                } else {
                    0.2f // No habits = perfect adherence
                }

            return score
        }

        private fun createReflectionEntity(
            /** Day key. */
            dayKey: String,
            dimensionId: String?,
            /** Reflection type. */
            reflectionType: String,
            /** Title. */
            title: String,
            description: String?,
            gapMinutes: Int?,
            relatedEntityId: String?,
        ): LensReflectionEntity =
            /** Lens reflection entity. */
            LensReflectionEntity(
                id = UUID.randomUUID().toString(),
                dayKey = dayKey,
                dimensionId = dimensionId,
                reflectionType = reflectionType,
                title = title,
                description = description,
                gapMinutes = gapMinutes,
                relatedEntityId = relatedEntityId,
                isAddressed = 0,
                userNote = null,
                createdAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            )

        private suspend fun buildUnifiedSnapshot(dayKey: String): UnifiedLensSnapshot {
            logger.d("LensRepository.calculateUnifiedSnapshot", "Calculating unified snapshot", mapOf("dayKey" to dayKey))
            /** Date. */
            val date = LocalDate.parse(dayKey)
            /** Budgets by dimension. */
            val budgetsByDimension = loadBudgetAllocationsByDimension(dayKey)
            /** All tasks. */
            val allTasks = taskRepository.getAllTasks().firstOrNull() ?: emptyList()
            /** Tasks due for reality. */
            val tasksDueForReality =
                allTasks.filter { task ->
                    task.dueDate?.toLocalDate()?.isEqual(date) == true
                }

            /** Planning. */
            val planning = buildPlanningData(dayKey, date, budgetsByDimension, allTasks)
            /** Reality. */
            val reality =
                /** Build reality data. */
                buildRealityData(
                    dayKey = dayKey,
                    date = date,
                    budgetsByDimension = budgetsByDimension,
                    allTasks = allTasks,
                    tasksDueForReality = tasksDueForReality,
                    planning = planning,
                )

            logger.i(
                "LensRepository.calculateUnifiedSnapshot",
                "Unified snapshot calculated",
                /** Map of. */
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
            /** Result. */
            val result = mutableMapOf<String, Int>()
            dayPlanRepository.getEffectiveAllocationsForDay(dayKey).forEach { alloc ->
                /** Normalized id. */
                val normalizedId = DimensionTaxonomyCatalog.fromCanonicalId(alloc.dimensionId)?.id ?: alloc.dimensionId
                result[normalizedId] = (result[normalizedId] ?: 0) + alloc.plannedMinutes
            }
            return result
        }

        private fun buildPlanningData(
            /** Day key. */
            dayKey: String,
            /** Date. */
            date: LocalDate,
            budgetsByDimension: Map<String, Int>,
            allTasks: List<io.payanam.domain.model.Task>,
        ): PlanningLensData {
            /** Tasks due for planning. */
            val tasksDueForPlanning =
                allTasks.filter { task ->
                    /** Task due date. */
                    val taskDueDate = task.dueDate?.toLocalDate()
                    taskDueDate != null &&
                        taskDueDate.isEqual(date) &&
                        task.status != "archived"
                }
            /** Task plan items. */
            val taskPlanItems =
                /** Tasks due for planning. */
                tasksDueForPlanning
                    .filterNot { it.recurrenceEnabled }
                    .map { task ->
                        /** Task plan item. */
                        TaskPlanItem(
                            taskId = task.id,
                            title = task.title,
                            dimensionId = task.dimensionId,
                            estimatedMinutes = task.durationMinutes,
                            dueDate = task.dueDate.toString(),
                            priority = determinePriority(task.taskScore ?: 0.0),
                        )
                    }
            /** Habit plan items. */
            val habitPlanItems =
                /** Tasks due for planning. */
                tasksDueForPlanning
                    .filter { it.recurrenceEnabled }
                    .map { habit ->
                        /** Habit plan item. */
                        HabitPlanItem(
                            habitId = habit.id,
                            title = habit.title,
                            dimensionId = habit.dimensionId,
                            estimatedMinutes = habit.durationMinutes,
                            recurrenceRule = habit.recurrenceRule ?: "",
                        )
                    }

            /** Task and habit minutes. */
            val taskAndHabitMinutes = taskPlanItems.sumOf { it.estimatedMinutes } + habitPlanItems.sumOf { it.estimatedMinutes }
            /** Budget minutes. */
            val budgetMinutes = budgetsByDimension.values.sum()
            /** Total planned minutes. */
            val totalPlannedMinutes =
                /** If. */
                if (budgetMinutes > 0) {
                    /** Budget minutes. */
                    budgetMinutes
                } else {
                    /** Task and habit minutes. */
                    taskAndHabitMinutes
                }
            /** Planned time by dim. */
            val plannedTimeByDim = mutableMapOf<String, Int>()
            taskPlanItems.forEach { item ->
                item.dimensionId?.let { dimId ->
                    /** Normalized id. */
                    val normalizedId = DimensionTaxonomyCatalog.fromCanonicalId(dimId)?.id ?: dimId
                    plannedTimeByDim[normalizedId] = (plannedTimeByDim[normalizedId] ?: 0) + item.estimatedMinutes
                }
            }
            habitPlanItems.forEach { item ->
                item.dimensionId?.let { dimId ->
                    /** Normalized id. */
                    val normalizedId = DimensionTaxonomyCatalog.fromCanonicalId(dimId)?.id ?: dimId
                    plannedTimeByDim[normalizedId] = (plannedTimeByDim[normalizedId] ?: 0) + item.estimatedMinutes
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
                    /** Calculate plan completeness internal. */
                    calculatePlanCompletenessInternal(
                        totalPlannedMinutes = totalPlannedMinutes,
                        hasBudgetAllocations = budgetsByDimension.isNotEmpty(),
                        plannedTasks = taskPlanItems,
                        plannedHabits = habitPlanItems,
                    ),
            )
        }

        @Suppress("LongMethod", "CyclomaticComplexMethod")
        private suspend fun buildRealityData(
            /** Day key. */
            dayKey: String,
            /** Date. */
            date: LocalDate,
            budgetsByDimension: Map<String, Int>,
            allTasks: List<io.payanam.domain.model.Task>,
            tasksDueForReality: List<io.payanam.domain.model.Task>,
            /** Planning. */
            planning: PlanningLensData,
        ): RealityLensData {
            /** Entries for day. */
            val entriesForDay = timeEntryRepository.getTimeEntriesForDate(date).firstOrNull() ?: emptyList()
            /** Total entry minutes. */
            val totalEntryMinutes =
                entriesForDay.sumOf { entry ->
                    /** Day bounded duration minutes. */
                    dayBoundedDurationMinutes(
                        startedAt = entry.startedAt,
                        endedAt = entry.endedAt,
                        day = date,
                    )
                }
            /** Tasks by id. */
            val tasksById = allTasks.associateBy { it.id }
            /** Tasks with tracked entries. */
            val tasksWithTrackedEntries = entriesForDay.mapNotNull { it.taskId }.toSet()
            /** Occurrences for day. */
            val occurrencesForDay = taskOccurrenceRepository.getOccurrencesForDate(date).firstOrNull() ?: emptyList()
            /** Supplemental habit minutes by dim. */
            val supplementalHabitMinutesByDim = mutableMapOf<String, Int>()
            occurrencesForDay.forEach { occurrence ->
                /** Task. */
                val task = tasksById[occurrence.taskId] ?: return@forEach
                /** If. */
                if (!task.recurrenceEnabled) {
                    return@forEach
                }
                /** Actual duration. */
                val actualDuration = occurrence.actualDurationMinutes?.coerceAtLeast(0) ?: 0
                /** If. */
                if (actualDuration <= 0 || occurrence.taskId in tasksWithTrackedEntries) {
                    return@forEach
                }
                /** Raw dimension id. */
                val rawDimensionId = task.dimensionId ?: return@forEach
                /** Dimension id. */
                val dimensionId = DimensionTaxonomyCatalog.fromCanonicalId(rawDimensionId)?.id ?: rawDimensionId
                supplementalHabitMinutesByDim[dimensionId] =
                    (supplementalHabitMinutesByDim[dimensionId] ?: 0) + actualDuration
            }
            /** Supplemental habit minutes. */
            val supplementalHabitMinutes = supplementalHabitMinutesByDim.values.sum()
            /** Total actual minutes. */
            val totalActualMinutes = totalEntryMinutes + supplementalHabitMinutes
            /** Actual time only minutes. */
            var actualTimeOnlyMinutes = 0
            /** Actual task minutes. */
            var actualTaskMinutes = 0
            /** Actual habit entry minutes. */
            var actualHabitEntryMinutes = 0
            entriesForDay.forEach { entry ->
                /** Minutes. */
                val minutes =
                    /** Day bounded duration minutes. */
                    dayBoundedDurationMinutes(
                        startedAt = entry.startedAt,
                        endedAt = entry.endedAt,
                        day = date,
                    )
                /** If. */
                if (minutes <= 0) {
                    return@forEach
                }
                /** Task. */
                val task = entry.taskId?.let { tasksById[it] }
                when {
                    entry.taskId == null -> actualTimeOnlyMinutes += minutes
                    task?.recurrenceEnabled == true -> actualHabitEntryMinutes += minutes
                    else -> actualTaskMinutes += minutes
                }
            }
            /** Actual habit minutes. */
            val actualHabitMinutes = actualHabitEntryMinutes + supplementalHabitMinutes
            logger.d(
                "LensRepository.buildRealityData",
                "Computed reality time split",
                /** Map of. */
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

            /** Task reality items. */
            val taskRealityItems =
                /** Tasks due for reality. */
                tasksDueForReality
                    .filterNot { it.recurrenceEnabled }
                    .map { task ->
                        /** Actual minutes. */
                        val actualMinutes =
                            /** Entries for day. */
                            entriesForDay
                                .filter { it.taskId == task.id }
                                .sumOf { entry ->
                                    /** Day bounded duration minutes. */
                                    dayBoundedDurationMinutes(
                                        startedAt = entry.startedAt,
                                        endedAt = entry.endedAt,
                                        day = date,
                                    )
                                }.toInt()
                        /** Task reality item. */
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

            /** Habit reality items. */
            val habitRealityItems =
                /** Occurrences for day. */
                occurrencesForDay
                    .map { occurrence ->
                        /** Task. */
                        val task = tasksById[occurrence.taskId]
                        /** If. */
                        if (task?.recurrenceEnabled != true) {
                            return@map null
                        }
                        /** Habit reality item. */
                        HabitRealityItem(
                            habitId = occurrence.taskId,
                            title = task?.title ?: "Unknown",
                            dimensionId = task?.dimensionId,
                            actualMinutes = occurrence.actualDurationMinutes,
                            completedAt = occurrence.actualCompletedAt?.toString(),
                            status = occurrence.status,
                        )
                    }.filterNotNull()

            /** Planned focus minutes. */
            val plannedFocusMinutes =
                /** Tasks due for reality. */
                tasksDueForReality
                    .filter { (it.focusRequired ?: 0.0) > 0.7 }
                    .sumOf { it.durationMinutes }
            /** Actual focus minutes. */
            val actualFocusMinutes =
                /** Entries for day. */
                entriesForDay
                    .filter { (it.focusRating ?: 0.0) >= 0.7 }
                    .sumOf { entry ->
                        /** Day bounded duration minutes. */
                        dayBoundedDurationMinutes(
                            startedAt = entry.startedAt,
                            endedAt = entry.endedAt,
                            day = date,
                        )
                    }.toInt()
            /** Actual time by dim. */
            val actualTimeByDim = mutableMapOf<String, Int>()
            entriesForDay.forEach { entry ->
                entry.dimensionId?.let { dimId ->
                    /** Normalized id. */
                    val normalizedId = DimensionTaxonomyCatalog.fromCanonicalId(dimId)?.id ?: dimId
                    /** Minutes. */
                    val minutes =
                        /** Day bounded duration minutes. */
                        dayBoundedDurationMinutes(
                            startedAt = entry.startedAt,
                            endedAt = entry.endedAt,
                            day = date,
                        )
                    actualTimeByDim[normalizedId] = (actualTimeByDim[normalizedId] ?: 0) + minutes
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
                    /** Calculate adherence internal. */
                    calculateAdherenceInternal(
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
    }

internal fun computePlanCompletenessScore(
    /** Total planned minutes. */
    totalPlannedMinutes: Int,
    /** Has budget allocations. */
    hasBudgetAllocations: Boolean,
    /** Planned task count. */
    plannedTaskCount: Int,
    /** Has planned habits. */
    hasPlannedHabits: Boolean,
): Float {
    /** Structure score. */
    val structureScore =
        /** Compute plan structure score. */
        computePlanStructureScore(
            hasBudgetAllocations = hasBudgetAllocations,
            plannedTaskCount = plannedTaskCount,
            hasPlannedHabits = hasPlannedHabits,
        )
    /** Day coverage score. */
    val dayCoverageScore = computeDayCoverageScore(totalPlannedMinutes)
    /** Return. */
    return (structureScore * dayCoverageScore).coerceIn(0f, 1f)
}

internal fun dayBoundedDurationMinutes(
    /** Started at. */
    startedAt: LocalDateTime,
    endedAt: LocalDateTime?,
    /** Day. */
    day: LocalDate,
    now: LocalDateTime = LocalDateTime.now(),
): Int {
    /** Day start. */
    val dayStart = day.atStartOfDay()
    /** Day end exclusive. */
    val dayEndExclusive = dayStart.plusDays(1)
    /** Effective end. */
    val effectiveEnd = endedAt ?: now
    /** Clipped start. */
    val clippedStart = maxOf(startedAt, dayStart)
    /** Clipped end. */
    val clippedEnd = minOf(effectiveEnd, dayEndExclusive)
    /** If. */
    if (!clippedEnd.isAfter(clippedStart)) {
        return 0
    }
    return Duration
        .between(clippedStart, clippedEnd)
        .toMinutes()
        .toInt()
        .coerceAtLeast(0)
}

private fun determinePriority(taskScore: Double): String =
    when {
        taskScore >= 80.0 -> "High"
        taskScore >= 50.0 -> "Medium"
        else -> "Low"
    }

internal fun computePlanStructureScore(
    /** Has budget allocations. */
    hasBudgetAllocations: Boolean,
    /** Planned task count. */
    plannedTaskCount: Int,
    /** Has planned habits. */
    hasPlannedHabits: Boolean,
): Float {
    /** Score. */
    var score = 0f

    /** If. */
    if (hasBudgetAllocations) {
        score += 0.4f
    }
    /** If. */
    if (plannedTaskCount > 0) {
        score += 0.4f * (plannedTaskCount.coerceAtMost(5) / 5f)
    }
    /** If. */
    if (hasPlannedHabits) {
        score += 0.2f
    }
    return score
}

internal fun computeDayCoverageScore(totalPlannedMinutes: Int): Float {
    /** If. */
    if (totalPlannedMinutes <= 0) return 0f
    /** Deviation minutes. */
    val deviationMinutes = kotlin.math.abs(totalPlannedMinutes - MINUTES_PER_DAY).toFloat()
    /** Return. */
    return (1f - (deviationMinutes / MINUTES_PER_DAY)).coerceIn(0f, 1f)
}
