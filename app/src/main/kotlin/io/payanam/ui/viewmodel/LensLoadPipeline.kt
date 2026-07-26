//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.repository.LensReflectionRecord
import io.payanam.domain.repository.LensRepository
import io.payanam.domain.repository.PlanningLensData
import io.payanam.domain.repository.RealityLensData
import io.payanam.domain.repository.UnifiedLensSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val UNASSIGNED_DIMENSION_KEY = "unassigned"
private const val LENS_MAX_RANGE_DAYS = 730
private const val LENS_DAY_MINUTES = 24 * 60
private val lensLoadPipelineLogger = UnifiedLogger.getInstance()

internal data class LensPreparedLoadData(
    val planningData: PlanningLensData,
    val realityData: RealityLensData,
    val selectedRangeSummary: LensRangeSummary,
    val timeModuleHistorySummary: TimeModuleHistorySummary?,
    val reflections: List<LensReflectionRecord>,
    val rangeSnapshotsByDay: MutableMap<String, UnifiedLensSnapshot>,
)

internal suspend fun prepareLensLoadData(
    lensRepository: LensRepository,
    snapshotCache: LensSnapshotCache,
    resolvedRange: ResolvedLensWindowRange,
    focusDate: LocalDate,
    dayKey: String,
    fastHistoryDays: Int,
    loadTimeHistorySummary: Boolean,
): LensPreparedLoadData {
    val rangeDates = buildLensDatesForRange(
        rangeStartDate = resolvedRange.startDate,
        rangeEndDate = resolvedRange.endDate,
        maxRangeDays = LENS_MAX_RANGE_DAYS,
    )
    val rangeSnapshotsByDay = withContext(Dispatchers.IO) {
        snapshotCache.loadForDays(
            dayKeys = rangeDates.map { it.format(DateTimeFormatter.ISO_LOCAL_DATE) },
            seededDataByDay = emptyMap(),
        ).toMutableMap()
    }
    val snapshot = rangeSnapshotsByDay[dayKey] ?: withContext(Dispatchers.IO) {
        snapshotCache.getOrLoad(dayKey, rangeSnapshotsByDay).also {
            rangeSnapshotsByDay[dayKey] = it
        }
    }

    val selectedRangeSummary = withContext(Dispatchers.Default) {
        buildRangeSummaryForSnapshots(
            resolvedRange = resolvedRange,
            dates = rangeDates,
            daySnapshotsByDay = rangeSnapshotsByDay,
        )
    }
    val timeModuleHistorySummary = if (loadTimeHistorySummary) {
        withContext(Dispatchers.Default) {
            buildTimeModuleHistorySummary(
                lensRepository = lensRepository,
                focusDate = focusDate,
                seededDataByDay = rangeSnapshotsByDay,
                historyDayLimit = fastHistoryDays,
            )
        }
    } else {
        null
    }
    val reflections: List<LensReflectionRecord> = if (resolvedRange.mode == LensTimeMode.FUTURE) {
        emptyList()
    } else {
        withContext(Dispatchers.IO) {
            lensRepository.observeReflections(dayKey).firstOrNull() ?: emptyList()
        }
    }
    lensLoadPipelineLogger.d(
        "LensLoadPipeline.prepareLensLoadData",
        "Prepared lens load payload",
        mapOf(
            "dayKey" to dayKey,
            "rangeDays" to rangeDates.size,
            "reflectionCount" to reflections.size,
            "hasHistory" to (timeModuleHistorySummary != null),
        ),
    )

    return LensPreparedLoadData(
        planningData = snapshot.planning,
        realityData = snapshot.reality,
        selectedRangeSummary = selectedRangeSummary,
        timeModuleHistorySummary = timeModuleHistorySummary,
        reflections = reflections,
        rangeSnapshotsByDay = rangeSnapshotsByDay,
    )
}

internal fun buildRangeSummaryForSnapshots(
    resolvedRange: ResolvedLensWindowRange,
    dates: List<LocalDate>,
    daySnapshotsByDay: Map<String, UnifiedLensSnapshot>,
): LensRangeSummary {
    val planning = mutableListOf<PlanningLensData>()
    val reality = mutableListOf<RealityLensData>()
    dates.forEach { date ->
        val dayKey = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val daySnapshot = daySnapshotsByDay[dayKey] ?: return@forEach
        planning.add(daySnapshot.planning)
        reality.add(daySnapshot.reality)
    }

    val plannedByDimension = mutableMapOf<String, Int>()
    val actualByDimension = mutableMapOf<String, Int>()
    val supplementalActualByDimension = mutableMapOf<String, Int>()
    val plannedTasksByDimension = mutableMapOf<String, Int>()
    val completedTasksByDimension = mutableMapOf<String, Int>()
    val missedTasksByDimension = mutableMapOf<String, Int>()
    val plannedHabitsByDimension = mutableMapOf<String, Int>()
    val completedHabitsByDimension = mutableMapOf<String, Int>()
    val missedHabitsByDimension = mutableMapOf<String, Int>()
    var plannedTaskMinutes = 0
    var plannedHabitMinutes = 0
    var actualTimeOnlyMinutes = 0
    var actualTaskMinutes = 0
    var actualHabitMinutes = 0

    planning.forEach { data ->
        data.budgetAllocationsByDimension.forEach { (dimensionId, minutes) ->
            plannedByDimension[dimensionId] = (plannedByDimension[dimensionId] ?: 0) + minutes
        }
        data.plannedTasks.forEach { item ->
            val dimensionKey = item.dimensionId ?: UNASSIGNED_DIMENSION_KEY
            plannedTasksByDimension[dimensionKey] = (plannedTasksByDimension[dimensionKey] ?: 0) + 1
            plannedTaskMinutes += item.estimatedMinutes
        }
        data.plannedHabits.forEach { item ->
            val dimensionKey = item.dimensionId ?: UNASSIGNED_DIMENSION_KEY
            plannedHabitsByDimension[dimensionKey] = (plannedHabitsByDimension[dimensionKey] ?: 0) + 1
            plannedHabitMinutes += item.estimatedMinutes
        }
    }
    reality.forEach { data ->
        data.actualTimeByDimension.forEach { (dimensionId, minutes) ->
            actualByDimension[dimensionId] = (actualByDimension[dimensionId] ?: 0) + minutes
        }
        data.supplementalActualByDimension.forEach { (dimensionId, minutes) ->
            supplementalActualByDimension[dimensionId] = (supplementalActualByDimension[dimensionId] ?: 0) + minutes
        }
        actualTimeOnlyMinutes += data.actualTimeOnlyMinutes
        actualTaskMinutes += data.actualTaskMinutes
        actualHabitMinutes += data.actualHabitMinutes
        data.completedTasks.forEach { item ->
            val dimensionKey = item.dimensionId ?: UNASSIGNED_DIMENSION_KEY
            when (item.status) {
                "completed" -> completedTasksByDimension[dimensionKey] = (completedTasksByDimension[dimensionKey] ?: 0) + 1
                "missed" -> missedTasksByDimension[dimensionKey] = (missedTasksByDimension[dimensionKey] ?: 0) + 1
            }
        }
        data.completedHabits.forEach { item ->
            val dimensionKey = item.dimensionId ?: UNASSIGNED_DIMENSION_KEY
            when (item.status) {
                "completed" -> completedHabitsByDimension[dimensionKey] = (completedHabitsByDimension[dimensionKey] ?: 0) + 1
                "missed" -> missedHabitsByDimension[dimensionKey] = (missedHabitsByDimension[dimensionKey] ?: 0) + 1
            }
        }
    }

    val trendPoints = dates.indices.map { index ->
        val plan = planning[index]
        val real = reality[index]
        LensTrendPoint(
            dayKey = dates[index].format(DateTimeFormatter.ISO_LOCAL_DATE),
            plannedMinutes = plan.totalPlannedMinutes,
            actualMinutes = real.totalActualMinutes,
        )
    }

    val summary = LensRangeSummary(
        mode = resolvedRange.mode,
        window = resolvedRange.window,
        pageIndex = resolvedRange.pageIndex,
        startDate = dates.first(),
        endDate = dates.last(),
        totalPlannedMinutes = planning.sumOf { it.totalPlannedMinutes },
        totalActualMinutes = reality.sumOf { it.totalActualMinutes },
        totalUntrackedMinutes = reality.sumOf { it.untrackedMinutes },
        totalFocusGapMinutes = reality.sumOf { it.focusGapMinutes },
        supplementalActualMinutes = reality.sumOf { it.supplementalActualMinutes },
        plannedTaskMinutes = plannedTaskMinutes,
        plannedHabitMinutes = plannedHabitMinutes,
        plannedTimeOnlyMinutes = (planning.sumOf { it.totalPlannedMinutes } - plannedTaskMinutes - plannedHabitMinutes).coerceAtLeast(0),
        unplannedDayMinutes = (dates.size * LENS_DAY_MINUTES - planning.sumOf { it.totalPlannedMinutes }).coerceAtLeast(0),
        actualTimeOnlyMinutes = actualTimeOnlyMinutes,
        actualTaskMinutes = actualTaskMinutes,
        actualHabitMinutes = actualHabitMinutes,
        plannedTaskCount = planning.sumOf { it.plannedTasks.size },
        completedTaskCount = reality.sumOf { day -> day.completedTasks.count { it.status == "completed" } },
        missedTaskCount = reality.sumOf { day -> day.completedTasks.count { it.status == "missed" } },
        plannedHabitCount = planning.sumOf { it.plannedHabits.size },
        completedHabitCount = reality.sumOf { day -> day.completedHabits.count { it.status == "completed" } },
        missedHabitCount = reality.sumOf { day -> day.completedHabits.count { it.status == "missed" } },
        averagePlanCompleteness = if (planning.isEmpty()) 0f else planning.map { it.planCompletenessScore }.average().toFloat(),
        averageAdherence = if (reality.isEmpty()) 0f else reality.map { it.adherenceScore }.average().toFloat(),
        plannedByDimension = plannedByDimension,
        actualByDimension = actualByDimension,
        supplementalActualByDimension = supplementalActualByDimension,
        plannedTasksByDimension = plannedTasksByDimension,
        completedTasksByDimension = completedTasksByDimension,
        missedTasksByDimension = missedTasksByDimension,
        plannedHabitsByDimension = plannedHabitsByDimension,
        completedHabitsByDimension = completedHabitsByDimension,
        missedHabitsByDimension = missedHabitsByDimension,
        trendPoints = trendPoints,
    )
    lensLoadPipelineLogger.d(
        "LensLoadPipeline.buildRangeSummaryForSnapshots",
        "Range summary built",
        mapOf(
            "mode" to resolvedRange.mode.name,
            "window" to resolvedRange.window.name,
            "days" to dates.size,
            "plannedMinutes" to summary.totalPlannedMinutes,
            "actualMinutes" to summary.totalActualMinutes,
            "completedTasks" to summary.completedTaskCount,
            "missedTasks" to summary.missedTaskCount,
            "plannedHabits" to summary.plannedHabitCount,
            "completedHabits" to summary.completedHabitCount,
            "missedHabits" to summary.missedHabitCount,
        ),
    )
    return summary
}
