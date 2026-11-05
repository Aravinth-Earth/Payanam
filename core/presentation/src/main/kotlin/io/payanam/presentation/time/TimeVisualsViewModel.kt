//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.time

import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.repository.DayPlanAllocationRecord
import io.payanam.domain.repository.DayPlanRepository
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TimeEntryRepository
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.platform.elapsedMillis
import io.payanam.presentation.time.TimeDayOverallSummary
import io.payanam.presentation.time.TimeDimensionDaySummary
import io.payanam.presentation.time.TimeTrendStripSummary
import io.payanam.presentation.time.TimeVisualsCalculator
import io.payanam.presentation.time.TimeVisualsState
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Time-screen visuals ViewModel: computes overall/per-dimension day summaries
 * and the 7-day trend strip, with a fast pre-lookup render, day cache, and a
 * toggleable dimension filter.
 */
class TimeVisualsViewModel(
    private val timeEntryRepository: TimeEntryRepository,
    private val taskRepository: TaskRepository,
    private val dayPlanRepository: DayPlanRepository,
    private val taskOccurrenceRepository: TaskOccurrenceRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val logger = AppLogger.current
    private val _uiState = MutableStateFlow(TimeVisualsState())
    val uiState: StateFlow<TimeVisualsState> = _uiState.asStateFlow()
    private val dayCache = mutableMapOf<LocalDate, CachedDayVisual>()
    private var taskLookup: Map<String, Task> = emptyMap()
    private var inFlightDate: LocalDate? = null
    private var loadJob: Job? = null

    /**
     * Loads visuals for [date]: fast render before task lookup, then the full
     * pass with per-dimension rollups and trend figures.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    fun loadForDate(date: LocalDate) {
        if (inFlightDate == date && loadJob?.isActive == true) {
            logger.d(
                "TimeVisualsViewModel.loadForDate",
                "Skipped duplicate in-flight visuals load",
                mapOf("date" to date.toString()),
            )
            return
        }
        loadJob =
            scope.launch {
                inFlightDate = date
                try {
                    val startMs = elapsedMillis()
                    _uiState.update { it.copy(isLoading = true, selectedDate = date) }
                    invalidateRollingWindow(date)
                    val hasLookup = taskLookup.isNotEmpty()
                    if (!hasLookup) {
                        val quickToday = getDayVisual(date = date, taskLookupSnapshot = emptyMap(), allowCache = false)
                        val quickYesterday = getDayVisual(date = date.minusDays(1), taskLookupSnapshot = emptyMap(), allowCache = false)
                        val quickRolling =
                            (0..6).map { offset ->
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
                                trend =
                                    TimeTrendStripSummary(
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
                                "durationMs" to (elapsedMillis() - startMs).toString(),
                            ),
                        )
                    }
                    val taskLookupDeferred =
                        async {
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
                    val trend =
                        TimeTrendStripSummary(
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
                            "durationMs" to (elapsedMillis() - startMs).toString(),
                        ),
                    )
                } catch (e: CancellationException) {
                    // A cancelled load is not a FAILED one: the scope is going away (the window closed, the
                    // ViewModel was disposed). Rethrow so the coroutine still ends as cancelled and the
                    // cancellation is never reported as a load failure.
                    throw e
                } catch (e: Exception) {
                    // WHY THIS CATCH EXISTS: the desktop closes its database session when the window
                    // closes, so a visuals load that is still in flight can read from a closed session and
                    // fail ("PayanamDatabaseSession is closed"). With no catch, that failure escaped the
                    // launched coroutine, reached the JVM-global exception collector and was re-thrown as
                    // UncaughtExceptionsBeforeTest in whichever unrelated test ran later - which is why
                    // the victim test moved from run to run. The failure belongs to THIS load: report it,
                    // and leave the screen usable instead of spinning on a load that can never finish.
                    logger.e(
                        "TimeVisualsViewModel.loadForDate",
                        "Failed to load time visuals",
                        e,
                        mapOf("date" to date.toString()),
                    )
                    _uiState.update { it.copy(isLoading = false) }
                } finally {
                    inFlightDate = null
                }
            }
    }

    /**
     * Toggles the per-dimension filter (re-tap clears it).
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
        val perDimension =
            TimeVisualsCalculator.computePerDimension(
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

    /**
     * Cancels the scope this ViewModel runs on.
     *
     * The platform calls this at the point it drops the ViewModel, so the collectors started here stop with
     * it. Through the default factory the scope is this ViewModel's own, which is the case [dispose] exists
     * for. A caller that passed its own [scope] has that scope cancelled here too - it is the same scope the
     * ViewModel is running on, so it cannot be released selectively - and must not reuse it afterwards.
     */
    fun dispose() {
        scope.cancel()
    }
}

/**
 * The one-line entry point a platform's destination body calls to get the TimeVisuals ViewModel.
 *
 * The repositories come from [LocalDataLayer], which the platform installed at startup with its own
 * implementation. Call sites on both platforms are therefore identical and contain no wiring.
 */
fun timeVisualsViewModel(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)): TimeVisualsViewModel =
    TimeVisualsViewModel(
        timeEntryRepository = LocalDataLayer.current.timeEntries,
        taskRepository = LocalDataLayer.current.tasks,
        dayPlanRepository = LocalDataLayer.current.dayPlans,
        taskOccurrenceRepository = LocalDataLayer.current.taskOccurrences,
        scope = scope,
    )
