//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.MetricWindowRow
import io.payanam.domain.repository.ScoreWindowRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Metrics available in the score-matrix dropdown (single select). */
enum class ScoreMetricColumn(val key: String) {
    SCORE("score"),
    RUNNING_AVG("running"),
    PROGRESS("progress"),
    STREAK_POS("streak"),
    STREAK_NET("net"),
    POS_CONTINUE("continue"),
}

/** One row of the score matrix: a dimension (or DAY) on its latest day. */
data class ScoreMatrixRow(
    val key: String,
    val label: String,
    val colorHex: String,
    val isDay: Boolean,
    val values: Map<ScoreMetricColumn, Double?>,
    /** 14-day series for the sparkline of the currently selected metric. */
    val sparkline: List<Double?>,
)

/** One radar axis: dimension with today's score and running average. */
data class RadarAxis(
    val key: String,
    val label: String,
    val colorHex: String,
    val today: Double?,
    val runningAvg: Double?,
)

/** Immutable state for the Lenses score matrix. */
data class LensHabitScoreUiState(
    val isLoading: Boolean = false,
    val windowStart: String = "",
    val windowEnd: String = "",
    val selectedMetric: ScoreMetricColumn = ScoreMetricColumn.SCORE,
    val rows: List<ScoreMatrixRow> = emptyList(),
    val dayRow: ScoreMatrixRow? = null,
    val radarAxes: List<RadarAxis> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class LensHabitScoreViewModel
    @Inject
    constructor(
        private val scoreWindowRepository: ScoreWindowRepository,
    ) : ViewModel() {
        private val logger = UnifiedLogger.getInstance()

        private val _uiState = MutableStateFlow(LensHabitScoreUiState())
        val uiState: StateFlow<LensHabitScoreUiState> = _uiState.asStateFlow()

        /** Load the matrix for the 14 days ending on [endDate] (default today). */
        fun loadWindow(endDate: LocalDate = LocalDate.now(), days: Int = 14) {
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true, error = null) }
                val end = endDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val start = endDate.minusDays((days - 1).toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE)
                logger.d(
                    "LensHabitScoreViewModel.loadWindow",
                    "Loading score matrix window",
                    mapOf("start" to start, "end" to end, "days" to days),
                )
                try {
                    val dims = scoreWindowRepository.getDimensionWindow(start, end)
                    val days = scoreWindowRepository.getDayWindow(start, end)
                    val dayRows = days.associateBy { it.dayKey }
                    val rows = buildDimensionRows(dims, dayRows, start, end)
                    val dayRow = buildDayRow(days, start, end)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            windowStart = start,
                            windowEnd = end,
                            rows = rows,
                            dayRow = dayRow,
                            radarAxes = buildRadarAxes(dims),
                        )
                    }
                    logger.d(
                        "LensHabitScoreViewModel.loadWindow",
                        "Score matrix loaded",
                        mapOf("rows" to rows.size, "days" to days.size),
                    )
                } catch (e: Exception) {
                    logger.e("LensHabitScoreViewModel.loadWindow", "Failed to load score matrix", e)
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
            }
        }

        fun selectMetric(metric: ScoreMetricColumn) {
            logger.d("LensHabitScoreViewModel.selectMetric", "Metric selected", mapOf("metric" to metric.key))
            _uiState.update { it.copy(selectedMetric = metric) }
        }

        private fun buildDimensionRows(
            dims: List<MetricWindowRow>,
            dayRows: Map<String, MetricWindowRow>,
            start: String,
            end: String,
        ): List<ScoreMatrixRow> {
            val byDim = dims.groupBy { it.key }
            return byDim.map { (key, rows) ->
                val latest = rows.maxByOrNull { it.dayKey } ?: return@map null
                ScoreMatrixRow(
                    key = key,
                    label = latest.label,
                    colorHex = dimensionColorHex(key),
                    isDay = false,
                    values = metricValues(latest),
                    sparkline = sparklineFor(rows, start, end),
                )
            }.filterNotNull()
        }

        private fun buildDayRow(
            days: List<MetricWindowRow>,
            start: String,
            end: String,
        ): ScoreMatrixRow? {
            val latest = days.maxByOrNull { it.dayKey } ?: return null
            return ScoreMatrixRow(
                key = "DAY",
                label = "DAY",
                colorHex = "#818CF8",
                isDay = true,
                values = metricValues(latest),
                sparkline = sparklineFor(days, start, end),
            )
        }

        private fun metricValues(row: MetricWindowRow): Map<ScoreMetricColumn, Double?> = mapOf(
            ScoreMetricColumn.SCORE to row.score,
            ScoreMetricColumn.RUNNING_AVG to row.runningAvg,
            ScoreMetricColumn.PROGRESS to row.progress,
            ScoreMetricColumn.STREAK_POS to row.streakPos.toDouble(),
            ScoreMetricColumn.STREAK_NET to row.streakNet.toDouble(),
            ScoreMetricColumn.POS_CONTINUE to row.posContinue.toDouble(),
        )

        /** Dense series across the window (null where the dimension had no row that day). */
        private fun sparklineFor(rows: List<MetricWindowRow>, start: String, end: String): List<Double?> {
            val byDay = rows.associateBy { it.dayKey }
            var cursor = LocalDate.parse(start)
            val last = LocalDate.parse(end)
            val out = mutableListOf<Double?>()
            while (!cursor.isAfter(last)) {
                out.add(byDay[cursor.format(DateTimeFormatter.ISO_LOCAL_DATE)]?.score)
                cursor = cursor.plusDays(1)
            }
            return out
        }

        /** Latest row per dimension → radar axis (today's score vs running avg). */
        private fun buildRadarAxes(dims: List<MetricWindowRow>): List<RadarAxis> =
            dims
                .groupBy { it.key }
                .map { (key, rows) ->
                    val latest = rows.maxByOrNull { it.dayKey } ?: return@map null
                    RadarAxis(
                        key = key,
                        label = latest.label,
                        colorHex = dimensionColorHex(key),
                        today = latest.score,
                        runningAvg = latest.runningAvg,
                    )
                }
                .filterNotNull()

        private fun dimensionColorHex(dimensionId: String): String =
            DimensionTaxonomyCatalog.fromAnyId(dimensionId)?.defaultColorHex ?: "#9AA0AA"
    }
