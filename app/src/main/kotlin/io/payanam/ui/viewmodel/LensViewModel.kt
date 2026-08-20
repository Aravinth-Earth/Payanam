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
    /** Focus date. */
    val focusDate: LocalDate,
    /** Resolved range. */
    val resolvedRange: ResolvedLensWindowRange,
    /** Range snapshots by day. */
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
    /** Ui state. */
    val uiState: StateFlow<LensUiState> = _uiState.asStateFlow()

    init {
        logger.i("LensViewModel.init", "Initializing LensViewModel")
        /** Load lens data. */
        loadLensData()
    }

    /**
     * Select date.
     */
    fun selectDate(date: LocalDate) {
        logger.d("LensViewModel.selectDate", "Selecting date", mapOf("date" to date.toString()))
        _uiState.update { state ->
            /** Navigation state. */
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
        /** Load lens data. */
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
        /** If. */
        if (_uiState.value.selectedGrouping == grouping) return
        logger.d("LensViewModel.selectGrouping", "Selecting grouping", mapOf("grouping" to grouping.name))
        _uiState.update { it.copy(selectedGrouping = grouping) }
    }

    /**
     * Select time mode.
     */
    fun selectTimeMode(mode: LensTimeMode) {
        /** Default window. */
        val defaultWindow = defaultWindowForMode(mode)
        /** Current state. */
        val currentState = _uiState.value
        /** If. */
        if (
            currentState.selectedTimeMode == mode &&
            currentState.selectedTimeWindow == defaultWindow &&
            currentState.windowPageIndex == 0
        ) {
            /** Return. */
            return
        }
        logger.d("LensViewModel.selectTimeMode", "Selecting time mode", mapOf("mode" to mode.name))
        /** Navigation state. */
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
        /** Load lens data. */
        loadLensData()
    }

    /**
     * Select time window.
     */
    fun selectTimeWindow(window: LensTimeWindow) {
        /** Mode. */
        val mode = _uiState.value.selectedTimeMode
        /** If. */
        if (!isWindowAllowedForMode(window, mode)) {
            logger.w(
                "LensViewModel.selectTimeWindow",
                "Ignoring unsupported window for mode",
                /** Map of. */
                mapOf("mode" to mode.name, "window" to window.name),
            )
            /** Return. */
            return
        }
        /** Current state. */
        val currentState = _uiState.value
        /** If. */
        if (currentState.selectedTimeWindow == window && currentState.windowPageIndex == 0) {
            /** Return. */
            return
        }
        logger.d("LensViewModel.selectTimeWindow", "Selecting time window", mapOf("window" to window.name))
        /** Navigation state. */
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
        /** Load lens data. */
        loadLensData()
    }

    /**
     * Go to previous window page.
     */
    fun goToPreviousWindowPage() {
        /** State. */
        val state = _uiState.value
        /** If. */
        if (!state.canGoToPreviousWindowPage) return

        /** New page index. */
        val newPageIndex = when (state.selectedTimeMode) {
            LensTimeMode.TODAY -> 0

            LensTimeMode.PAST -> {
                /** If. */
                if (state.selectedTimeWindow == LensTimeWindow.ALL_PAST_DAYS) 0 else state.windowPageIndex + 1
            }

            LensTimeMode.FUTURE -> (state.windowPageIndex - 1).coerceAtLeast(0)
        }
        /** Navigation state. */
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
        /** Load lens data. */
        loadLensData()
    }

    /**
     * Go to next window page.
     */
    fun goToNextWindowPage() {
        /** State. */
        val state = _uiState.value
        /** If. */
        if (!state.canGoToNextWindowPage) return

        /** New page index. */
        val newPageIndex = when (state.selectedTimeMode) {
            LensTimeMode.TODAY -> 0
            LensTimeMode.PAST -> (state.windowPageIndex - 1).coerceAtLeast(0)
            LensTimeMode.FUTURE -> state.windowPageIndex + 1
        }
        /** Navigation state. */
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
        /** Load lens data. */
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
                /** Load lens data. */
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
                /** Day key. */
                val dayKey = _uiState.value.selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
                lensRepository.generateReflectionCards(dayKey)
                /** Load lens data. */
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
        /** If. */
        if (!timeHistoryChartsEnabled) return
        /** If. */
        if (FeatureFlags.minimalModeEnabled) return
        /** Context. */
        val context = backfillContext ?: return
        /** Current days. */
        val currentDays = _uiState.value.timeModuleHistorySummary?.totalDays ?: FAST_HISTORY_DAYS
        /** Next limit. */
        val nextLimit = historyBackfill.nextLimitAfter(currentDays) ?: return
        /** Now. */
        val now = android.os.SystemClock.elapsedRealtime()
        /** If. */
        if (now - lastHistoryPrefetchAtMs < HISTORY_PREFETCH_COOLDOWN_MS) {
            /** Return. */
            return
        }
        /** Pending limit. */
        val pendingLimit = pendingHistoryLimit
        /** If. */
        if (pendingLimit != null && nextLimit <= pendingLimit) {
            /** Return. */
            return
        }
        lastHistoryPrefetchAtMs = now
        pendingHistoryLimit = nextLimit
        /** Schedule history backfill. */
        scheduleHistoryBackfill(context = context, maxHistoryLimit = nextLimit)
    }

    /**
     * Load enabled charts sequentially.
     */
    fun loadEnabledChartsSequentially(
        /** Chart time module enabled. */
        chartTimeModuleEnabled: Boolean,
        /** Chart time score cards enabled. */
        chartTimeScoreCardsEnabled: Boolean,
        /** Chart time overall score card enabled. */
        chartTimeOverallScoreCardEnabled: Boolean,
        /** Chart time dimension score cards enabled. */
        chartTimeDimensionScoreCardsEnabled: Boolean,
        /** Chart time line graphs enabled. */
        chartTimeLineGraphsEnabled: Boolean,
        /** Chart time daily score trend enabled. */
        chartTimeDailyScoreTrendEnabled: Boolean,
        /** Chart time progress trend enabled. */
        chartTimeProgressTrendEnabled: Boolean,
        /** Chart time historical ranking enabled. */
        chartTimeHistoricalRankingEnabled: Boolean,
        /** Chart time momentum streak enabled. */
        chartTimeMomentumStreakEnabled: Boolean,
        /** Chart dim split enabled. */
        chartDimSplitEnabled: Boolean,
        /** Chart average daily time enabled. */
        chartAverageDailyTimeEnabled: Boolean,
        /** Chart dim trend enabled. */
        chartDimTrendEnabled: Boolean,
        /** Chart daily timeline enabled. */
        chartDailyTimelineEnabled: Boolean,
        /** Chart weekly pattern enabled. */
        chartWeeklyPatternEnabled: Boolean,
        /** Chart weekly pattern excl empty. */
        chartWeeklyPatternExclEmpty: Boolean,
        /** Chart daily rhythm enabled. */
        chartDailyRhythmEnabled: Boolean,
        /** Chart daily rhythm excl empty. */
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
                /** Map of. */
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
            /** If. */
            if (!timeHistoryChartsEnabled) {
                historyBackfill.cancel()
                _uiState.update { it.copy(timeModuleHistorySummary = null) }
            } else if (_uiState.value.timeModuleHistorySummary == null) {
                /** Load time module history internal. */
                loadTimeModuleHistoryInternal()
                backfillContext?.let { context ->
                    /** Schedule history backfill. */
                    scheduleHistoryBackfill(
                        context = context,
                        maxHistoryLimit = INITIAL_BACKFILL_STAGE_LIMIT,
                    )
                }
            }
            /** If. */
            if (chartTimeModuleEnabled && chartDimSplitEnabled) {
                /** Load dimension split internal. */
                loadDimensionSplitInternal()
            }
            /** If. */
            if (chartTimeModuleEnabled && chartAverageDailyTimeEnabled) {
                /** If. */
                if (_uiState.value.averageDailyTimeTable == null) {
                    /** Load average daily time internal. */
                    loadAverageDailyTimeInternal()
                } else {
                    logger.d(
                        "LensViewModel.loadEnabledChartsSequentially",
                        "Reusing cached average daily time table",
                        /** Map of. */
                        mapOf(
                            "days" to (_uiState.value.averageDailyTimeTable?.totalCalendarDays ?: 0),
                            "rows" to (_uiState.value.averageDailyTimeTable?.rows?.size ?: 0),
                        ),
                    )
                }
            } else if (!chartTimeModuleEnabled || !chartAverageDailyTimeEnabled) {
                _uiState.update { it.copy(averageDailyTimeTable = null) }
            }
            /** If. */
            if (chartTimeModuleEnabled && chartDimTrendEnabled) loadDimensionTrendInternal()
            /** If. */
            if (chartTimeModuleEnabled && chartDailyTimelineEnabled) loadHeatmapInternal()
            /** If. */
            if (chartTimeModuleEnabled && chartWeeklyPatternEnabled) loadWeekGridInternal(chartWeeklyPatternExclEmpty)
            /** If. */
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
                /** Execute lens data load. */
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
                /** State. */
                val state = _uiState.value
                /** Resolved range. */
                val resolvedRange = resolveDateRange(
                    anchorDate = state.selectedDate,
                    mode = state.selectedTimeMode,
                    window = state.selectedTimeWindow,
                    pageIndex = state.windowPageIndex,
                )
                /** Focus date. */
                val focusDate = focusDateForRange(resolvedRange)
                /** Day key. */
                val dayKey = focusDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
                logger.d(
                    "LensViewModel.loadLensData",
                    "Loading lens data",
                    /** Map of. */
                    mapOf(
                        "dayKey" to dayKey,
                        "mode" to resolvedRange.mode.name,
                        "window" to resolvedRange.window.name,
                        "pageIndex" to resolvedRange.pageIndex,
                        "startDate" to resolvedRange.startDate.toString(),
                        "endDate" to resolvedRange.endDate.toString(),
                    ),
                )

                /** Prepared load data. */
                val preparedLoadData = prepareLensLoadData(
                    lensRepository = lensRepository,
                    snapshotCache = snapshotCache,
                    resolvedRange = resolvedRange,
                    focusDate = focusDate,
                    dayKey = dayKey,
                    fastHistoryDays = FAST_HISTORY_DAYS,
                    loadTimeHistorySummary = timeHistoryChartsEnabled,
                )
                /** Navigation state. */
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
                    /** Map of. */
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
                /** If. */
                if (FeatureFlags.minimalModeEnabled) {
                    /** Load minimal focus averages. */
                    loadMinimalFocusAverages()
                } else {
                    /** If. */
                    if (timeHistoryChartsEnabled) {
                        /** Schedule history backfill. */
                        scheduleHistoryBackfill(
                            context = backfillContext ?: return,
                            maxHistoryLimit = INITIAL_BACKFILL_STAGE_LIMIT,
                        )
                    }
                    /** If. */
                    if (resolvedRange.mode != LensTimeMode.FUTURE) {
                        /** Refresh reflections. */
                        refreshReflections(dayKey)
                    }
                }
    }

    private fun loadMinimalFocusAverages() {
        viewModelScope.launch {
            try {
                /** Avgs. */
                val avgs = lensRepository.getDailyFocusAverages()
                _uiState.update { it.copy(dailyFocusAverages = avgs) }
                logger.d("LensViewModel.loadMinimalFocusAverages", "Loaded daily focus averages", mapOf("count" to avgs.size))
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("LensViewModel.loadMinimalFocusAverages", "Failed to load daily focus averages", e)
            }
        }
        /** Load daily tracked time stats. */
        loadDailyTrackedTimeStats()
    }

    private fun loadDailyTrackedTimeStats() {
        viewModelScope.launch {
            try {
                /** Stats. */
                val stats = lensRepository.getDailyTrackedTimeStats()
                _uiState.update { it.copy(dailyTrackedTimeStats = stats) }
                logger.d("LensViewModel.loadDailyTrackedTimeStats", "Loaded daily tracked time stats", mapOf("count" to stats.size))
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("LensViewModel.loadDailyTrackedTimeStats", "Failed to load daily tracked time stats", e)
            }
        }
        /** Load daily focused hours stats. */
        loadDailyFocusedHoursStats()
    }

    private fun loadDailyFocusedHoursStats() {
        viewModelScope.launch {
            try {
                /** Stats. */
                val stats = lensRepository.getDailyFocusedHoursStats()
                _uiState.update { it.copy(dailyFocusedHoursStats = stats) }
                logger.d("LensViewModel.loadDailyFocusedHoursStats", "Loaded daily focused hours stats", mapOf("count" to stats.size))
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("LensViewModel.loadDailyFocusedHoursStats", "Failed to load daily focused hours stats", e)
            }
        }
    }

    private suspend fun loadTimeModuleHistoryInternal() {
        /** Context. */
        val context = backfillContext ?: return
        /** Summary. */
        val summary = withContext(Dispatchers.Default) {
            /** Build time module history summary. */
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
            /** Map of. */
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
        /** Load dimension split. */
        loadDimensionSplit()
    }

    /**
     * Shift dimension split left.
     */
    fun shiftDimensionSplitLeft() {
        /** State. */
        val state = _uiState.value.dimensionSplit
        /** If. */
        if (state.window == DimensionSplitWindow.ALL) return
        /** New offset. */
        val newOffset = state.windowOffset + 1
        logger.d("LensViewModel.shiftDimensionSplitLeft", "Shifting dimension split left", mapOf("newOffset" to newOffset))
        _uiState.update { it.copy(dimensionSplit = it.dimensionSplit.copy(windowOffset = newOffset, isLoading = true)) }
        /** Load dimension split. */
        loadDimensionSplit()
    }

    /**
     * Shift dimension split right.
     */
    fun shiftDimensionSplitRight() {
        /** State. */
        val state = _uiState.value.dimensionSplit
        /** If. */
        if (state.window == DimensionSplitWindow.ALL || state.windowOffset == 0) return
        /** New offset. */
        val newOffset = state.windowOffset - 1
        logger.d("LensViewModel.shiftDimensionSplitRight", "Shifting dimension split right", mapOf("newOffset" to newOffset))
        _uiState.update { it.copy(dimensionSplit = it.dimensionSplit.copy(windowOffset = newOffset, isLoading = true)) }
        /** Load dimension split. */
        loadDimensionSplit()
    }

    private fun loadDimensionSplit() {
        viewModelScope.launch {
            /** Load dimension split internal. */
            loadDimensionSplitInternal()
        }
    }

    private suspend fun loadDimensionSplitInternal() {
        try {
            /** Today. */
            val today = LocalDate.now()
            /** First entry. */
            val firstEntry = lensRepository.getFirstTrackedDate()
            /** Split. */
            val split = _uiState.value.dimensionSplit
            /** Window. */
            val window = split.window
            /** Offset. */
            val offset = split.windowOffset

            /** Val. */
            val (requestedStart, requestedEnd) = if (window == DimensionSplitWindow.ALL) {
                (firstEntry ?: today) to today
            } else {
                /** Span days. */
                val spanDays = window.spanDays!!.toLong()
                /** Shift days. */
                val shiftDays = spanDays * offset
                /** End. */
                val end = today.minusDays(shiftDays)
                /** Start. */
                val start = end.minusDays(spanDays - 1)
                start to end
            }

            /** Effective start. */
            val effectiveStart = if (firstEntry != null && requestedStart.isBefore(firstEntry)) firstEntry else requestedStart
            /** Effective end. */
            val effectiveEnd = if (requestedEnd.isAfter(today)) today else requestedEnd
            /** Is clamped. */
            val isClamped = effectiveStart.isAfter(requestedStart)
            /** Clamped days. */
            val clampedDays = (effectiveEnd.toEpochDay() - effectiveStart.toEpochDay() + 1).toInt().coerceAtLeast(0)
            /** Requested days. */
            val requestedDays = window.spanDays ?: (today.toEpochDay() - (firstEntry ?: today).toEpochDay() + 1).toInt()

            /** Raw by dimension. */
            val rawByDimension = if (effectiveStart.isAfter(effectiveEnd)) {
                /** Empty map. */
                emptyMap()
            } else {
                lensRepository.getDimensionSplitForRange(effectiveStart, effectiveEnd)
            }
            // Normalize sentinel keys (null, "", "unassigned") all into null so they
            // merge into a single "Unassigned" slice rather than appearing as separate entries.
            /** By dimension. */
            val byDimension = mutableMapOf<String?, Int>()
            rawByDimension.forEach { (key, minutes) ->
                /** Normalized key. */
                val normalizedKey = if (key.isNullOrBlank() || key == "unassigned") null else key
                byDimension[normalizedKey] = (byDimension[normalizedKey] ?: 0) + minutes
            }
            /** Total minutes. */
            val totalMinutes = byDimension.values.sum()
            /** Named keys. */
            val namedKeys = byDimension.keys.filterNotNull().sorted()
            /** Has unassigned. */
            val hasUnassigned = byDimension.containsKey(null)

            /** Can shift left. */
            val canShiftLeft = window != DimensionSplitWindow.ALL && effectiveStart.isAfter(firstEntry ?: effectiveStart)
            /** Can shift right. */
            val canShiftRight = window != DimensionSplitWindow.ALL && offset > 0

            logger.d(
                "LensViewModel.loadDimensionSplit",
                "Dimension split loaded",
                /** Map of. */
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
        /** Load dimension trend. */
        loadDimensionTrend()
    }

    // Public trigger methods — called from LensesScreen when opt-in pref is enabled
    /**
     * Trigger dim trend load.
     */
    fun triggerDimTrendLoad() {
        logger.d("LensViewModel.triggerDimTrendLoad", "Dimension trend chart load triggered")
        /** Load dimension trend. */
        loadDimensionTrend()
    }
    /**
     * Trigger daily timeline load.
     */
    fun triggerDailyTimelineLoad() {
        logger.d("LensViewModel.triggerDailyTimelineLoad", "Daily timeline chart load triggered")
        /** Load heatmap. */
        loadHeatmap()
    }
    /**
     * Trigger weekly pattern load.
     */
    fun triggerWeeklyPatternLoad(excludeEmptyDays: Boolean) {
        logger.d("LensViewModel.triggerWeeklyPatternLoad", "Weekly pattern chart load triggered", mapOf("excludeEmptyDays" to excludeEmptyDays))
        /** Load week grid. */
        loadWeekGrid(excludeEmptyDays)
    }
    /**
     * Trigger daily rhythm load.
     */
    fun triggerDailyRhythmLoad(excludeEmptyDays: Boolean) {
        logger.d("LensViewModel.triggerDailyRhythmLoad", "Daily rhythm chart load triggered", mapOf("excludeEmptyDays" to excludeEmptyDays))
        /** Load minute pattern. */
        loadMinutePattern(excludeEmptyDays)
    }

    private fun loadDimensionTrend() {
        viewModelScope.launch {
            /** Load dimension trend internal. */
            loadDimensionTrendInternal()
        }
    }

    private fun loadHeatmap() {
        viewModelScope.launch {
            /** Load heatmap internal. */
            loadHeatmapInternal()
        }
    }

    private fun loadWeekGrid(excludeEmptyDays: Boolean = false) {
        viewModelScope.launch {
            /** Load week grid internal. */
            loadWeekGridInternal(excludeEmptyDays)
        }
    }

    private fun loadMinutePattern(excludeEmptyDays: Boolean = false) {
        viewModelScope.launch {
            /** Load minute pattern internal. */
            loadMinutePatternInternal(excludeEmptyDays)
        }
    }

    private suspend fun loadDimensionTrendInternal() {
        try {
            /** Window. */
            val window = _uiState.value.dimensionTrend.window
            logger.d("LensViewModel.loadDimensionTrend", "Loading dimension trend blocks", mapOf("window" to window.name))
            /** Blocks. */
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
            /** Days. */
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
            /** Data. */
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
            /** Data. */
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
            /** Table. */
            val table = withContext(Dispatchers.IO) { lensRepository.getAverageDailyTimeTableData() }
            _uiState.update { it.copy(averageDailyTimeTable = table) }
            logger.d(
                "LensViewModel.loadAverageDailyTime",
                "Average daily time table loaded",
                /** Map of. */
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
                /** State now. */
                val stateNow = _uiState.value
                /** Resolve date range. */
                resolveDateRange(
                    anchorDate = stateNow.selectedDate,
                    mode = stateNow.selectedTimeMode,
                    window = stateNow.selectedTimeWindow,
                    pageIndex = stateNow.windowPageIndex,
                ) == context.resolvedRange
            },
            onBackfillReady = { summary ->
                _uiState.update { stateNow ->
                    /** If. */
                    if (stateNow.timeModuleHistorySummary?.totalDays == summary.totalDays) {
                        /** State now. */
                        stateNow
                    } else {
                        stateNow.copy(timeModuleHistorySummary = summary)
                    }
                }
                /** Pending limit. */
                val pendingLimit = pendingHistoryLimit
                /** If. */
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
                /** If. */
                if (!lensRepository.isDayDirty(dayKey)) {
                    logger.d("LensViewModel.refreshReflections", "Skipping reflection refresh for clean day", mapOf("dayKey" to dayKey))
                    return@launch
                }
                lensRepository.generateReflectionCards(dayKey)
                /** Reflections. */
                val reflections = lensRepository.observeReflections(dayKey).firstOrNull() ?: emptyList()
                /** Selected focus day key. */
                val selectedFocusDayKey = focusDateForRange(
                    /** Resolve date range. */
                    resolveDateRange(
                        anchorDate = _uiState.value.selectedDate,
                        mode = _uiState.value.selectedTimeMode,
                        window = _uiState.value.selectedTimeWindow,
                        pageIndex = _uiState.value.windowPageIndex,
                    ),
                ).format(DateTimeFormatter.ISO_LOCAL_DATE)
                /** If. */
                if (selectedFocusDayKey != dayKey) {
                    logger.d(
                        "LensViewModel.refreshReflections",
                        "Ignoring stale reflections refresh",
                        /** Map of. */
                        mapOf("dayKey" to dayKey, "selectedDayKey" to selectedFocusDayKey),
                    )
                    return@launch
                }
                _uiState.update { it.copy(reflections = reflections) }
                logger.d(
                    "LensViewModel.refreshReflections",
                    "Reflections refreshed",
                    /** Map of. */
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
        /** Anchor date. */
        anchorDate: LocalDate,
        /** Mode. */
        mode: LensTimeMode,
        /** Window. */
        window: LensTimeWindow,
        /** Page index. */
        pageIndex: Int,
    ): ResolvedLensWindowRange {
        /** Normalized page index. */
        val normalizedPageIndex = pageIndex.coerceAtLeast(0)
        /** Effective window. */
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
                /** If. */
                if (effectiveWindow == LensTimeWindow.ALL_PAST_DAYS) {
                    /** End date. */
                    val endDate = anchorDate.minusDays(1)
                    /** Start date. */
                    val startDate = endDate.minusDays((MAX_RANGE_DAYS - 1).toLong())
                    /** Resolved lens window range. */
                    ResolvedLensWindowRange(
                        mode = LensTimeMode.PAST,
                        window = LensTimeWindow.ALL_PAST_DAYS,
                        pageIndex = 0,
                        startDate = startDate,
                        endDate = endDate,
                    )
                } else {
                    /** Span days. */
                    val spanDays = spanDaysForWindow(effectiveWindow)
                    /** Shift days. */
                    val shiftDays = spanDays * normalizedPageIndex
                    /** End date. */
                    val endDate = anchorDate.minusDays(1 + shiftDays)
                    /** Start date. */
                    val startDate = endDate.minusDays(spanDays - 1)
                    /** Resolved lens window range. */
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
                /** Span days. */
                val spanDays = spanDaysForWindow(effectiveWindow)
                /** Shift days. */
                val shiftDays = spanDays * normalizedPageIndex
                /** Start date. */
                val startDate = anchorDate.plusDays(1 + shiftDays)
                /** End date. */
                val endDate = startDate.plusDays(spanDays - 1)
                /** Resolved lens window range. */
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
        /** Mode. */
        mode: LensTimeMode,
        /** Window. */
        window: LensTimeWindow,
        /** Page index. */
        pageIndex: Int,
    ): LensWindowNavigationState = when (mode) {
        LensTimeMode.TODAY -> LensWindowNavigationState(canGoPrevious = false, canGoNext = false)

        LensTimeMode.PAST -> {
            /** If. */
            if (window == LensTimeWindow.ALL_PAST_DAYS) {
                /** Lens window navigation state. */
                LensWindowNavigationState(canGoPrevious = false, canGoNext = false)
            } else {
                /** Lens window navigation state. */
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
    /** Is loading. */
    val isLoading: Boolean = true,
    /** Has error. */
    val hasError: Boolean = false,
    /** Error message. */
    val errorMessage: String? = null,
    /** Selected date. */
    val selectedDate: LocalDate = LocalDate.now(),
    /** Selected moment. */
    val selectedMoment: LensMoment = LensMoment.LIVE_DAY,
    /** Selected time mode. */
    val selectedTimeMode: LensTimeMode = LensTimeMode.TODAY,
    /** Selected time window. */
    val selectedTimeWindow: LensTimeWindow = LensTimeWindow.TODAY,
    /** Window page index. */
    val windowPageIndex: Int = 0,
    /** Can go to previous window page. */
    val canGoToPreviousWindowPage: Boolean = false,
    /** Can go to next window page. */
    val canGoToNextWindowPage: Boolean = false,
    /** Selected grouping. */
    val selectedGrouping: LensGrouping = LensGrouping.BY_MODULE,
    /** Planning data. */
    val planningData: PlanningLensData? = null,
    /** Plan completeness score. */
    val planCompletenessScore: Float = 0f,
    /** Reality data. */
    val realityData: RealityLensData? = null,
    /** Adherence score. */
    val adherenceScore: Float = 0f,
    /** Reflections. */
    val reflections: List<LensReflectionRecord> = emptyList(),
    /** Selected range summary. */
    val selectedRangeSummary: LensRangeSummary? = null,
    /** Time module history summary. */
    val timeModuleHistorySummary: TimeModuleHistorySummary? = null,
    /** Average daily time table. */
    val averageDailyTimeTable: AverageDailyTimeTableData? = null,
    /** Long horizon summaries. */
    val longHorizonSummaries: List<LensRangeSummary> = emptyList(),
    /** Daily focus averages. */
    val dailyFocusAverages: List<DailyFocusStat> = emptyList(),
    /** Daily tracked time stats. */
    val dailyTrackedTimeStats: List<DailyTrackedTimeStat> = emptyList(),
    /** Daily focused hours stats. */
    val dailyFocusedHoursStats: List<DailyFocusedHoursStat> = emptyList(),
    /** Dimension split. */
    val dimensionSplit: DimensionSplitState = DimensionSplitState(),
    /** Dimension trend. */
    val dimensionTrend: DimensionTrendState = DimensionTrendState(),
    /** Heatmap. */
    val heatmap: HeatmapState = HeatmapState(),
    /** Week grid. */
    val weekGrid: WeekGridState = WeekGridState(),
    /** Minute pattern. */
    val minutePattern: MinutePatternState = MinutePatternState(),
)
/**
 * LensMoment.
 */
enum class LensMoment  {
    /** Start day. */
    START_DAY,
    /** Live day. */
    LIVE_DAY,
    /** Close day. */
    CLOSE_DAY,
}
/**
 * LensGrouping.
 */
enum class LensGrouping  {
    /** Overall. */
    OVERALL,
    /** By module. */
    BY_MODULE,
    /** By dimension. */
    BY_DIMENSION,
}
/**
 * LensTimeMode.
 */
enum class LensTimeMode  {
    /** Today. */
    TODAY,
    /** Past. */
    PAST,
    /** Future. */
    FUTURE,
}
/**
 * LensTimeWindow.
 */
enum class LensTimeWindow {
    /** Today. */
    TODAY,
    /** Last day. */
    LAST_DAY,
    /** Last 7 days. */
    LAST_7_DAYS,
    /** Last 30 days. */
    LAST_30_DAYS,
    /** Last 90 days. */
    LAST_90_DAYS,
    /** Last 180 days. */
    LAST_180_DAYS,
    /** Last 365 days. */
    LAST_365_DAYS,
    /** All past days. */
    ALL_PAST_DAYS,
    /** Next day. */
    NEXT_DAY,
    /** Next 7 days. */
    NEXT_7_DAYS,
    /** Next 30 days. */
    NEXT_30_DAYS,
    /** Next 90 days. */
    NEXT_90_DAYS,
    /** Next 180 days. */
    NEXT_180_DAYS,
    /** Next 365 days. */
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
    /** Mode. */
    val mode: LensTimeMode,
    /** Window. */
    val window: LensTimeWindow,
    /** Page index. */
    val pageIndex: Int,
    /** Start date. */
    val startDate: LocalDate,
    /** End date. */
    val endDate: LocalDate,
    /** Total planned minutes. */
    val totalPlannedMinutes: Int,
    /** Total actual minutes. */
    val totalActualMinutes: Int,
    /** Total untracked minutes. */
    val totalUntrackedMinutes: Int,
    /** Total focus gap minutes. */
    val totalFocusGapMinutes: Int,
    /** Supplemental actual minutes. */
    val supplementalActualMinutes: Int = 0,
    /** Planned task minutes. */
    val plannedTaskMinutes: Int = 0,
    /** Planned habit minutes. */
    val plannedHabitMinutes: Int = 0,
    /** Planned time only minutes. */
    val plannedTimeOnlyMinutes: Int = 0,
    /** Unplanned day minutes. */
    val unplannedDayMinutes: Int = 0,
    /** Actual time only minutes. */
    val actualTimeOnlyMinutes: Int = 0,
    /** Actual task minutes. */
    val actualTaskMinutes: Int = 0,
    /** Actual habit minutes. */
    val actualHabitMinutes: Int = 0,
    /** Planned task count. */
    val plannedTaskCount: Int,
    /** Completed task count. */
    val completedTaskCount: Int,
    /** Missed task count. */
    val missedTaskCount: Int,
    /** Planned habit count. */
    val plannedHabitCount: Int,
    /** Completed habit count. */
    val completedHabitCount: Int,
    /** Missed habit count. */
    val missedHabitCount: Int,
    /** Average plan completeness. */
    val averagePlanCompleteness: Float,
    /** Average adherence. */
    val averageAdherence: Float,
    /** Planned by dimension. */
    val plannedByDimension: Map<String, Int>,
    /** Actual by dimension. */
    val actualByDimension: Map<String, Int>,
    /** Supplemental actual by dimension. */
    val supplementalActualByDimension: Map<String, Int> = emptyMap(),
    /** Planned tasks by dimension. */
    val plannedTasksByDimension: Map<String, Int>,
    /** Completed tasks by dimension. */
    val completedTasksByDimension: Map<String, Int>,
    /** Missed tasks by dimension. */
    val missedTasksByDimension: Map<String, Int>,
    /** Planned habits by dimension. */
    val plannedHabitsByDimension: Map<String, Int>,
    /** Completed habits by dimension. */
    val completedHabitsByDimension: Map<String, Int>,
    /** Missed habits by dimension. */
    val missedHabitsByDimension: Map<String, Int>,
    /** Trend points. */
    val trendPoints: List<LensTrendPoint>,
)
/**
 * ResolvedLensWindowRange.
 */
data class ResolvedLensWindowRange(
    /** Mode. */
    val mode: LensTimeMode,
    /** Window. */
    val window: LensTimeWindow,
    /** Page index. */
    val pageIndex: Int,
    /** Start date. */
    val startDate: LocalDate,
    /** End date. */
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
    /** W1. */
    W1(1),
    /** W7. */
    W7(7),
    /** W30. */
    W30(30),
    /** W90. */
    W90(90),
    /** W180. */
    W180(180),
    /** W365. */
    W365(365),
    /** All. */
    ALL(null),
}

/**
 * DimensionSplitState.
 */
data class DimensionSplitState(
    /** Window. */
    val window: DimensionSplitWindow = DimensionSplitWindow.W1,
    /** Window offset. */
    val windowOffset: Int = 0,
    /** First entry date. */
    val firstEntryDate: LocalDate? = null,
    /** Effective start. */
    val effectiveStart: LocalDate = LocalDate.now(),
    /** Effective end. */
    val effectiveEnd: LocalDate = LocalDate.now(),
    /** Requested start. */
    val requestedStart: LocalDate = LocalDate.now(),
    /** Requested end. */
    val requestedEnd: LocalDate = LocalDate.now(),
    /** By dimension. */
    val byDimension: Map<String?, Int> = emptyMap(),
    /** Total minutes. */
    val totalMinutes: Int = 0,
    /** Is clamped. */
    val isClamped: Boolean = false,
    /** Clamped days. */
    val clampedDays: Int = 0,
    /** Requested days. */
    val requestedDays: Int = 0,
    /** Can shift left. */
    val canShiftLeft: Boolean = false,
    /** Can shift right. */
    val canShiftRight: Boolean = false,
    /** Is loading. */
    val isLoading: Boolean = false,
)

/**
 * DimensionTrendWindow.
 */
enum class DimensionTrendWindow(val spanDays: Int) {
    /** W1. */
    W1(1),
    /** W7. */
    W7(7),
    /** W30. */
    W30(30),
    /** W90. */
    W90(90),
    /** W180. */
    W180(180),
    /** W365. */
    W365(365),
}

/**
 * DimensionTrendState.
 */
data class DimensionTrendState(
    /** Window. */
    val window: DimensionTrendWindow = DimensionTrendWindow.W1,
    /** Blocks. */
    val blocks: List<DimensionTrendBlock> = emptyList(),
    /** Is loading. */
    val isLoading: Boolean = false,
)

/**
 * HeatmapState.
 */
data class HeatmapState(
    /** Days. */
    val days: List<HeatmapDayData> = emptyList(),
    /** Is loading. */
    val isLoading: Boolean = false,
)

/**
 * WeekGridState.
 */
data class WeekGridState(
    /** Data. */
    val data: WeekGridData = WeekGridData(emptyList()),
    /** Is loading. */
    val isLoading: Boolean = false,
)

/**
 * MinutePatternState.
 */
data class MinutePatternState(
    /** Data. */
    val data: MinutePatternData = MinutePatternData(emptyList()),
    /** Is loading. */
    val isLoading: Boolean = false,
)
