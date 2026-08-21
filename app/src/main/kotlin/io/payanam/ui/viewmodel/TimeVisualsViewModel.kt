//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.repository.DayPlanAllocationRecord
import io.payanam.domain.repository.DayPlanRepository
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TimeEntryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
/**
 * Provides the time visuals view model.
 */
class TimeVisualsViewModel @Inject constructor(
    private val timeEntryRepository: TimeEntryRepository,
    private val taskRepository: TaskRepository,
    private val dayPlanRepository: DayPlanRepository,
    private val taskOccurrenceRepository: TaskOccurrenceRepository,
) : ViewModel() {
    private val logger = UnifiedLogger.getInstance()
    private val _uiState = MutableStateFlow(TimeVisualsState())
    val uiState: StateFlow<TimeVisualsState> = _uiState.asStateFlow()
    private val dayCache = mutableMapOf<LocalDate, CachedDayVisual>()
    private var taskLookup: Map<String, Task> = emptyMap()
    private var inFlightDate: LocalDate? = null
    private var loadJob: Job? = null
    /**
     * Loads the load for date.
     */
    fun loadForDate(date: LocalDate) {
        if (inFlightDate == date && loadJob?.isActive == true) {
            logger.d(
                "TimeVisualsViewModel.loadForDate",
                "Skipped duplicate in-flight visuals load",
                mapOf("date" to date.toString()),
            )
            return
        }
        loadJob = viewModelScope.launch {
            inFlightDate = date
            try {
                val startMs = SystemClock.elapsedRealtime()
                _uiState.update { it.copy(isLoading = true, selectedDate = date) }
                invalidateRollingWindow(date)
                val hasLookup = taskLookup.isNotEmpty()
                if (!hasLookup) {
                    val quickToday = getDayVisual(date = date, taskLookupSnapshot = emptyMap(), allowCache = false)
                    val quickYesterday = getDayVisual(date = date.minusDays(1), taskLookupSnapshot = emptyMap(), allowCache = false)
                    val quickRolling = (0..6).map { offset ->
                        getDayVisual(date = date.minusDays(offset.toLong()), taskLookupSnapshot = emptyMap(), allowCache = false)
                            .overall
                            .trackedMinutes
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            selectedDate = date,
                            dayOverall = quickToday.overall,
                            perDimension = quickToday.perDimension,
                            trend = TimeTrendStripSummary(
                                selectedDayMinutes = quickToday.overall.trackedMinutes,
                                previousDayMinutes = quickYesterday.overall.trackedMinutes,
                                last7AverageMinutes = quickRolling.average().toLong(),
                            ),
                        )
                    }
                    logger.d(
                        "TimeVisualsViewModel.loadForDate",
                        "Rendered fast-path visuals before task lookup",
                        mapOf(
                            "date" to date.toString(),
                            "durationMs" to (SystemClock.elapsedRealtime() - startMs).toString(),
                        ),
                    )
                }
                val taskLookupDeferred = async {
                    if (taskLookup.isEmpty()) {
                        taskRepository.getAllTasks().first().associateBy { it.id }
                    } else {
                        taskLookup
                    }
                }
                val resolvedTaskLookup = taskLookupDeferred.await()
                if (resolvedTaskLookup !== taskLookup) {
                    taskLookup = resolvedTaskLookup
                    dayCache.clear()
                }
                val todaySummary = getDayVisual(date)
                val yesterdaySummary = getDayVisual(date.minusDays(1))
                val rolling = (0..6).map { offset -> getDayVisual(date.minusDays(offset.toLong())).overall.trackedMinutes }
                val trend = TimeTrendStripSummary(
                    selectedDayMinutes = todaySummary.overall.trackedMinutes,
                    previousDayMinutes = yesterdaySummary.overall.trackedMinutes,
                    last7AverageMinutes = rolling.average().toLong(),
                )
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        selectedDate = date,
                        dayOverall = todaySummary.overall,
                        perDimension = todaySummary.perDimension,
                        trend = trend,
                    )
                }
                logger.d(
                    "TimeVisualsViewModel.loadForDate",
                    "Rendered full visuals with task lookup",
                    mapOf(
                        "date" to date.toString(),
                        "hasTaskLookup" to (taskLookup.isNotEmpty()).toString(),
                        "durationMs" to (SystemClock.elapsedRealtime() - startMs).toString(),
                    ),
                )
            } finally {
                inFlightDate = null
            }
        }
    }
    /**
     * Performs the toggle dimension filter.
     */
    fun toggleDimensionFilter(dimensionId: String) {
        _uiState.update { state ->
            state.copy(
                selectedDimensionFilterId = if (state.selectedDimensionFilterId == dimensionId) null else dimensionId,
            )
        }
    }

    private suspend fun getDayVisual(
        date: LocalDate,
        taskLookupSnapshot: Map<String, Task> = taskLookup,
        allowCache: Boolean = true,
    ): CachedDayVisual {
        if (allowCache) {
            dayCache[date]?.let { return it }
        }
        val entries = timeEntryRepository.getTimeEntriesForDate(date).first()
        val occurrences = taskOccurrenceRepository.getOccurrencesForDate(date).first()
        val allocations = dayPlanRepository.getEffectiveAllocationsForDay(date.toString())
        val overall = TimeVisualsCalculator.computeDayOverall(selectedDate = date, entries = entries)
        val perDimension = TimeVisualsCalculator.computePerDimension(
            selectedDate = date,
            entries = entries,
            occurrences = occurrences,
            taskLookup = taskLookupSnapshot,
            allocations = allocations,
        )
        val cached = CachedDayVisual(entries, occurrences, allocations, overall, perDimension)
        if (allowCache) {
            dayCache[date] = cached
        }
        logger.d(
            "TimeVisualsViewModel.getDayVisual",
            "Computed day-scoped time visuals with cache",
            mapOf(
                "date" to date.toString(),
                "allowCache" to allowCache.toString(),
                "entries" to entries.size.toString(),
                "occurrences" to occurrences.size.toString(),
                "dimensions" to perDimension.size.toString(),
            ),
        )
        return cached
    }

    private data class CachedDayVisual(
        val entries: List<TimeEntry>,
        val occurrences: List<TaskOccurrence>,
        val allocations: List<DayPlanAllocationRecord>,
        val overall: TimeDayOverallSummary,
        val perDimension: List<TimeDimensionDaySummary>,
    )

    private fun invalidateRollingWindow(date: LocalDate) {
        (0..6).forEach { offset ->
            dayCache.remove(date.minusDays(offset.toLong()))
        }
    }
}
