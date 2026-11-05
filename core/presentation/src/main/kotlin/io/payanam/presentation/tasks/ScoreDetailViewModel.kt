//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.tasks

import io.payanam.domain.model.MetricWindowRow
import io.payanam.domain.repository.ScoreWindowRepository
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Detail-page source: DAY (day_metrics) or a dimension (dimension_metrics). */
enum class ScoreDetailType {
    DAY,
    DIMENSION,
}

/**
 * UI state for the score-detail screen: loading/error flags, metric rows for
 * the selected layer, and the current window (size + end date) plus view mode.
 */
data class ScoreDetailUiState(
    val isLoading: Boolean = true,
    val rows: List<MetricWindowRow> = emptyList(),
    val windowSizeDays: Int = 7,
    val windowEnd: LocalDate = LocalDate.now(),
    val showChartView: Boolean = true,
    val error: String? = null,
)

/**
 * Score-detail ViewModel: loads day/dimension metric windows, shifts the
 * window or changes its size, and toggles the chart/table view.
 */
class ScoreDetailViewModel(
    private val scoreWindowRepository: ScoreWindowRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val logger = AppLogger.current

    private val _uiState = MutableStateFlow(ScoreDetailUiState())
    val uiState: StateFlow<ScoreDetailUiState> = _uiState.asStateFlow()

    /**
     * Loads the metric rows for [type]/[key] over the current window.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; broad catch intentional
    fun load(
        type: ScoreDetailType,
        key: String,
    ) {
        scope.launch {
            val state = _uiState.value
            val end = state.windowEnd
            val start = scoreWindowRepository.resolveWindowStart(end, state.windowSizeDays, currentType, currentKey)
            val startStr = start.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val endStr = end.format(DateTimeFormatter.ISO_LOCAL_DATE)
            _uiState.update { it.copy(isLoading = true, error = null) }
            logger.d(
                "ScoreDetailViewModel.load",
                "Loading score detail window",
                mapOf("type" to type.name, "key" to key, "start" to startStr, "end" to endStr),
            )
            try {
                val rows =
                    when (type) {
                        ScoreDetailType.DAY -> {
                            scoreWindowRepository.getDayWindow(startStr, endStr)
                        }

                        ScoreDetailType.DIMENSION -> {
                            scoreWindowRepository
                                .getDimensionWindow(startStr, endStr)
                                .filter { it.key == key }
                        }
                    }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        rows = rows,
                        windowSizeDays = state.windowSizeDays,
                        windowEnd = state.windowEnd,
                    )
                }
                logger.d(
                    "ScoreDetailViewModel.load",
                    "Score detail loaded",
                    mapOf("type" to type.name, "rows" to rows.size),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("ScoreDetailViewModel.load", "Failed to load score detail", e)
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    /**
     * Slides the window by [days] and reloads its rows.
     */
    fun shiftWindow(days: Int) {
        logger.d(
            "ScoreDetailViewModel.shiftWindow",
            "Window shifted",
            mapOf("days" to days, "type" to currentType.name, "key" to currentKey),
        )
        _uiState.update { it.copy(windowEnd = it.windowEnd.plusDays(days.toLong())) }
        // Reload with the new end date: re-enter load with current state
        scope.launch {
            val state = _uiState.value
            val end = state.windowEnd
            val start = scoreWindowRepository.resolveWindowStart(end, state.windowSizeDays, currentType, currentKey)
            val rows =
                when (currentType) {
                    ScoreDetailType.DAY -> {
                        scoreWindowRepository.getDayWindow(
                            start.format(DateTimeFormatter.ISO_LOCAL_DATE),
                            end.format(DateTimeFormatter.ISO_LOCAL_DATE),
                        )
                    }

                    ScoreDetailType.DIMENSION -> {
                        scoreWindowRepository
                            .getDimensionWindow(
                                start.format(DateTimeFormatter.ISO_LOCAL_DATE),
                                end.format(DateTimeFormatter.ISO_LOCAL_DATE),
                            ).filter { it.key == currentKey }
                    }
                }
            _uiState.update { it.copy(rows = rows, isLoading = false) }
        }
    }

    /**
     * Sets the visible range to [days] days (0 = all-time) and reloads.
     */
    fun setWindowSize(days: Int) {
        logger.d(
            "ScoreDetailViewModel.setWindowSize",
            "Range size selected",
            mapOf("days" to days, "type" to currentType.name, "key" to currentKey),
        )
        _uiState.update { it.copy(windowSizeDays = days) }
        scope.launch {
            val state = _uiState.value
            val end = state.windowEnd
            val start = scoreWindowRepository.resolveWindowStart(end, state.windowSizeDays, currentType, currentKey)
            val rows =
                when (currentType) {
                    ScoreDetailType.DAY -> {
                        scoreWindowRepository.getDayWindow(
                            start.format(DateTimeFormatter.ISO_LOCAL_DATE),
                            end.format(DateTimeFormatter.ISO_LOCAL_DATE),
                        )
                    }

                    ScoreDetailType.DIMENSION -> {
                        scoreWindowRepository
                            .getDimensionWindow(
                                start.format(DateTimeFormatter.ISO_LOCAL_DATE),
                                end.format(DateTimeFormatter.ISO_LOCAL_DATE),
                            ).filter { it.key == currentKey }
                    }
                }
            _uiState.update { it.copy(rows = rows, isLoading = false) }
        }
    }

    /**
     * Toggles the activity section between chart and table rendering.
     */
    fun setChartView(show: Boolean) {
        _uiState.update { it.copy(showChartView = show) }
    }

    /**
     * Snaps the window back to end at today and reloads.
     */
    fun goToday() {
        logger.d(
            "ScoreDetailViewModel.goToday",
            "Window reset to today",
            mapOf("type" to currentType.name, "key" to currentKey),
        )
        _uiState.update { it.copy(windowEnd = LocalDate.now()) }
        scope.launch {
            val state = _uiState.value
            val end = state.windowEnd
            val start = scoreWindowRepository.resolveWindowStart(end, state.windowSizeDays, currentType, currentKey)
            val rows =
                when (currentType) {
                    ScoreDetailType.DAY -> {
                        scoreWindowRepository.getDayWindow(
                            start.format(DateTimeFormatter.ISO_LOCAL_DATE),
                            end.format(DateTimeFormatter.ISO_LOCAL_DATE),
                        )
                    }

                    ScoreDetailType.DIMENSION -> {
                        scoreWindowRepository
                            .getDimensionWindow(
                                start.format(DateTimeFormatter.ISO_LOCAL_DATE),
                                end.format(DateTimeFormatter.ISO_LOCAL_DATE),
                            ).filter { it.key == currentKey }
                    }
                }
            _uiState.update { it.copy(rows = rows, isLoading = false) }
        }
    }

    // Set by the screen on first composition; used by window shifts.
    var currentType: ScoreDetailType = ScoreDetailType.DAY
    var currentKey: String = "DAY"

    /**
     * Releases the coroutine scope this ViewModel created.
     *
     * `onCleared` used to do this: it was the androidx lifecycle hook that ran when the framework dropped the
     * ViewModel, and this class no longer has one. The platform calls this at the same point - Android through
     * the release action it registers when it retains the ViewModel, the desktop from a `DisposableEffect`.
     */
    fun dispose() {
        scope.cancel()
    }
}

/** Window start: 0 days = "All" → earliest logged day for the layer
 *  (resolved from the repository — per-dimension mapped habits for the
 *  dimension layer, all habits for the DAY layer); otherwise end minus
 *  (days-1). Hard date fallback only when no metrics exist at all. */
private suspend fun ScoreWindowRepository.resolveWindowStart(
    end: LocalDate,
    windowSizeDays: Int,
    type: ScoreDetailType,
    key: String,
): LocalDate {
    if (windowSizeDays > 0) return end.minusDays((windowSizeDays - 1).toLong())
    val earliest =
        when (type) {
            ScoreDetailType.DAY -> earliestDayKey()
            ScoreDetailType.DIMENSION -> earliestDimensionDayKey(key)
        }
    return earliest?.take(DAY_KEY_LENGTH)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: FALLBACK_WINDOW_FLOOR
}

/** A stored day key is an ISO date, so its date part is exactly the first ten characters. */
private const val DAY_KEY_LENGTH = 10

/** The floor a window falls back to when the database holds no row to point at - before any Payanam data can exist. */
@Suppress("MagicNumber") // A date literal cannot be a compile-time constant, and the value IS the meaning.
private val FALLBACK_WINDOW_FLOOR: LocalDate = LocalDate.of(2020, 1, 1)

/**
 * The one-line entry point a platform's destination body calls to get the ScoreDetailViewModel.
 *
 * The repository comes from [LocalDataLayer], which the platform installed at startup with its own
 * implementation, so a call site on either platform contains no wiring.
 */
fun scoreDetailViewModel(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)): ScoreDetailViewModel =
    ScoreDetailViewModel(
        scoreWindowRepository = LocalDataLayer.current.scoreWindows,
        scope = scope,
    )
