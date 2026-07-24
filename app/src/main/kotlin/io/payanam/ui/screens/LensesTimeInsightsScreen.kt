//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottomAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStartAxis
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.Scroll
import com.patrykandpatrick.vico.core.cartesian.axis.Axis
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.ChartValues
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.ui.viewmodel.LensUiState
import io.payanam.ui.viewmodel.LocalAppPreferences
import io.payanam.ui.viewmodel.TimeModuleDayMetric
import io.payanam.ui.viewmodel.TimeModuleHistorySummary
import io.payanam.ui.viewmodel.colorForDimension
import io.payanam.ui.viewmodel.colorForDimensionId
import io.payanam.ui.viewmodel.labelForDimension
import io.payanam.ui.viewmodel.labelForDimensionId
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.round

private const val LENS_CHART_VISIBLE_DAYS = 7
private val lensDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
private const val LENS_SCORE_SCALE = 100000.0

@Immutable
private data class MetricSeries(
    val id: String,
    val label: String,
    val color: Color,
    val values: List<Double>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LensesTimeInsightsScreen(
    uiState: LensUiState,
    onBack: () -> Unit,
) {
    val logger = remember { UnifiedLogger.getInstance() }
    val history = uiState.timeModuleHistorySummary
    val appPrefs = LocalAppPreferences.current
    val scoreCardsEnabled = appPrefs.chartTimeScoreCardsEnabled
    val scoreCardsVisible = scoreCardsEnabled && (appPrefs.chartTimeOverallScoreCardEnabled || appPrefs.chartTimeDimensionScoreCardsEnabled)
    val lineGraphsEnabled = appPrefs.chartTimeLineGraphsEnabled
    val dailyScoreTrendEnabled = lineGraphsEnabled && appPrefs.chartTimeDailyScoreTrendEnabled
    val progressTrendEnabled = lineGraphsEnabled && appPrefs.chartTimeProgressTrendEnabled
    val historicalRankingEnabled = lineGraphsEnabled && appPrefs.chartTimeHistoricalRankingEnabled
    val momentumStreakEnabled = lineGraphsEnabled && appPrefs.chartTimeMomentumStreakEnabled
    val visibleLineGraphs = dailyScoreTrendEnabled || progressTrendEnabled || historicalRankingEnabled || momentumStreakEnabled
    val hasVisibleCharts = scoreCardsVisible || visibleLineGraphs
    LaunchedEffect(history?.currentDayKey, history?.totalDays) {
        logger.d(
            "LensesTimeInsightsScreen",
            "Opened time module insights full screen",
            mapOf(
                "currentDayKey" to (history?.currentDayKey ?: "none"),
                "totalDays" to (history?.totalDays ?: 0),
            ),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.loc_time_insights)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(id = R.string.loc_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (!hasVisibleCharts) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(id = R.string.loc_lens_time_charts_disabled_hint))
            }
            return@Scaffold
        }

        if (history == null || history.metrics.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(id = R.string.loc_lens_time_no_history))
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (scoreCardsVisible) {
                TimeHistoryQuickStatsRow(history = history)
            }
            if (visibleLineGraphs) {
                Text(
                    text = stringResource(id = R.string.loc_lens_time_all_history_days_line, history.totalDays),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LensesTimeInlineCharts(
                    history = history,
                    useLazyContainer = true,
                    showDailyScoreTrend = dailyScoreTrendEnabled,
                    showProgressTrend = progressTrendEnabled,
                    showHistoricalRanking = historicalRankingEnabled,
                    showMomentumStreak = momentumStreakEnabled,
                )
            }
        }
    }
}

@Composable
internal fun TimeHistoryQuickStatsRow(history: TimeModuleHistorySummary) {
    val orderedMetrics = remember(history.metrics) { history.metrics.sortedByDescending { it.dayKey } }
    val current = orderedMetrics.firstOrNull()
    val scoreValues = orderedMetrics.map { it.dayScore }
    val progressValues = orderedMetrics.map { it.progressDelta }
    val streakValues = orderedMetrics.map { it.progressStreak.toDouble() }
    val scoreRank = current?.let { denseRankDescending(it.dayScore, scoreValues) } ?: 0
    val progressRank = current?.let { denseRankDescending(it.progressDelta, progressValues) } ?: 0
    val streakRank = current?.let { denseRankDescending(it.progressStreak.toDouble(), streakValues) } ?: 0
    val scoreUniqueTotal = scoreValues.toSet().size
    val progressUniqueTotal = progressValues.toSet().size
    val streakUniqueTotal = streakValues.toSet().size

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TimeKpiCard(
            modifier = Modifier.weight(1f),
            title = stringResource(id = R.string.loc_lens_time_day_score_line, formatLensScore(current?.dayScore ?: 0.0)),
            subtitle = stringResource(id = R.string.loc_lens_rank_short_line, scoreRank, scoreUniqueTotal),
        )
        TimeKpiCard(
            modifier = Modifier.weight(1f),
            title = stringResource(id = R.string.loc_lens_time_progress_line, formatSignedLensScore(current?.progressDelta ?: 0.0)),
            subtitle = stringResource(id = R.string.loc_lens_rank_short_line, progressRank, progressUniqueTotal),
        )
        TimeKpiCard(
            modifier = Modifier.weight(1f),
            title = stringResource(id = R.string.loc_lens_time_streak_line, current?.progressStreak ?: 0),
            subtitle = stringResource(id = R.string.loc_lens_rank_short_line, streakRank, streakUniqueTotal),
        )
    }
}

@Composable
internal fun LensesTimeInlineCharts(history: TimeModuleHistorySummary) {
    LensesTimeInlineCharts(
        history = history,
        useLazyContainer = false,
        onRequestMoreHistory = {},
    )
}

@Composable
internal fun LensesTimeInlineCharts(
    history: TimeModuleHistorySummary,
    useLazyContainer: Boolean,
    onRequestMoreHistory: () -> Unit = {},
    showDailyScoreTrend: Boolean = true,
    showProgressTrend: Boolean = true,
    showHistoricalRanking: Boolean = true,
    showMomentumStreak: Boolean = true,
) {
    val appPrefs = LocalAppPreferences.current
    val orderedMetrics = remember(history.metrics) { history.metrics.sortedByDescending { it.dayKey } }
    val ascendingMetrics = remember(orderedMetrics) { orderedMetrics.asReversed() }
    val dimensionIds = remember(orderedMetrics) {
        orderedMetrics
            .flatMap { it.perDimensionScores.keys }
            .toSet()
            .toList()
    }
    val xLabels = remember(orderedMetrics) { buildXAxisLabels(orderedMetrics) }
    val fallbackDimensionLabel = stringResource(id = R.string.loc_dimension_fallback_unassigned)
    val fallbackDimensionColor = MaterialTheme.colorScheme.tertiary
    val dimensionMeta = remember(dimensionIds, appPrefs, fallbackDimensionLabel, fallbackDimensionColor) {
        dimensionIds.associateWith { dimensionId ->
            val fallbackName = DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.fallbackLabel
            val label = appPrefs.labelForDimensionId(dimensionId)
                ?: appPrefs.labelForDimension(dimensionId, fallbackName)
                ?: fallbackDimensionLabel
            val color = appPrefs.colorForDimensionId(dimensionId)
                ?: appPrefs.colorForDimension(dimensionId, fallbackName)
                ?: fallbackDimensionColor
            label to color
        }
    }
    val overallLabel = stringResource(id = R.string.loc_lens_group_overall)

    val scoreSeries = remember(orderedMetrics, dimensionMeta) {
        buildScoreSeries(orderedMetrics, dimensionIds, dimensionMeta, overallLabel)
    }
    val progressByDay = remember(ascendingMetrics, dimensionIds) {
        buildPerDimensionProgressByDay(ascendingMetrics, dimensionIds)
    }
    val progressSeries = remember(orderedMetrics, dimensionIds, dimensionMeta, progressByDay) {
        buildProgressSeries(orderedMetrics, dimensionIds, dimensionMeta, progressByDay, overallLabel)
    }
    val streakByDay = remember(ascendingMetrics, dimensionIds, progressByDay) {
        buildPerDimensionStreakByDay(ascendingMetrics, dimensionIds, progressByDay)
    }
    val streakSeries = remember(orderedMetrics, dimensionIds, dimensionMeta, streakByDay) {
        buildStreakSeries(orderedMetrics, dimensionIds, dimensionMeta, streakByDay, overallLabel)
    }
    val rankSeries = remember(scoreSeries) {
        scoreSeries.map { series ->
            series.copy(values = denseRanksDescending(series.values).map { it.toDouble() })
        }
    }

    val scoreFormatter = remember { scorePercentFormatter() }
    val progressFormatter = remember { signedScoreFormatter() }
    val rankValueFormatter = remember { rankFormatter() }
    val streakFormatter = remember { integerFormatter() }
    val chartConfigs = remember(
        scoreSeries,
        progressSeries,
        rankSeries,
        streakSeries,
        scoreFormatter,
        progressFormatter,
        rankValueFormatter,
        streakFormatter,
        showDailyScoreTrend,
        showProgressTrend,
        showHistoricalRanking,
        showMomentumStreak,
    ) {
        buildList {
            if (showDailyScoreTrend) {
                add(LensChartConfig(id = "score", yLabelFormatter = scoreFormatter, series = scoreSeries))
            }
            if (showProgressTrend) {
                add(LensChartConfig(id = "progress", yLabelFormatter = progressFormatter, series = progressSeries))
            }
            if (showHistoricalRanking) {
                add(LensChartConfig(id = "rank", yLabelFormatter = rankValueFormatter, series = rankSeries))
            }
            if (showMomentumStreak) {
                add(LensChartConfig(id = "streak", yLabelFormatter = streakFormatter, series = streakSeries))
            }
        }
    }
    val chartTitles = mapOf(
        "score" to stringResource(id = R.string.settings_insights_time_daily_score_trend_title),
        "progress" to stringResource(id = R.string.settings_insights_time_progress_trend_title),
        "rank" to stringResource(id = R.string.settings_insights_time_historical_ranking_title),
        "streak" to stringResource(id = R.string.settings_insights_time_momentum_streak_title),
    )
    if (useLazyContainer) {
        val listState = rememberLazyListState()
        LaunchedEffect(listState, chartConfigs.size, history.totalDays) {
            snapshotFlow { listState.firstVisibleItemIndex }.drop(1).distinctUntilChanged().collect { index ->
                if (index >= (chartConfigs.lastIndex - 1).coerceAtLeast(0)) onRequestMoreHistory()
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items = chartConfigs, key = { it.id }) { config ->
                val title = chartTitles[config.id].orEmpty()
                MultiSeriesLineChartCard(
                    title = title,
                    xLabels = xLabels,
                    yLabelFormatter = config.yLabelFormatter,
                    series = config.series,
                    onHorizontalExplore = onRequestMoreHistory,
                )
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            chartConfigs.forEach { config ->
                val title = chartTitles[config.id].orEmpty()
                MultiSeriesLineChartCard(
                    title = title,
                    xLabels = xLabels,
                    yLabelFormatter = config.yLabelFormatter,
                    series = config.series,
                    onHorizontalExplore = onRequestMoreHistory,
                )
            }
        }
    }
}

private data class LensChartConfig(
    val id: String,
    val yLabelFormatter: CartesianValueFormatter,
    val series: List<MetricSeries>,
)

@Composable
private fun TimeKpiCard(
    modifier: Modifier,
    title: String,
    subtitle: String,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.36f)),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MultiSeriesLineChartCard(
    title: String,
    xLabels: List<String>,
    yLabelFormatter: CartesianValueFormatter,
    series: List<MetricSeries>,
    onHorizontalExplore: () -> Unit = {},
) {
    if (series.isEmpty() || xLabels.isEmpty()) return
    val logger = remember { UnifiedLogger.getInstance() }
    val normalizedSeries by remember(series, xLabels) {
        derivedStateOf {
            series.map { metricSeries ->
                metricSeries.copy(
                    values = xLabels.indices.map { index -> metricSeries.values.getOrElse(index) { 0.0 } },
                )
            }
        }
    }
    val modelProducer = remember { CartesianChartModelProducer() }
    val xValues = remember(xLabels) { xLabels.indices.toList() }
    val scrollEnabled = xLabels.size > LENS_CHART_VISIBLE_DAYS
    val styleSignature = remember(normalizedSeries) { normalizedSeries.map { it.id to it.color } }
    val lineProvider = rememberSeriesLineProvider(styleSignature)
    val seriesFingerprint = remember(normalizedSeries) {
        normalizedSeries.fold(17) { acc, metricSeries ->
            31 * acc + metricSeries.id.hashCode() + metricSeries.values.hashCode()
        }
    }

    LaunchedEffect(title, xLabels.size, normalizedSeries.size, scrollEnabled) {
        logger.d(
            "LensesTimeInsightsScreen.MultiSeriesLineChartCard",
            "Preparing time chart",
            mapOf(
                "title" to title,
                "days" to xLabels.size,
                "seriesCount" to normalizedSeries.size,
                "scrollEnabled" to scrollEnabled,
            ),
        )
    }

    LaunchedEffect(seriesFingerprint, xValues) {
        modelProducer.runTransaction {
            lineSeries {
                normalizedSeries.forEach { metricSeries ->
                    series(xValues, metricSeries.values)
                }
            }
        }
    }

    ChartCardContainer(
        title = title,
        showScrollHint = scrollEnabled,
        legend = { SeriesLegend(normalizedSeries) },
    ) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(
                    lineProvider = lineProvider,
                ),
                startAxis = rememberStartAxis(valueFormatter = yLabelFormatter),
                bottomAxis = rememberBottomAxis(
                    valueFormatter = xAxisFormatter(xLabels),
                    itemPlacer = HorizontalAxis.ItemPlacer.default(1, 0, true, true),
                ),
                marker = rememberDefaultCartesianMarker(
                    label = rememberTextComponent(),
                ),
            ),
            modelProducer = modelProducer,
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp),
            scrollState = rememberVicoScrollState(
                scrollEnabled = scrollEnabled,
                initialScroll = Scroll.Absolute.Start,
            ),
            zoomState = rememberVicoZoomState(
                zoomEnabled = scrollEnabled,
            ),
        )
    }
}

@Composable
private fun rememberSeriesLineProvider(seriesStyles: List<Pair<String, Color>>): LineCartesianLayer.LineProvider {
    val lines = seriesStyles.map { (_, color) ->
        rememberLine(
            fill = LineCartesianLayer.LineFill.single(fill(color)),
            areaFill = null,
        )
    }
    return LineCartesianLayer.LineProvider.series(lines)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeriesLegend(series: List<MetricSeries>) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        series.forEach { line ->
            Text(
                text = line.label,
                style = MaterialTheme.typography.labelSmall,
                color = line.color,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ChartCardContainer(
    title: String,
    showScrollHint: Boolean,
    legend: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, fontWeight = FontWeight.SemiBold)
            content()
            legend()
            if (showScrollHint) {
                Text(
                    text = stringResource(id = R.string.loc_lens_scroll_for_more_days),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun buildXAxisLabels(metrics: List<TimeModuleDayMetric>): List<String> = metrics.map { metric ->
    metric.dayKey.toLensAxisDateLabel()
}

private fun String.toLensAxisDateLabel(): String {
    val canonicalDate = if (length >= 10) substring(0, 10) else this
    return runCatching {
        LocalDate.parse(canonicalDate).format(lensDateFormatter)
    }.getOrElse { canonicalDate }
}

private fun buildScoreSeries(
    orderedMetrics: List<TimeModuleDayMetric>,
    dimensionIds: List<String>,
    dimensionMeta: Map<String, Pair<String, Color>>,
    overallLabel: String,
): List<MetricSeries> {
    val overall = MetricSeries(
        id = "overall",
        label = overallLabel,
        color = Color(0xFF1565C0),
        values = orderedMetrics.map { it.dayScore },
    )
    val dimensionSeries = dimensionIds.map { dimensionId ->
        val (label, color) = dimensionMeta[dimensionId] ?: ("[$dimensionId]" to Color(0xFF6D4C41))
        MetricSeries(
            id = dimensionId,
            label = label,
            color = color,
            values = orderedMetrics.map { metric -> metric.perDimensionScores[dimensionId] ?: 0.0 },
        )
    }
    return listOf(overall) + dimensionSeries
}

private fun buildProgressSeries(
    orderedMetrics: List<TimeModuleDayMetric>,
    dimensionIds: List<String>,
    dimensionMeta: Map<String, Pair<String, Color>>,
    progressByDay: Map<String, Map<String, Double>>,
    overallLabel: String,
): List<MetricSeries> {
    val overall = MetricSeries(
        id = "overall",
        label = overallLabel,
        color = Color(0xFF2E7D32),
        values = orderedMetrics.map { it.progressDelta },
    )
    val dimensionSeries = dimensionIds.map { dimensionId ->
        val (label, color) = dimensionMeta[dimensionId] ?: ("[$dimensionId]" to Color(0xFF6D4C41))
        MetricSeries(
            id = dimensionId,
            label = label,
            color = color,
            values = orderedMetrics.map { metric -> progressByDay[metric.dayKey]?.get(dimensionId) ?: 0.0 },
        )
    }
    return listOf(overall) + dimensionSeries
}

private fun buildStreakSeries(
    orderedMetrics: List<TimeModuleDayMetric>,
    dimensionIds: List<String>,
    dimensionMeta: Map<String, Pair<String, Color>>,
    streakByDay: Map<String, Map<String, Int>>,
    overallLabel: String,
): List<MetricSeries> {
    val overall = MetricSeries(
        id = "overall",
        label = overallLabel,
        color = Color(0xFF6A1B9A),
        values = orderedMetrics.map { it.progressStreak.toDouble() },
    )
    val dimensionSeries = dimensionIds.map { dimensionId ->
        val (label, color) = dimensionMeta[dimensionId] ?: ("[$dimensionId]" to Color(0xFF6D4C41))
        MetricSeries(
            id = dimensionId,
            label = label,
            color = color,
            values = orderedMetrics.map { metric -> (streakByDay[metric.dayKey]?.get(dimensionId) ?: 0).toDouble() },
        )
    }
    return listOf(overall) + dimensionSeries
}

private fun buildPerDimensionProgressByDay(
    ascendingMetrics: List<TimeModuleDayMetric>,
    dimensionIds: List<String>,
): Map<String, Map<String, Double>> {
    val previousScoreByDimension = mutableMapOf<String, Double>()
    val result = mutableMapOf<String, MutableMap<String, Double>>()
    ascendingMetrics.forEachIndexed { index, metric ->
        val dayMap = mutableMapOf<String, Double>()
        dimensionIds.forEach { dimensionId ->
            val score = metric.perDimensionScores[dimensionId] ?: 0.0
            val previousScore = previousScoreByDimension[dimensionId] ?: 0.0
            val delta = if (index == 0) 0.0 else roundToLensScorePrecision(score - previousScore)
            dayMap[dimensionId] = delta
            previousScoreByDimension[dimensionId] = score
        }
        result[metric.dayKey] = dayMap
    }
    return result
}

private fun buildPerDimensionStreakByDay(
    ascendingMetrics: List<TimeModuleDayMetric>,
    dimensionIds: List<String>,
    progressByDay: Map<String, Map<String, Double>>,
): Map<String, Map<String, Int>> {
    val previousDeltaByDimension = mutableMapOf<String, Double>()
    val previousStreakByDimension = mutableMapOf<String, Int>()
    val result = mutableMapOf<String, MutableMap<String, Int>>()

    ascendingMetrics.forEachIndexed { index, metric ->
        val dayMap = mutableMapOf<String, Int>()
        dimensionIds.forEach { dimensionId ->
            val delta = progressByDay[metric.dayKey]?.get(dimensionId) ?: 0.0
            val previousDelta = previousDeltaByDimension[dimensionId] ?: 0.0
            val previousStreak = previousStreakByDimension[dimensionId] ?: 0
            val streak = if (index == 0) {
                0
            } else if (delta > previousDelta) {
                previousStreak + 1
            } else {
                0
            }
            dayMap[dimensionId] = streak
            previousDeltaByDimension[dimensionId] = delta
            previousStreakByDimension[dimensionId] = streak
        }
        result[metric.dayKey] = dayMap
    }
    return result
}

private fun denseRanksDescending(values: List<Double>): List<Int> {
    if (values.isEmpty()) return emptyList()
    val uniqueSorted = values.toSet().sortedDescending()
    return values.map { value -> 1 + uniqueSorted.indexOf(value) }
}

private fun denseRankDescending(value: Double, allValues: List<Double>): Int {
    val uniqueSorted = allValues.toSet().sortedDescending()
    return 1 + uniqueSorted.indexOf(value)
}

private fun roundToLensScorePrecision(value: Double): Double = round(value * LENS_SCORE_SCALE) / LENS_SCORE_SCALE

private fun xAxisFormatter(labels: List<String>): CartesianValueFormatter {
    return object : CartesianValueFormatter {
        override fun format(
            value: Double,
            chartValues: ChartValues,
            verticalAxisPosition: Axis.Position.Vertical?,
        ): CharSequence {
            val index = value.toInt()
            return if (index in labels.indices) labels[index] else ""
        }
    }
}

private fun scorePercentFormatter(): CartesianValueFormatter = CartesianValueFormatter { value, _, _ ->
    "${(value.coerceIn(0.0, 1.0) * 100.0).toInt()}%"
}

private fun signedScoreFormatter(): CartesianValueFormatter = CartesianValueFormatter { value, _, _ ->
    val sign = if (value > 0.0) "+" else ""
    "$sign${String.format(Locale.US, "%.5f", value)}"
}

private fun integerFormatter(): CartesianValueFormatter = CartesianValueFormatter { value, _, _ ->
    value.toInt().toString()
}

private fun rankFormatter(): CartesianValueFormatter = CartesianValueFormatter { value, _, _ ->
    "R${value.toInt()}"
}
