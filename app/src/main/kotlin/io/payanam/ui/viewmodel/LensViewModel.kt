//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber", "UndocumentedPublicProperty")

package io.payanam.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.payanam.FeatureFlags
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.repository.AverageDailyTimeTableData
import io.payanam.domain.repository.DailyFocusStat
import io.payanam.domain.repository.DailyFocusedHoursStat
import io.payanam.domain.repository.DailyTrackedTimeStat
import io.payanam.domain.repository.DimensionTrendBlock
import io.payanam.domain.repository.HeatmapDayData
import io.payanam.domain.repository.LensReflectionRecord
import io.payanam.domain.repository.LensRepository
import io.payanam.domain.repository.MinutePatternData
import io.payanam.domain.repository.PlanningLensData
import io.payanam.domain.repository.RealityLensData
import io.payanam.domain.repository.UnifiedLensSnapshot
import io.payanam.domain.repository.WeekGridData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

private const val MAX_RANGE_DAYS = 730
private const val FAST_HISTORY_DAYS = 7
private const val INITIAL_BACKFILL_STAGE_LIMIT = 14
private const val HISTORY_PREFETCH_COOLDOWN_MS = 1200L

private data class LensBackfillContext(
    val focusDate: LocalDate,
    val resolvedRange: ResolvedLensWindowRange,
    val rangeSnapshotsByDay: Map<String, UnifiedLensSnapshot>,
)

/**
 * ViewModel for the unified Lens workspace.
 * Manages shared planning/execution data, reflections, and range summaries.
 */
@HiltViewModel
/**
 * LensViewModel.
 */
class LensViewModel @Inject constructor(
    private val lensRepository: LensRepository,
) : ViewModel() {

    private val logger = UnifiedLogger.getInstance()
    private var lensLoadJob: Job? = null
    private var reflectionRefreshJob: Job? = null
    private val snapshotCache = LensSnapshotCache(lensRepository, logger)
    private val historyBackfill = LensHistoryBackfillCoordinator(logger)
    private var backfillContext: LensBackfillContext? = null
    private var pendingHistoryLimit: Int? = null
    private var lastHistoryPrefetchAtMs: Long = 0L
    private var timeHistoryChartsEnabled: Boolean = false
    private var chartLoadJob: Job? = null

    private val _uiState = MutableStateFlow(LensUiState())
    val uiState: StateFlow<LensUiState> = _uiState.asStateFlow()

    init {
        logger.i("LensViewModel.init", "Initializing LensViewModel")
        loadLensData()
    }

    /**
     * Select date.
     */
    fun selectDate(date: LocalDate) {
        logger.d("LensViewModel.selectDate", "Selecting date", mapOf("date" to date.toString()))
        _uiState.update { state ->
            val navigationState = navigationStateForSelection(
                mode = state.selectedTimeMode,
                window = state.selectedTimeWindow,
                pageIndex = 0,
            )
            state.copy(
                selectedDate = date,
                windowPageIndex = 0,
                canGoToPreviousWindowPage = navigationState.canGoPrevious,
                canGoToNextWindowPage = navigationState.canGoNext,
                isLoading = true,
            )
        }
        loadLensData()
    }

    /**
     * Select moment.
     */
    fun selectMoment(moment: LensMoment) {
        logger.d("LensViewModel.selectMoment", "Selecting moment", mapOf("moment" to moment.name))
        _uiState.update { it.copy(selectedMoment = moment) }
    }

    /**
     * Select grouping.
     */
    fun selectGrouping(grouping: LensGrouping) {
        if (_uiState.value.selectedGrouping == grouping) return
        logger.d("LensViewModel.selectGrouping", "Selecting grouping", mapOf("grouping" to grouping.name))
        _uiState.update { it.copy(selectedGrouping = grouping) }
    }

    /**
     * Select time mode.
     */
    fun selectTimeMode(mode: LensTimeMode) {
        val defaultWindow = defaultWindowForMode(mode)
        val currentState = _uiState.value
        if (
            currentState.selectedTimeMode == mode &&
            currentState.selectedTimeWindow == defaultWindow &&
            currentState.windowPageIndex == 0
        ) {
            return
        }
        logger.d("LensViewModel.selectTimeMode", "Selecting time mode", mapOf("mode" to mode.name))
        val navigationState = navigationStateForSelection(mode = mode, window = defaultWindow, pageIndex = 0)
        _uiState.update {
            it.copy(
                selectedTimeMode = mode,
                selectedTimeWindow = defaultWindow,
                windowPageIndex = 0,
                canGoToPreviousWindowPage = navigationState.canGoPrevious,
                canGoToNextWindowPage = navigationState.canGoNext,
                isLoading = true,
            )
        }
        loadLensData()
    }

    /**
     * Select time window.
     */
    fun selectTimeWindow(window: LensTimeWindow) {
        val mode = _uiState.value.selectedTimeMode
        if (!isWindowAllowedForMode(window, mode)) {
            logger.w(
                "LensViewModel.selectTimeWindow",
                "Ignoring unsupported window for mode",
                mapOf("mode" to mode.name, "window" to window.name),
            )
            return
        }
        val currentState = _uiState.value
        if (currentState.selectedTimeWindow == window && currentState.windowPageIndex == 0) {
            return
        }
        logger.d("LensViewModel.selectTimeWindow", "Selecting time window", mapOf("window" to window.name))
        val navigationState = navigationStateForSelection(mode = mode, window = window, pageIndex = 0)
        _uiState.update {
            it.copy(
                selectedTimeWindow = window,
                windowPageIndex = 0,
                canGoToPreviousWindowPage = navigationState.canGoPrevious,
                canGoToNextWindowPage = navigationState.canGoNext,
                isLoading = true,
            )
        }
        loadLensData()
    }

    /**
     * Go to previous window page.
     */
    fun goToPreviousWindowPage() {
        val state = _uiState.value
        if (!state.canGoToPreviousWindowPage) return
        val newPageIndex = when (state.selectedTimeMode) {
            LensTimeMode.TODAY -> 0

            LensTimeMode.PAST -> {
                if (state.selectedTimeWindow == LensTimeWindow.ALL_PAST_DAYS) 0 else state.windowPageIndex + 1
            }

            LensTimeMode.FUTURE -> (state.windowPageIndex - 1).coerceAtLeast(0)
        }
        val navigationState = navigationStateForSelection(
            mode = state.selectedTimeMode,
            window = state.selectedTimeWindow,
            pageIndex = newPageIndex,
        )
        _uiState.update {
            it.copy(
                windowPageIndex = newPageIndex,
                canGoToPreviousWindowPage = navigationState.canGoPrevious,
                canGoToNextWindowPage = navigationState.canGoNext,
                isLoading = true,
            )
        }
        loadLensData()
    }

    /**
     * Go to next window page.
     */
    fun goToNextWindowPage() {
        val state = _uiState.value
        if (!state.canGoToNextWindowPage) return
        val newPageIndex = when (state.selectedTimeMode) {
            LensTimeMode.TODAY -> 0
            LensTimeMode.PAST -> (state.windowPageIndex - 1).coerceAtLeast(0)
            LensTimeMode.FUTURE -> state.windowPageIndex + 1
        }
        val navigationState = navigationStateForSelection(
            mode = state.selectedTimeMode,
            window = state.selectedTimeWindow,
            pageIndex = newPageIndex,
        )
        _uiState.update {
            it.copy(
                windowPageIndex = newPageIndex,
                canGoToPreviousWindowPage = navigationState.canGoPrevious,
                canGoToNextWindowPage = navigationState.canGoNext,
                isLoading = true,
            )
        }
        loadLensData()
    }

    /**
     * Mark reflection addressed.
     */
    fun markReflectionAddressed(reflectionId: String, note: String?) {
        logger.d("LensViewModel.markReflectionAddressed", "Marking reflection addressed", mapOf("id" to reflectionId))
        viewModelScope.launch {
            try {
                lensRepository.markReflectionAddressed(reflectionId, note)
                loadLensData()
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("LensViewModel.markReflectionAddressed", "Failed to mark reflection addressed", e)
                _uiState.update {
                    it.copy(
                        hasError = true,
                        errorMessage = "Failed to update reflection: ${e.message}",
                    )
                }
            }
        }
    }

    /**
     * Regenerate reflections.
     */
    fun regenerateReflections() {
        logger.d("LensViewModel.regenerateReflections", "Regenerating reflections")
        viewModelScope.launch {
            try {
                val dayKey = _uiState.value.selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
                lensRepository.generateReflectionCards(dayKey)
                loadLensData()
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("LensViewModel.regenerateReflections", "Failed to regenerate reflections", e)
                _uiState.update {
                    it.copy(
                        hasError = true,
                        errorMessage = "Failed to regenerate reflections: ${e.message}",
                    )
                }
            }
        }
    }

    /**
     * Request next time history stage.
     */
    fun requestNextTimeHistoryStage() {
        if (!timeHistoryChartsEnabled) return
        if (FeatureFlags.minimalModeEnabled) return
        val context = backfillContext ?: return
        val currentDays = _uiState.value.timeModuleHistorySummary?.totalDays ?: FAST_HISTORY_DAYS
        val nextLimit = historyBackfill.nextLimitAfter(currentDays) ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastHistoryPrefetchAtMs < HISTORY_PREFETCH_COOLDOWN_MS) {
            return
        }
        val pendingLimit = pendingHistoryLimit
        if (pendingLimit != null && nextLimit <= pendingLimit) {
            return
        }
        lastHistoryPrefetchAtMs = now
        pendingHistoryLimit = nextLimit
        scheduleHistoryBackfill(context = context, maxHistoryLimit = nextLimit)
    }

    /**
     * Load enabled charts sequentially.
     */
    fun loadEnabledChartsSequentially(
        chartTimeModuleEnabled: Boolean,
        chartTimeScoreCardsEnabled: Boolean,
        chartTimeOverallScoreCardEnabled: Boolean,
        chartTimeDimensionScoreCardsEnabled: Boolean,
        chartTimeLineGraphsEnabled: Boolean,
        chartTimeDailyScoreTrendEnabled: Boolean,
        chartTimeProgressTrendEnabled: Boolean,
        chartTimeHistoricalRankingEnabled: Boolean,
        chartTimeMomentumStreakEnabled: Boolean,
        chartDimSplitEnabled: Boolean,
        chartAverageDailyTimeEnabled: Boolean,
        chartDimTrendEnabled: Boolean,
        chartDailyTimelineEnabled: Boolean,
        chartWeeklyPatternEnabled: Boolean,
        chartWeeklyPatternExclEmpty: Boolean,
        chartDailyRhythmEnabled: Boolean,
        chartDailyRhythmExclEmpty: Boolean,
    ) {
        chartLoadJob?.cancel()
        chartLoadJob = viewModelScope.launch {
            timeHistoryChartsEnabled =
                chartTimeModuleEnabled &&
                    (
                        (chartTimeScoreCardsEnabled && (chartTimeOverallScoreCardEnabled || chartTimeDimensionScoreCardsEnabled)) ||
                            (chartTimeLineGraphsEnabled && (chartTimeDailyScoreTrendEnabled || chartTimeProgressTrendEnabled || chartTimeHistoricalRankingEnabled || chartTimeMomentumStreakEnabled))
                        )
            logger.d(
                "LensViewModel.loadEnabledChartsSequentially",
                "Loading enabled insight charts",
                mapOf(
                    "timeModuleEnabled" to chartTimeModuleEnabled,
                    "timeScoreCardsEnabled" to chartTimeScoreCardsEnabled,
                    "timeOverallScoreCardEnabled" to chartTimeOverallScoreCardEnabled,
                    "timeDimensionScoreCardsEnabled" to chartTimeDimensionScoreCardsEnabled,
                    "timeLineGraphsEnabled" to chartTimeLineGraphsEnabled,
                    "timeDailyScoreTrendEnabled" to chartTimeDailyScoreTrendEnabled,
                    "timeProgressTrendEnabled" to chartTimeProgressTrendEnabled,
                    "timeHistoricalRankingEnabled" to chartTimeHistoricalRankingEnabled,
                    "timeMomentumStreakEnabled" to chartTimeMomentumStreakEnabled,
                    "dimSplitEnabled" to chartDimSplitEnabled,
                    "averageDailyTimeEnabled" to chartAverageDailyTimeEnabled,
                    "dimTrendEnabled" to chartDimTrendEnabled,
                    "dailyTimelineEnabled" to chartDailyTimelineEnabled,
                    "weeklyPatternEnabled" to chartWeeklyPatternEnabled,
                    "dailyRhythmEnabled" to chartDailyRhythmEnabled,
                    "timeHistoryChartsEnabled" to timeHistoryChartsEnabled,
                ),
            )
            if (!timeHistoryChartsEnabled) {
                historyBackfill.cancel()
                _uiState.update { it.copy(timeModuleHistorySummary = null) }
            } else if (_uiState.value.timeModuleHistorySummary == null) {
                loadTimeModuleHistoryInternal()
                backfillContext?.let { context ->
                    scheduleHistoryBackfill(
                        context = context,
                        maxHistoryLimit = INITIAL_BACKFILL_STAGE_LIMIT,
                    )
                }
            }
            if (chartTimeModuleEnabled && chartDimSplitEnabled) {
                loadDimensionSplitInternal()
            }
            if (chartTimeModuleEnabled && chartAverageDailyTimeEnabled) {
                if (_uiState.value.averageDailyTimeTable == null) {
                    loadAverageDailyTimeInternal()
                } else {
                    logger.d(
                        "LensViewModel.loadEnabledChartsSequentially",
                        "Reusing cached average daily time table",
                        mapOf(
                            "days" to (_uiState.value.averageDailyTimeTable?.totalCalendarDays ?: 0),
                            "rows" to (_uiState.value.averageDailyTimeTable?.rows?.size ?: 0),
                        ),
                    )
                }
            } else if (!chartTimeModuleEnabled || !chartAverageDailyTimeEnabled) {
                _uiState.update { it.copy(averageDailyTimeTable = null) }
            }
            if (chartTimeModuleEnabled && chartDimTrendEnabled) loadDimensionTrendInternal()
            if (chartTimeModuleEnabled && chartDailyTimelineEnabled) loadHeatmapInternal()
            if (chartTimeModuleEnabled && chartWeeklyPatternEnabled) loadWeekGridInternal(chartWeeklyPatternExclEmpty)
            if (chartTimeModuleEnabled && chartDailyRhythmEnabled) loadMinutePatternInternal(chartDailyRhythmExclEmpty)
        }
    }

    /**
     * Load lens data.
     */
    fun loadLensData() {
        lensLoadJob?.cancel()
        reflectionRefreshJob?.cancel()
        historyBackfill.cancel()
        lensLoadJob = viewModelScope.launch {
            try {
                executeLensDataLoad()
            } catch (_: CancellationException) {
                logger.d("LensViewModel.loadLensData", "Previous lens collector cancelled")
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("LensViewModel.loadLensData", "Failed to load lens data", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        hasError = true,
                        errorMessage = "Failed to load lens data: ${e.message}",
                    )
                }
            }
        }
    }

    private suspend fun executeLensDataLoad() {
                val state = _uiState.value
                val resolvedRange = resolveDateRange(
                    anchorDate = state.selectedDate,
                    mode = state.selectedTimeMode,
                    window = state.selectedTimeWindow,
                    pageIndex = state.windowPageIndex,
                )
                val focusDate = focusDateForRange(resolvedRange)
                val dayKey = focusDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
                logger.d(
                    "LensViewModel.loadLensData",
                    "Loading lens data",
                    mapOf(
                        "dayKey" to dayKey,
                        "mode" to resolvedRange.mode.name,
                        "window" to resolvedRange.window.name,
                        "pageIndex" to resolvedRange.pageIndex,
                        "startDate" to resolvedRange.startDate.toString(),
                        "endDate" to resolvedRange.endDate.toString(),
                    ),
                )
                val preparedLoadData = prepareLensLoadData(
                    lensRepository = lensRepository,
                    snapshotCache = snapshotCache,
                    resolvedRange = resolvedRange,
                    focusDate = focusDate,
                    dayKey = dayKey,
                    fastHistoryDays = FAST_HISTORY_DAYS,
                    loadTimeHistorySummary = timeHistoryChartsEnabled,
                )
                val navigationState = navigationStateForSelection(
                    mode = resolvedRange.mode,
                    window = resolvedRange.window,
                    pageIndex = resolvedRange.pageIndex,
                )
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        hasError = false,
                        errorMessage = null,
                        selectedTimeMode = resolvedRange.mode,
                        selectedTimeWindow = resolvedRange.window,
                        windowPageIndex = resolvedRange.pageIndex,
                        canGoToPreviousWindowPage = navigationState.canGoPrevious,
                        canGoToNextWindowPage = navigationState.canGoNext,
                        planningData = preparedLoadData.planningData,
                        realityData = preparedLoadData.realityData,
                        reflections = preparedLoadData.reflections,
                        planCompletenessScore = preparedLoadData.planningData.planCompletenessScore,
                        adherenceScore = preparedLoadData.realityData.adherenceScore,
                        selectedRangeSummary = preparedLoadData.selectedRangeSummary,
                        timeModuleHistorySummary = preparedLoadData.timeModuleHistorySummary,
                        longHorizonSummaries = emptyList(),
                    )
                }
                logger.d(
                    "LensViewModel.loadLensData",
                    "Lens data updated",
                    mapOf(
                        "dayKey" to dayKey,
                        "planCompleteness" to preparedLoadData.planningData.planCompletenessScore,
                        "adherence" to preparedLoadData.realityData.adherenceScore,
                        "reflectionsCount" to preparedLoadData.reflections.size,
                        "mode" to resolvedRange.mode.name,
                        "window" to resolvedRange.window.name,
                        "pageIndex" to resolvedRange.pageIndex,
                    ),
                )
                backfillContext = LensBackfillContext(
                    focusDate = focusDate,
                    resolvedRange = resolvedRange,
                    rangeSnapshotsByDay = preparedLoadData.rangeSnapshotsByDay,
                )
                lastHistoryPrefetchAtMs = 0L
                pendingHistoryLimit = if (timeHistoryChartsEnabled && INITIAL_BACKFILL_STAGE_LIMIT > FAST_HISTORY_DAYS) INITIAL_BACKFILL_STAGE_LIMIT else null
                if (FeatureFlags.minimalModeEnabled) {
                    loadMinimalFocusAverages()
                } else {
                    if (timeHistoryChartsEnabled) {
                        scheduleHistoryBackfill(
                            context = backfillContext ?: return,
                            maxHistoryLimit = INITIAL_BACKFILL_STAGE_LIMIT,
                        )
                    }
                    if (resolvedRange.mode != LensTimeMode.FUTURE) {
                        refreshReflections(dayKey)
                    }
                }
    }

    private fun loadMinimalFocusAverages() {
        viewModelScope.launch {
            try {
                val avgs = lensRepository.getDailyFocusAverages()
                _uiState.update { it.copy(dailyFocusAverages = avgs) }
                logger.d("LensViewModel.loadMinimalFocusAverages", "Loaded daily focus averages", mapOf("count" to avgs.size))
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("LensViewModel.loadMinimalFocusAverages", "Failed to load daily focus averages", e)
            }
        }
        loadDailyTrackedTimeStats()
    }

    private fun loadDailyTrackedTimeStats() {
        viewModelScope.launch {
            try {
                val stats = lensRepository.getDailyTrackedTimeStats()
                _uiState.update { it.copy(dailyTrackedTimeStats = stats) }
                logger.d("LensViewModel.loadDailyTrackedTimeStats", "Loaded daily tracked time stats", mapOf("count" to stats.size))
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("LensViewModel.loadDailyTrackedTimeStats", "Failed to load daily tracked time stats", e)
            }
        }
        loadDailyFocusedHoursStats()
    }

    private fun loadDailyFocusedHoursStats() {
        viewModelScope.launch {
            try {
                val stats = lensRepository.getDailyFocusedHoursStats()
                _uiState.update { it.copy(dailyFocusedHoursStats = stats) }
                logger.d("LensViewModel.loadDailyFocusedHoursStats", "Loaded daily focused hours stats", mapOf("count" to stats.size))
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("LensViewModel.loadDailyFocusedHoursStats", "Failed to load daily focused hours stats", e)
            }
        }
    }

    private suspend fun loadTimeModuleHistoryInternal() {
        val context = backfillContext ?: return
        val summary = withContext(Dispatchers.Default) {
            buildTimeModuleHistorySummary(
                lensRepository = lensRepository,
                focusDate = context.focusDate,
                seededDataByDay = context.rangeSnapshotsByDay,
                historyDayLimit = FAST_HISTORY_DAYS,
            )
        }
        _uiState.update { it.copy(timeModuleHistorySummary = summary) }
        logger.d(
            "LensViewModel.loadTimeModuleHistoryInternal",
            "Loaded time module history summary",
            mapOf(
                "days" to (summary?.totalDays ?: 0),
                "currentDayKey" to (summary?.currentDayKey ?: "none"),
            ),
        )
    }

    /**
     * Select dimension split window.
     */
    fun selectDimensionSplitWindow(window: DimensionSplitWindow) {
        logger.d("LensViewModel.selectDimensionSplitWindow", "Selecting dimension split window", mapOf("window" to window.name))
        _uiState.update { it.copy(dimensionSplit = it.dimensionSplit.copy(window = window, windowOffset = 0, isLoading = true)) }
        loadDimensionSplit()
    }

    /**
     * Shift dimension split left.
     */
    fun shiftDimensionSplitLeft() {
        val state = _uiState.value.dimensionSplit
        if (state.window == DimensionSplitWindow.ALL) return
        val newOffset = state.windowOffset + 1
        logger.d("LensViewModel.shiftDimensionSplitLeft", "Shifting dimension split left", mapOf("newOffset" to newOffset))
        _uiState.update { it.copy(dimensionSplit = it.dimensionSplit.copy(windowOffset = newOffset, isLoading = true)) }
        loadDimensionSplit()
    }

    /**
     * Shift dimension split right.
     */
    fun shiftDimensionSplitRight() {
        val state = _uiState.value.dimensionSplit
        if (state.window == DimensionSplitWindow.ALL || state.windowOffset == 0) return
        val newOffset = state.windowOffset - 1
        logger.d("LensViewModel.shiftDimensionSplitRight", "Shifting dimension split right", mapOf("newOffset" to newOffset))
        _uiState.update { it.copy(dimensionSplit = it.dimensionSplit.copy(windowOffset = newOffset, isLoading = true)) }
        loadDimensionSplit()
    }

    private fun loadDimensionSplit() {
        viewModelScope.launch {
            loadDimensionSplitInternal()
        }
    }

    private suspend fun loadDimensionSplitInternal() {
        try {
            val today = LocalDate.now()
            val firstEntry = lensRepository.getFirstTrackedDate()
            val split = _uiState.value.dimensionSplit
            val window = split.window
            val offset = split.windowOffset
            val (requestedStart, requestedEnd) = if (window == DimensionSplitWindow.ALL) {
                (firstEntry ?: today) to today
            } else {
                val spanDays = window.spanDays!!.toLong()
                val shiftDays = spanDays * offset
                val end = today.minusDays(shiftDays)
                val start = end.minusDays(spanDays - 1)
                start to end
            }
            val effectiveStart = if (firstEntry != null && requestedStart.isBefore(firstEntry)) firstEntry else requestedStart
            val effectiveEnd = if (requestedEnd.isAfter(today)) today else requestedEnd
            val isClamped = effectiveStart.isAfter(requestedStart)
            val clampedDays = (effectiveEnd.toEpochDay() - effectiveStart.toEpochDay() + 1).toInt().coerceAtLeast(0)
            val requestedDays = window.spanDays ?: (today.toEpochDay() - (firstEntry ?: today).toEpochDay() + 1).toInt()
            val rawByDimension = if (effectiveStart.isAfter(effectiveEnd)) {
                emptyMap()
            } else {
                lensRepository.getDimensionSplitForRange(effectiveStart, effectiveEnd)
            }
            // Normalize sentinel keys (null, "", "unassigned") all into null so they
            // merge into a single "Unassigned" slice rather than appearing as separate entries.
            val byDimension = mutableMapOf<String?, Int>()
            rawByDimension.forEach { (key, minutes) ->
                val normalizedKey = if (key.isNullOrBlank() || key == "unassigned") null else key
                byDimension[normalizedKey] = (byDimension[normalizedKey] ?: 0) + minutes
            }
            val totalMinutes = byDimension.values.sum()
            val namedKeys = byDimension.keys.filterNotNull().sorted()
            val hasUnassigned = byDimension.containsKey(null)
            val canShiftLeft = window != DimensionSplitWindow.ALL && effectiveStart.isAfter(firstEntry ?: effectiveStart)
            val canShiftRight = window != DimensionSplitWindow.ALL && offset > 0

            logger.d(
                "LensViewModel.loadDimensionSplit",
                "Dimension split loaded",
                mapOf(
                    "window" to window.name,
                    "offset" to offset,
                    "effectiveStart" to effectiveStart.toString(),
                    "effectiveEnd" to effectiveEnd.toString(),
                    "isClamped" to isClamped,
                    "totalMinutes" to totalMinutes,
                    "namedDimensions" to namedKeys.size,
                    "dimensionKeys" to namedKeys.joinToString(","),
                    "hasUnassigned" to hasUnassigned,
                    "rawKeys" to rawByDimension.keys.size,
                ),
            )
            _uiState.update {
                it.copy(
                    dimensionSplit = it.dimensionSplit.copy(
                        firstEntryDate = firstEntry,
                        effectiveStart = effectiveStart,
                        effectiveEnd = effectiveEnd,
                        requestedStart = requestedStart,
                        requestedEnd = requestedEnd,
                        byDimension = byDimension,
                        totalMinutes = totalMinutes,
                        isClamped = isClamped,
                        clampedDays = clampedDays,
                        requestedDays = requestedDays,
                        canShiftLeft = canShiftLeft,
                        canShiftRight = canShiftRight,
                        isLoading = false,
                    ),
                )
            }
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e("LensViewModel.loadDimensionSplit", "Failed to load dimension split", e)
            _uiState.update { it.copy(dimensionSplit = it.dimensionSplit.copy(isLoading = false)) }
        }
    }

    /**
     * Select dimension trend window.
     */
    fun selectDimensionTrendWindow(window: DimensionTrendWindow) {
        logger.d("LensViewModel.selectDimensionTrendWindow", "Selecting dimension trend window", mapOf("window" to window.name, "spanDays" to window.spanDays))
        _uiState.update { it.copy(dimensionTrend = it.dimensionTrend.copy(window = window, isLoading = true)) }
        loadDimensionTrend()
    }

    // Public trigger methods — called from LensesScreen when opt-in pref is enabled
    /**
     * Trigger dim trend load.
     */
    fun triggerDimTrendLoad() {
        logger.d("LensViewModel.triggerDimTrendLoad", "Dimension trend chart load triggered")
        loadDimensionTrend()
    }
    /**
     * Trigger daily timeline load.
     */
    fun triggerDailyTimelineLoad() {
        logger.d("LensViewModel.triggerDailyTimelineLoad", "Daily timeline chart load triggered")
        loadHeatmap()
    }
    /**
     * Trigger weekly pattern load.
     */
    fun triggerWeeklyPatternLoad(excludeEmptyDays: Boolean) {
        logger.d("LensViewModel.triggerWeeklyPatternLoad", "Weekly pattern chart load triggered", mapOf("excludeEmptyDays" to excludeEmptyDays))
        loadWeekGrid(excludeEmptyDays)
    }
    /**
     * Trigger daily rhythm load.
     */
    fun triggerDailyRhythmLoad(excludeEmptyDays: Boolean) {
        logger.d("LensViewModel.triggerDailyRhythmLoad", "Daily rhythm chart load triggered", mapOf("excludeEmptyDays" to excludeEmptyDays))
        loadMinutePattern(excludeEmptyDays)
    }

    private fun loadDimensionTrend() {
        viewModelScope.launch {
            loadDimensionTrendInternal()
        }
    }

    private fun loadHeatmap() {
        viewModelScope.launch {
            loadHeatmapInternal()
        }
    }

    private fun loadWeekGrid(excludeEmptyDays: Boolean = false) {
        viewModelScope.launch {
            loadWeekGridInternal(excludeEmptyDays)
        }
    }

    private fun loadMinutePattern(excludeEmptyDays: Boolean = false) {
        viewModelScope.launch {
            loadMinutePatternInternal(excludeEmptyDays)
        }
    }

    private suspend fun loadDimensionTrendInternal() {
        try {
            val window = _uiState.value.dimensionTrend.window
            logger.d("LensViewModel.loadDimensionTrend", "Loading dimension trend blocks", mapOf("window" to window.name))
            val blocks = withContext(Dispatchers.Default) { lensRepository.getDimensionTrendBlocks(windowDays = window.spanDays) }
            _uiState.update { it.copy(dimensionTrend = it.dimensionTrend.copy(blocks = blocks, isLoading = false)) }
            logger.d("LensViewModel.loadDimensionTrend", "Dimension trend loaded", mapOf("window" to window.name, "blockCount" to blocks.size))
        } catch (_: CancellationException) {
            logger.d("LensViewModel.loadDimensionTrend", "Dimension trend load cancelled")
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e("LensViewModel.loadDimensionTrend", "Failed to load dimension trend", e)
            _uiState.update { it.copy(dimensionTrend = it.dimensionTrend.copy(isLoading = false)) }
        }
    }

    private suspend fun loadHeatmapInternal() {
        try {
            logger.d("LensViewModel.loadHeatmap", "Loading heatmap days")
            _uiState.update { it.copy(heatmap = it.heatmap.copy(isLoading = true)) }
            val days = withContext(Dispatchers.Default) { lensRepository.getHeatmapDays() }
            _uiState.update { it.copy(heatmap = HeatmapState(days = days, isLoading = false)) }
            logger.d("LensViewModel.loadHeatmap", "Heatmap loaded", mapOf("dayCount" to days.size, "totalSegments" to days.sumOf { it.segments.size }))
        } catch (_: CancellationException) {
            logger.d("LensViewModel.loadHeatmap", "Heatmap load cancelled")
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e("LensViewModel.loadHeatmap", "Failed to load heatmap", e)
            _uiState.update { it.copy(heatmap = it.heatmap.copy(isLoading = false)) }
        }
    }

    private suspend fun loadWeekGridInternal(excludeEmptyDays: Boolean) {
        try {
            logger.d("LensViewModel.loadWeekGrid", "Loading week grid data", mapOf("excludeEmptyDays" to excludeEmptyDays))
            _uiState.update { it.copy(weekGrid = it.weekGrid.copy(isLoading = true)) }
            val data = withContext(Dispatchers.Default) { lensRepository.getWeekGridData(excludeEmptyDays) }
            _uiState.update { it.copy(weekGrid = WeekGridState(data = data, isLoading = false)) }
            logger.d("LensViewModel.loadWeekGrid", "Week grid loaded", mapOf("days" to data.days.size))
        } catch (_: CancellationException) {
            logger.d("LensViewModel.loadWeekGrid", "Week grid load cancelled")
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e("LensViewModel.loadWeekGrid", "Failed to load week grid", e)
            _uiState.update { it.copy(weekGrid = it.weekGrid.copy(isLoading = false)) }
        }
    }

    private suspend fun loadMinutePatternInternal(excludeEmptyDays: Boolean) {
        try {
            logger.d("LensViewModel.loadMinutePattern", "Loading minute pattern data", mapOf("excludeEmptyDays" to excludeEmptyDays))
            _uiState.update { it.copy(minutePattern = it.minutePattern.copy(isLoading = true)) }
            val data = withContext(Dispatchers.Default) { lensRepository.getMinutePatternData(excludeEmptyDays) }
            _uiState.update { it.copy(minutePattern = MinutePatternState(data = data, isLoading = false)) }
            logger.d("LensViewModel.loadMinutePattern", "Minute pattern loaded", mapOf("days" to data.days.size))
        } catch (_: CancellationException) {
            logger.d("LensViewModel.loadMinutePattern", "Minute pattern load cancelled")
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e("LensViewModel.loadMinutePattern", "Failed to load minute pattern", e)
            _uiState.update { it.copy(minutePattern = it.minutePattern.copy(isLoading = false)) }
        }
    }

    private suspend fun loadAverageDailyTimeInternal() {
        try {
            logger.d("LensViewModel.loadAverageDailyTime", "Loading average daily time table")
            val table = withContext(Dispatchers.IO) { lensRepository.getAverageDailyTimeTableData() }
            _uiState.update { it.copy(averageDailyTimeTable = table) }
            logger.d(
                "LensViewModel.loadAverageDailyTime",
                "Average daily time table loaded",
                mapOf(
                    "hasTable" to (table != null),
                    "days" to (table?.totalCalendarDays ?: 0),
                    "rows" to (table?.rows?.size ?: 0),
                    "windows" to (table?.visibleWindows?.size ?: 0),
                ),
            )
        } catch (_: CancellationException) {
            logger.d("LensViewModel.loadAverageDailyTime", "Average daily time load cancelled")
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e("LensViewModel.loadAverageDailyTime", "Failed to load average daily time table", e)
            _uiState.update { it.copy(averageDailyTimeTable = null) }
        }
    }

    private fun scheduleHistoryBackfill(context: LensBackfillContext, maxHistoryLimit: Int) {
        historyBackfill.schedule(
            scope = viewModelScope,
            lensRepository = lensRepository,
            focusDate = context.focusDate,
            seededDataByDay = context.rangeSnapshotsByDay,
            expectedRange = context.resolvedRange,
            maxHistoryLimit = maxHistoryLimit,
            loadSnapshot = { snapshotDayKey, seedData -> snapshotCache.getOrLoad(snapshotDayKey, seedData) },
            isCurrentSelection = {
                val stateNow = _uiState.value
                resolveDateRange(
                    anchorDate = stateNow.selectedDate,
                    mode = stateNow.selectedTimeMode,
                    window = stateNow.selectedTimeWindow,
                    pageIndex = stateNow.windowPageIndex,
                ) == context.resolvedRange
            },
            onBackfillReady = { summary ->
                _uiState.update { stateNow ->
                    if (stateNow.timeModuleHistorySummary?.totalDays == summary.totalDays) {
                        stateNow
                    } else {
                        stateNow.copy(timeModuleHistorySummary = summary)
                    }
                }
                val pendingLimit = pendingHistoryLimit
                if (pendingLimit != null && summary.totalDays >= pendingLimit) {
                    pendingHistoryLimit = null
                }
            },
        )
    }

    private fun refreshReflections(dayKey: String) {
        reflectionRefreshJob?.cancel()
        reflectionRefreshJob = viewModelScope.launch {
            try {
                if (!lensRepository.isDayDirty(dayKey)) {
                    logger.d("LensViewModel.refreshReflections", "Skipping reflection refresh for clean day", mapOf("dayKey" to dayKey))
                    return@launch
                }
                lensRepository.generateReflectionCards(dayKey)
                val reflections = lensRepository.observeReflections(dayKey).firstOrNull() ?: emptyList()
                val selectedFocusDayKey = focusDateForRange(
                    resolveDateRange(
                        anchorDate = _uiState.value.selectedDate,
                        mode = _uiState.value.selectedTimeMode,
                        window = _uiState.value.selectedTimeWindow,
                        pageIndex = _uiState.value.windowPageIndex,
                    ),
                ).format(DateTimeFormatter.ISO_LOCAL_DATE)
                if (selectedFocusDayKey != dayKey) {
                    logger.d(
                        "LensViewModel.refreshReflections",
                        "Ignoring stale reflections refresh",
                        mapOf("dayKey" to dayKey, "selectedDayKey" to selectedFocusDayKey),
                    )
                    return@launch
                }
                _uiState.update { it.copy(reflections = reflections) }
                logger.d(
                    "LensViewModel.refreshReflections",
                    "Reflections refreshed",
                    mapOf("dayKey" to dayKey, "count" to reflections.size),
                )
            } catch (_: CancellationException) {
                logger.d("LensViewModel.refreshReflections", "Reflection refresh cancelled")
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("LensViewModel.refreshReflections", "Failed to refresh reflections", e)
            }
        }
    }

    private fun resolveDateRange(
        anchorDate: LocalDate,
        mode: LensTimeMode,
        window: LensTimeWindow,
        pageIndex: Int,
    ): ResolvedLensWindowRange {
        val normalizedPageIndex = pageIndex.coerceAtLeast(0)
        val effectiveWindow = if (isWindowAllowedForMode(window, mode)) window else defaultWindowForMode(mode)

        return when (mode) {
            LensTimeMode.TODAY -> ResolvedLensWindowRange(
                mode = LensTimeMode.TODAY,
                window = LensTimeWindow.TODAY,
                pageIndex = 0,
                startDate = anchorDate,
                endDate = anchorDate,
            )

            LensTimeMode.PAST -> {
                if (effectiveWindow == LensTimeWindow.ALL_PAST_DAYS) {
                    val endDate = anchorDate.minusDays(1)
                    val startDate = endDate.minusDays((MAX_RANGE_DAYS - 1).toLong())
                    ResolvedLensWindowRange(
                        mode = LensTimeMode.PAST,
                        window = LensTimeWindow.ALL_PAST_DAYS,
                        pageIndex = 0,
                        startDate = startDate,
                        endDate = endDate,
                    )
                } else {
                    val spanDays = spanDaysForWindow(effectiveWindow)
                    val shiftDays = spanDays * normalizedPageIndex
                    val endDate = anchorDate.minusDays(1 + shiftDays)
                    val startDate = endDate.minusDays(spanDays - 1)
                    ResolvedLensWindowRange(
                        mode = LensTimeMode.PAST,
                        window = effectiveWindow,
                        pageIndex = normalizedPageIndex,
                        startDate = startDate,
                        endDate = endDate,
                    )
                }
            }

            LensTimeMode.FUTURE -> {
                val spanDays = spanDaysForWindow(effectiveWindow)
                val shiftDays = spanDays * normalizedPageIndex
                val startDate = anchorDate.plusDays(1 + shiftDays)
                val endDate = startDate.plusDays(spanDays - 1)
                ResolvedLensWindowRange(
                    mode = LensTimeMode.FUTURE,
                    window = effectiveWindow,
                    pageIndex = normalizedPageIndex,
                    startDate = startDate,
                    endDate = endDate,
                )
            }
        }
    }

    private fun focusDateForRange(resolvedRange: ResolvedLensWindowRange): LocalDate = when (resolvedRange.mode) {
        LensTimeMode.TODAY -> resolvedRange.startDate
        LensTimeMode.PAST -> resolvedRange.endDate
        LensTimeMode.FUTURE -> resolvedRange.startDate
    }

    private fun navigationStateForSelection(
        mode: LensTimeMode,
        window: LensTimeWindow,
        pageIndex: Int,
    ): LensWindowNavigationState = when (mode) {
        LensTimeMode.TODAY -> LensWindowNavigationState(canGoPrevious = false, canGoNext = false)

        LensTimeMode.PAST -> {
            if (window == LensTimeWindow.ALL_PAST_DAYS) {
                LensWindowNavigationState(canGoPrevious = false, canGoNext = false)
            } else {
                LensWindowNavigationState(
                    canGoPrevious = true,
                    canGoNext = pageIndex > 0,
                )
            }
        }

        LensTimeMode.FUTURE -> LensWindowNavigationState(
            canGoPrevious = pageIndex > 0,
            canGoNext = true,
        )
    }

    private fun isWindowAllowedForMode(window: LensTimeWindow, mode: LensTimeMode): Boolean = windowsForMode(mode).contains(window)

    private fun defaultWindowForMode(mode: LensTimeMode): LensTimeWindow = when (mode) {
        LensTimeMode.TODAY -> LensTimeWindow.TODAY
        LensTimeMode.PAST -> LensTimeWindow.LAST_DAY
        LensTimeMode.FUTURE -> LensTimeWindow.NEXT_DAY
    }

    private fun spanDaysForWindow(window: LensTimeWindow): Long = when (window) {
        LensTimeWindow.TODAY -> 1
        LensTimeWindow.LAST_DAY -> 1
        LensTimeWindow.LAST_7_DAYS -> 7
        LensTimeWindow.LAST_30_DAYS -> 30
        LensTimeWindow.LAST_90_DAYS -> 90
        LensTimeWindow.LAST_180_DAYS -> 180
        LensTimeWindow.LAST_365_DAYS -> 365
        LensTimeWindow.ALL_PAST_DAYS -> MAX_RANGE_DAYS.toLong()
        LensTimeWindow.NEXT_DAY -> 1
        LensTimeWindow.NEXT_7_DAYS -> 7
        LensTimeWindow.NEXT_30_DAYS -> 30
        LensTimeWindow.NEXT_90_DAYS -> 90
        LensTimeWindow.NEXT_180_DAYS -> 180
        LensTimeWindow.NEXT_365_DAYS -> 365
    }
}
/**
 * LensUiState.
 */
data class LensUiState(
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    val errorMessage: String? = null,
    val selectedDate: LocalDate = LocalDate.now(),
    val selectedMoment: LensMoment = LensMoment.LIVE_DAY,
    val selectedTimeMode: LensTimeMode = LensTimeMode.TODAY,
    val selectedTimeWindow: LensTimeWindow = LensTimeWindow.TODAY,
    val windowPageIndex: Int = 0,
    val canGoToPreviousWindowPage: Boolean = false,
    val canGoToNextWindowPage: Boolean = false,
    val selectedGrouping: LensGrouping = LensGrouping.BY_MODULE,
    val planningData: PlanningLensData? = null,
    val planCompletenessScore: Float = 0f,
    val realityData: RealityLensData? = null,
    val adherenceScore: Float = 0f,
    val reflections: List<LensReflectionRecord> = emptyList(),
    val selectedRangeSummary: LensRangeSummary? = null,
    val timeModuleHistorySummary: TimeModuleHistorySummary? = null,
    val averageDailyTimeTable: AverageDailyTimeTableData? = null,
    val longHorizonSummaries: List<LensRangeSummary> = emptyList(),
    val dailyFocusAverages: List<DailyFocusStat> = emptyList(),
    val dailyTrackedTimeStats: List<DailyTrackedTimeStat> = emptyList(),
    val dailyFocusedHoursStats: List<DailyFocusedHoursStat> = emptyList(),
    val dimensionSplit: DimensionSplitState = DimensionSplitState(),
    val dimensionTrend: DimensionTrendState = DimensionTrendState(),
    val heatmap: HeatmapState = HeatmapState(),
    val weekGrid: WeekGridState = WeekGridState(),
    val minutePattern: MinutePatternState = MinutePatternState(),
)
/**
 * LensMoment.
 */
enum class LensMoment  {
    START_DAY,
    LIVE_DAY,
    CLOSE_DAY,
}
/**
 * LensGrouping.
 */
enum class LensGrouping  {
    OVERALL,
    BY_MODULE,
    BY_DIMENSION,
}
/**
 * LensTimeMode.
 */
enum class LensTimeMode  {
    TODAY,
    PAST,
    FUTURE,
}
/**
 * LensTimeWindow.
 */
enum class LensTimeWindow {
    TODAY,
    LAST_DAY,
    LAST_7_DAYS,
    LAST_30_DAYS,
    LAST_90_DAYS,
    LAST_180_DAYS,
    LAST_365_DAYS,
    ALL_PAST_DAYS,
    NEXT_DAY,
    NEXT_7_DAYS,
    NEXT_30_DAYS,
    NEXT_90_DAYS,
    NEXT_180_DAYS,
    NEXT_365_DAYS,
}
/**
 * LensTrendPoint.
 */
data class LensTrendPoint(val dayKey: String, val plannedMinutes: Int, val actualMinutes: Int)
/**
 * LensRangeSummary.
 */
data class LensRangeSummary(
    val mode: LensTimeMode,
    val window: LensTimeWindow,
    val pageIndex: Int,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val totalPlannedMinutes: Int,
    val totalActualMinutes: Int,
    val totalUntrackedMinutes: Int,
    val totalFocusGapMinutes: Int,
    val supplementalActualMinutes: Int = 0,
    val plannedTaskMinutes: Int = 0,
    val plannedHabitMinutes: Int = 0,
    val plannedTimeOnlyMinutes: Int = 0,
    val unplannedDayMinutes: Int = 0,
    val actualTimeOnlyMinutes: Int = 0,
    val actualTaskMinutes: Int = 0,
    val actualHabitMinutes: Int = 0,
    val plannedTaskCount: Int,
    val completedTaskCount: Int,
    val missedTaskCount: Int,
    val plannedHabitCount: Int,
    val completedHabitCount: Int,
    val missedHabitCount: Int,
    val averagePlanCompleteness: Float,
    val averageAdherence: Float,
    val plannedByDimension: Map<String, Int>,
    val actualByDimension: Map<String, Int>,
    val supplementalActualByDimension: Map<String, Int> = emptyMap(),
    val plannedTasksByDimension: Map<String, Int>,
    val completedTasksByDimension: Map<String, Int>,
    val missedTasksByDimension: Map<String, Int>,
    val plannedHabitsByDimension: Map<String, Int>,
    val completedHabitsByDimension: Map<String, Int>,
    val missedHabitsByDimension: Map<String, Int>,
    val trendPoints: List<LensTrendPoint>,
)
/**
 * ResolvedLensWindowRange.
 */
data class ResolvedLensWindowRange(
    val mode: LensTimeMode,
    val window: LensTimeWindow,
    val pageIndex: Int,
    val startDate: LocalDate,
    val endDate: LocalDate,
)
/**
 * LensWindowNavigationState.
 */
data class LensWindowNavigationState(val canGoPrevious: Boolean, val canGoNext: Boolean)
/**
 * Windows for mode.
 */
fun windowsForMode(mode: LensTimeMode): List<LensTimeWindow> = when (mode) {
    LensTimeMode.TODAY -> listOf(LensTimeWindow.TODAY)

    LensTimeMode.PAST -> listOf(
        LensTimeWindow.LAST_DAY,
        LensTimeWindow.LAST_7_DAYS,
        LensTimeWindow.LAST_30_DAYS,
        LensTimeWindow.LAST_90_DAYS,
        LensTimeWindow.LAST_180_DAYS,
        LensTimeWindow.LAST_365_DAYS,
        LensTimeWindow.ALL_PAST_DAYS,
    )

    LensTimeMode.FUTURE -> listOf(
        LensTimeWindow.NEXT_DAY,
        LensTimeWindow.NEXT_7_DAYS,
        LensTimeWindow.NEXT_30_DAYS,
        LensTimeWindow.NEXT_90_DAYS,
        LensTimeWindow.NEXT_180_DAYS,
        LensTimeWindow.NEXT_365_DAYS,
    )
}

/**
 * DimensionSplitWindow.
 */
enum class DimensionSplitWindow(val spanDays: Int?) {
    W1(1),
    W7(7),
    W30(30),
    W90(90),
    W180(180),
    W365(365),
    ALL(null),
}

/**
 * DimensionSplitState.
 */
data class DimensionSplitState(
    val window: DimensionSplitWindow = DimensionSplitWindow.W1,
    val windowOffset: Int = 0,
    val firstEntryDate: LocalDate? = null,
    val effectiveStart: LocalDate = LocalDate.now(),
    val effectiveEnd: LocalDate = LocalDate.now(),
    val requestedStart: LocalDate = LocalDate.now(),
    val requestedEnd: LocalDate = LocalDate.now(),
    val byDimension: Map<String?, Int> = emptyMap(),
    val totalMinutes: Int = 0,
    val isClamped: Boolean = false,
    val clampedDays: Int = 0,
    val requestedDays: Int = 0,
    val canShiftLeft: Boolean = false,
    val canShiftRight: Boolean = false,
    val isLoading: Boolean = false,
)

/**
 * DimensionTrendWindow.
 */
enum class DimensionTrendWindow(val spanDays: Int) {
    W1(1),
    W7(7),
    W30(30),
    W90(90),
    W180(180),
    W365(365),
}

/**
 * DimensionTrendState.
 */
data class DimensionTrendState(
    val window: DimensionTrendWindow = DimensionTrendWindow.W1,
    val blocks: List<DimensionTrendBlock> = emptyList(),
    val isLoading: Boolean = false,
)

/**
 * HeatmapState.
 */
data class HeatmapState(
    val days: List<HeatmapDayData> = emptyList(),
    val isLoading: Boolean = false,
)

/**
 * WeekGridState.
 */
data class WeekGridState(
    val data: WeekGridData = WeekGridData(emptyList()),
    val isLoading: Boolean = false,
)

/**
 * MinutePatternState.
 */
data class MinutePatternState(
    val data: MinutePatternData = MinutePatternData(emptyList()),
    val isLoading: Boolean = false,
)
