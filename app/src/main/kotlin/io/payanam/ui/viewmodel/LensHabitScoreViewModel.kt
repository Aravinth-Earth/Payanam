//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("UndocumentedPublicProperty")

package io.payanam.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.database.event.ScoreChangeEventBus
import io.payanam.domain.model.MetricWindowRow
import io.payanam.domain.repository.ScoreWindowRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Metrics available in the score-matrix dropdown (single select). */
enum class ScoreMetricColumn(val key: String) {
    /** Score. */
    SCORE("score"),
    /** Running avg. */
    RUNNING_AVG("running"),
    /** Progress. */
    PROGRESS("progress"),
    /** Streak pos. */
    STREAK_POS("streak"),
    /** Streak net. */
    STREAK_NET("net"),
    /** Pos continue. */
    POS_CONTINUE("continue"),
}

/** One row of the score matrix: a dimension (or DAY) on its latest day. */
data class ScoreMatrixRow(
    /** Key. */
    val key: String,
    /** Label. */
    val label: String,
    /** Color hex. */
    val colorHex: String,
    /** Is day. */
    val isDay: Boolean,
    /** Values. */
    val values: Map<ScoreMetricColumn, Double?>,
    /** 14-day series for the sparkline of the currently selected metric. */
    val sparkline: List<Double?>,
)

/** One radar axis: dimension with today's score and running average. */
data class RadarAxis(
    /** Key. */
    val key: String,
    /** Label. */
    val label: String,
    /** Color hex. */
    val colorHex: String,
    /** Values per metric column: today's value + running avg for that metric. */
    val values: Map<ScoreMetricColumn, Pair<Double?, Double?>>,
    /** Resolved display label (user-custom or taxonomy fallback). */
    val displayLabel: String = label,
) {
    /**
     * Today.
     */
    fun today(metric: ScoreMetricColumn): Double? = values[metric]?.first
    /** The running-average component of [metric] for this row. */
    fun runningAvg(metric: ScoreMetricColumn): Double? = values[metric]?.second
}

/** Immutable state for the Lenses score matrix. */
data class LensHabitScoreUiState(
    /** Is loading. */
    val isLoading: Boolean = false,
    /** Window start. */
    val windowStart: String = "",
    /** Window end. */
    val windowEnd: String = "",
    /** Selected metric. */
    val selectedMetric: ScoreMetricColumn = ScoreMetricColumn.PROGRESS,
    /** Rows. */
    val rows: List<ScoreMatrixRow> = emptyList(),
    /** Day row. */
    val dayRow: ScoreMatrixRow? = null,
    /** Radar axes. */
    val radarAxes: List<RadarAxis> = emptyList(),
    /** Ordinal rank of each row's selected-metric value across its OWN full
     *  history of unique values. Keyed by row key ("DAY" for the day row).
     *  Format "X/Y" where Y = count of distinct historical values. */
    val rankByKey: Map<String, String> = emptyMap(),
    /** Error. */
    val error: String? = null,
)

@HiltViewModel
/**
 * LensHabitScoreViewModel.
 */
class LensHabitScoreViewModel
    @Inject
    /** Constructor. */
    constructor(
        private val scoreWindowRepository: ScoreWindowRepository,
        private val scoreChangeEventBus: ScoreChangeEventBus,
    ) : ViewModel() {
        private val logger = UnifiedLogger.getInstance()

        private val _uiState = MutableStateFlow(LensHabitScoreUiState())
        /** Ui state. */
        val uiState: StateFlow<LensHabitScoreUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                scoreChangeEventBus.events.collect { date ->
                    logger.d(
                        "LensHabitScoreViewModel",
                        "Score change event received; refreshing matrix",
                        /** Map of. */
                        mapOf("date" to date.toString()),
                    )
                    /** Load window. */
                    loadWindow()
                }
            }
        }

        /** Load the matrix for the 14 days ending on [endDate] (default today).
         *  The 14-day window drives the displayed rows + sparklines; the full
         *  history (from each row's earliest day) drives the ordinal rank. */
        fun loadWindow(endDate: LocalDate = LocalDate.now(), days: Int = 14, metric: ScoreMetricColumn = _uiState.value.selectedMetric) {
            /** T0. */
            val t0 = System.currentTimeMillis()
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true, error = null) }
                /** End. */
                val end = endDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
                /** Start. */
                val start = endDate.minusDays((days - 1).toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE)
                logger.d(
                    "LensHabitScoreViewModel.loadWindow",
                    "Score matrix load started",
                    /** Map of. */
                    mapOf("start" to start, "end" to end, "days" to days, "metric" to metric.key),
                )
                try {
                    // 14-day window for display
                    /** T disp start. */
                    val tDispStart = System.currentTimeMillis()
                    /** Dims. */
                    val dims = scoreWindowRepository.getDimensionWindow(start, end)
                    /** Days. */
                    val days = scoreWindowRepository.getDayWindow(start, end)
                    /** T disp end. */
                    val tDispEnd = System.currentTimeMillis()
                    // Full history for ordinal rank (each row from its earliest day)
                    /** Dim earliest. */
                    val dimEarliest = scoreWindowRepository.earliestDimensionDayKey() ?: start
                    /** Day earliest. */
                    val dayEarliest = scoreWindowRepository.earliestDayKey() ?: start
                    /** T hist start. */
                    val tHistStart = System.currentTimeMillis()
                    /** Dim hist. */
                    val dimHist = scoreWindowRepository.getDimensionWindow(dimEarliest, end)
                    /** Day hist. */
                    val dayHist = scoreWindowRepository.getDayWindow(dayEarliest, end)
                    /** T hist end. */
                    val tHistEnd = System.currentTimeMillis()
                    logger.d(
                        "LensHabitScoreViewModel.loadWindow",
                        "Rank history loaded from store",
                        /** Map of. */
                        mapOf(
                            "displayQueryMs" to (tDispEnd - tDispStart),
                            "fullHistoryQueryMs" to (tHistEnd - tHistStart),
                            "dimEarliest" to dimEarliest,
                            "dayEarliest" to dayEarliest,
                            "dimHistRows" to dimHist.size,
                            "dayHistRows" to dayHist.size,
                            "dimHistDays" to dimHist.map { it.dayKey }.distinct().size,
                        ),
                    )
                    /** History. */
                    val history = buildRankHistory(dimHist, dayHist)
                    rankHistory = history
                    /** Day rows. */
                    val dayRows = days.associateBy { it.dayKey }
                    /** Rows. */
                    val rows = buildDimensionRows(dims, dayRows, start, end)
                    /** Day row. */
                    val dayRow = buildDayRow(days, start, end)
                    /** T rank start. */
                    val tRankStart = System.currentTimeMillis()
                    /** Rank by key. */
                    val rankByKey = computeRankMap(history, metric)
                    /** T rank end. */
                    val tRankEnd = System.currentTimeMillis()
                    /** T update start. */
                    val tUpdateStart = System.currentTimeMillis()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            windowStart = start,
                            windowEnd = end,
                            rows = rows,
                            dayRow = dayRow,
                            radarAxes = buildRadarAxes(dims),
                            rankByKey = rankByKey,
                        )
                    }
                    /** T update end. */
                    val tUpdateEnd = System.currentTimeMillis()
                    logger.d(
                        "LensHabitScoreViewModel.loadWindow",
                        "Rank recomputed for window",
                        /** Map of. */
                        mapOf(
                            "metric" to metric.key,
                            "rankKeys" to rankByKey.size,
                            "sampleDay" to (rankByKey["DAY"] ?: "none"),
                        ),
                    )
                    logger.d(
                        "LensHabitScoreViewModel.loadWindow",
                        "Score matrix state published",
                        /** Map of. */
                        mapOf(
                            "computeRankMs" to (tRankEnd - tRankStart),
                            "uiStateUpdateMs" to (tUpdateEnd - tUpdateStart),
                            "totalLoadMs" to (tUpdateEnd - t0),
                            "historyKeys" to history.size,
                        ),
                    )
                } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                    logger.e("LensHabitScoreViewModel.loadWindow", "Failed to load score matrix", e)
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
            }
        }

        /**
         * Switch the active metric and reload the matrix for it. Reloading
         * (rather than recomputing rank against the cached history) guarantees
         * the published rank matches the metric the user selected, even if a
         * prior [loadWindow] is still in flight.
         */
        fun selectMetric(metric: ScoreMetricColumn) {
            logger.d("LensHabitScoreViewModel.selectMetric", "Metric selected", mapOf("metric" to metric.key))
            _uiState.update { it.copy(selectedMetric = metric) }
            /** Load window. */
            loadWindow(metric = metric)
        }

        /** Cached full-history values per row key, per metric. Populated in loadWindow. */
        private var rankHistory: Map<String, Map<ScoreMetricColumn, List<Double>>> = emptyMap()

        /**
         * Group full-history rows into per-row, per-metric value lists.
         * The DAY pseudo-row is included so it can be ranked alongside dimensions.
         */
        private fun buildRankHistory(
            dimHist: List<MetricWindowRow>,
            dayHist: List<MetricWindowRow>,
        ): Map<String, Map<ScoreMetricColumn, List<Double>>> {
            /** Out. */
            val out = mutableMapOf<String, MutableMap<ScoreMetricColumn, MutableList<Double>>>()
            dimHist.forEach { row ->
                /** M. */
                val m = out.getOrPut(row.key) { mutableMapOf() }
                m.accumulate(row)
            }
            dayHist.forEach { row ->
                /** M. */
                val m = out.getOrPut("DAY") { mutableMapOf() }
                m.accumulate(row)
            }
            return out
        }

        /** Appends a row's 6 metric values into this accumulator map (one list per metric). */
        private fun MutableMap<ScoreMetricColumn, MutableList<Double>>.accumulate(row: MetricWindowRow) {
            this[ScoreMetricColumn.SCORE]?.add(row.score) ?: put(ScoreMetricColumn.SCORE, mutableListOf(row.score))
            this[ScoreMetricColumn.RUNNING_AVG]?.add(row.runningAvg) ?: put(ScoreMetricColumn.RUNNING_AVG, mutableListOf(row.runningAvg))
            this[ScoreMetricColumn.PROGRESS]?.add(row.progress) ?: put(ScoreMetricColumn.PROGRESS, mutableListOf(row.progress))
            this[ScoreMetricColumn.STREAK_POS]?.add(row.streakPos.toDouble()) ?: put(ScoreMetricColumn.STREAK_POS, mutableListOf(row.streakPos.toDouble()))
            this[ScoreMetricColumn.STREAK_NET]?.add(row.streakNet.toDouble()) ?: put(ScoreMetricColumn.STREAK_NET, mutableListOf(row.streakNet.toDouble()))
            this[ScoreMetricColumn.POS_CONTINUE]?.add(row.posContinue.toDouble()) ?: put(ScoreMetricColumn.POS_CONTINUE, mutableListOf(row.posContinue.toDouble()))
        }

        /**
         * Ordinal rank of each row's [metric] value across its OWN full history.
         * Denominator = count of DISTINCT historical values (repeats collapsed).
         * Highest value → #1. Ties share the same rank (dense).
         * Returns "X/Y" keyed by row key.
         */
        private fun computeRankMap(
            history: Map<String, Map<ScoreMetricColumn, List<Double>>>,
            /** Metric. */
            metric: ScoreMetricColumn,
        ): Map<String, String> {
            /** Result. */
            val result = mutableMapOf<String, String>()
            history.forEach { (key, byMetric) ->
                /** Series. */
                val series = byMetric[metric] ?: return@forEach
                // Today = latest value in the chronological history list.
                /** Today. */
                val today = series.lastOrNull() ?: return@forEach
                /** Unique. */
                val unique = series.distinct().sortedDescending()
                /** Y. */
                val y = unique.size
                /** Rank. */
                val rank = unique.indexOfFirst { it == today }.let { if (it < 0) y else it + 1 }
                result[key] = "$rank/$y"
                logger.d(
                    "LensHabitScoreViewModel.computeRankMap",
                    "Ordinal rank derived",
                    /** Map of. */
                    mapOf(
                        "key" to key,
                        "metric" to metric.key,
                        "today" to String.format(Locale.US, "%.5f", today),
                        "historySize" to series.size,
                        "uniqueValues" to y,
                        "rank" to "$rank/$y",
                    ),
                )
            }
            return result
        }

        /** Builds display rows for each dimension in [dims], attaching the selected metric, sparkline, and rank. */
        private fun buildDimensionRows(
            dims: List<MetricWindowRow>,
            dayRows: Map<String, MetricWindowRow>,
            /** Start. */
            start: String,
            /** End. */
            end: String,
        ): List<ScoreMatrixRow> {
            /** By dim. */
            val byDim = dims.groupBy { it.key }
            return byDim.map { (key, rows) ->
                /** Latest. */
                val latest = rows.maxByOrNull { it.dayKey } ?: return@map null
                /** Score matrix row. */
                ScoreMatrixRow(
                    key = key,
                    label = dimensionLabel(key),
                    colorHex = dimensionColorHex(key),
                    isDay = false,
                    values = metricValues(latest),
                    sparkline = sparklineFor(rows, start, end),
                )
            }.filterNotNull()
        }

        /** Aggregates the DAY pseudo-row across all dimensions for the window. */
        private fun buildDayRow(
            days: List<MetricWindowRow>,
            /** Start. */
            start: String,
            /** End. */
            end: String,
        ): ScoreMatrixRow? {
            /** Latest. */
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

        /** Extracts all 6 self-gov metric values from a row into a column→value map. */
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
            /** By day. */
            val byDay = rows.associateBy { it.dayKey }
            /** Cursor. */
            var cursor = LocalDate.parse(start)
            /** Last. */
            val last = LocalDate.parse(end)
            /** Out. */
            val out = mutableListOf<Double?>()
            /** While. */
            while (!cursor.isAfter(last)) {
                out.add(byDay[cursor.format(DateTimeFormatter.ISO_LOCAL_DATE)]?.score)
                cursor = cursor.plusDays(1)
            }
            return out
        }

        /** Latest row per dimension → radar axis with ALL metric pairs
         *  (today + running avg per column) so the radar can follow the
         *  matrix's selected metric. */
        private fun buildRadarAxes(dims: List<MetricWindowRow>): List<RadarAxis> =
            /** Dims. */
            dims
                .groupBy { it.key }
                .map { (key, rows) ->
                    /** Latest. */
                    val latest = rows.maxByOrNull { it.dayKey } ?: return@map null
                    /** Radar axis. */
                    RadarAxis(
                        key = key,
                        label = dimensionLabel(key),
                        colorHex = dimensionColorHex(key),
                        values =
                            /** Map of. */
                            mapOf(
                                ScoreMetricColumn.SCORE to (latest.score to latest.runningAvg),
                                ScoreMetricColumn.RUNNING_AVG to (latest.runningAvg to null),
                                ScoreMetricColumn.PROGRESS to (latest.progress to null),
                                ScoreMetricColumn.STREAK_POS to (latest.streakPos.toDouble() to null),
                                ScoreMetricColumn.STREAK_NET to (latest.streakNet.toDouble() to null),
                                ScoreMetricColumn.POS_CONTINUE to (latest.posContinue.toDouble() to null),
                            ),
                    )
                }
                .filterNotNull()

        /** Taxonomy fallback label; user-custom labels are layered at the UI. */
        private fun dimensionLabel(dimensionId: String): String =
            DimensionTaxonomyCatalog.fromAnyId(dimensionId)?.fallbackLabel ?: dimensionId

        /** Default color hex for a dimension (taxonomy or fallback gray). */
        private fun dimensionColorHex(dimensionId: String): String =
            DimensionTaxonomyCatalog.fromAnyId(dimensionId)?.defaultColorHex ?: "#9AA0AA"
    }
