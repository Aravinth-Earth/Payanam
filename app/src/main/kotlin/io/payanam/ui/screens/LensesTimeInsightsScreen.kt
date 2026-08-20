//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

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
    /** Id. */
    val id: String,
    /** Label. */
    val label: String,
    /** Color. */
    val color: Color,
    /** Values. */
    val values: List<Double>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LensesTimeInsightsScreen(
    /** Ui state. */
    uiState: LensUiState,
    onBack: () -> Unit,
) {
    /** Logger. */
    val logger = remember { UnifiedLogger.getInstance() }
    /** History. */
    val history = uiState.timeModuleHistorySummary
    /** App prefs. */
    val appPrefs = LocalAppPreferences.current
    /** Score cards enabled. */
    val scoreCardsEnabled = appPrefs.chartTimeScoreCardsEnabled
    /** Score cards visible. */
    val scoreCardsVisible = scoreCardsEnabled && (appPrefs.chartTimeOverallScoreCardEnabled || appPrefs.chartTimeDimensionScoreCardsEnabled)
    /** Line graphs enabled. */
    val lineGraphsEnabled = appPrefs.chartTimeLineGraphsEnabled
    /** Daily score trend enabled. */
    val dailyScoreTrendEnabled = lineGraphsEnabled && appPrefs.chartTimeDailyScoreTrendEnabled
    /** Progress trend enabled. */
    val progressTrendEnabled = lineGraphsEnabled && appPrefs.chartTimeProgressTrendEnabled
    /** Historical ranking enabled. */
    val historicalRankingEnabled = lineGraphsEnabled && appPrefs.chartTimeHistoricalRankingEnabled
    /** Momentum streak enabled. */
    val momentumStreakEnabled = lineGraphsEnabled && appPrefs.chartTimeMomentumStreakEnabled
    /** Visible line graphs. */
    val visibleLineGraphs = dailyScoreTrendEnabled || progressTrendEnabled || historicalRankingEnabled || momentumStreakEnabled
    /** Has visible charts. */
    val hasVisibleCharts = scoreCardsVisible || visibleLineGraphs
    /** Launched effect. */
    LaunchedEffect(history?.currentDayKey, history?.totalDays) {
        logger.d(
            "LensesTimeInsightsScreen",
            "Opened time module insights full screen",
            /** Map of. */
            mapOf(
                "currentDayKey" to (history?.currentDayKey ?: "none"),
                "totalDays" to (history?.totalDays ?: 0),
            ),
        )
    }

    /** Scaffold. */
    Scaffold(
        topBar = {
            /** Top app bar. */
            TopAppBar(
                title = { Text(stringResource(id = R.string.loc_time_insights)) },
                navigationIcon = {
                    /** Icon button. */
                    IconButton(onClick = onBack) {
                        /** Icon. */
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(id = R.string.loc_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        /** If. */
        if (!hasVisibleCharts) {
            /** Box. */
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                /** Text. */
                Text(stringResource(id = R.string.loc_lens_time_charts_disabled_hint))
            }
            return@Scaffold
        }

        /** If. */
        if (history == null || history.metrics.isEmpty()) {
            /** Box. */
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                /** Text. */
                Text(stringResource(id = R.string.loc_lens_time_no_history))
            }
            return@Scaffold
        }

        /** Column. */
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            /** If. */
            if (scoreCardsVisible) {
                /** Time history quick stats row. */
                TimeHistoryQuickStatsRow(history = history)
            }
            /** If. */
            if (visibleLineGraphs) {
                /** Text. */
                Text(
                    text = stringResource(id = R.string.loc_lens_time_all_history_days_line, history.totalDays),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                /** Lenses time inline charts. */
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
    /** Ordered metrics. */
    val orderedMetrics = remember(history.metrics) { history.metrics.sortedByDescending { it.dayKey } }
    /** Current. */
    val current = orderedMetrics.firstOrNull()
    /** Score values. */
    val scoreValues = orderedMetrics.map { it.dayScore }
    /** Progress values. */
    val progressValues = orderedMetrics.map { it.progressDelta }
    /** Streak values. */
    val streakValues = orderedMetrics.map { it.progressStreak.toDouble() }
    /** Score rank. */
    val scoreRank = current?.let { denseRankDescending(it.dayScore, scoreValues) } ?: 0
    /** Progress rank. */
    val progressRank = current?.let { denseRankDescending(it.progressDelta, progressValues) } ?: 0
    /** Streak rank. */
    val streakRank = current?.let { denseRankDescending(it.progressStreak.toDouble(), streakValues) } ?: 0
    /** Score unique total. */
    val scoreUniqueTotal = scoreValues.toSet().size
    /** Progress unique total. */
    val progressUniqueTotal = progressValues.toSet().size
    /** Streak unique total. */
    val streakUniqueTotal = streakValues.toSet().size

    /** Row. */
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        /** Time kpi card. */
        TimeKpiCard(
            modifier = Modifier.weight(1f),
            title = stringResource(id = R.string.loc_lens_time_day_score_line, formatLensScore(current?.dayScore ?: 0.0)),
            subtitle = stringResource(id = R.string.loc_lens_rank_short_line, scoreRank, scoreUniqueTotal),
        )
        /** Time kpi card. */
        TimeKpiCard(
            modifier = Modifier.weight(1f),
            title = stringResource(id = R.string.loc_lens_time_progress_line, formatSignedLensScore(current?.progressDelta ?: 0.0)),
            subtitle = stringResource(id = R.string.loc_lens_rank_short_line, progressRank, progressUniqueTotal),
        )
        /** Time kpi card. */
        TimeKpiCard(
            modifier = Modifier.weight(1f),
            title = stringResource(id = R.string.loc_lens_time_streak_line, current?.progressStreak ?: 0),
            subtitle = stringResource(id = R.string.loc_lens_rank_short_line, streakRank, streakUniqueTotal),
        )
    }
}

@Composable
internal fun LensesTimeInlineCharts(history: TimeModuleHistorySummary) {
    /** Lenses time inline charts. */
    LensesTimeInlineCharts(
        history = history,
        useLazyContainer = false,
        onRequestMoreHistory = {},
    )
}

@Composable
internal fun LensesTimeInlineCharts(
    /** History. */
    history: TimeModuleHistorySummary,
    /** Use lazy container. */
    useLazyContainer: Boolean,
    onRequestMoreHistory: () -> Unit = {},
    showDailyScoreTrend: Boolean = true,
    showProgressTrend: Boolean = true,
    showHistoricalRanking: Boolean = true,
    showMomentumStreak: Boolean = true,
) {
    /** App prefs. */
    val appPrefs = LocalAppPreferences.current
    /** Ordered metrics. */
    val orderedMetrics = remember(history.metrics) { history.metrics.sortedByDescending { it.dayKey } }
    /** Ascending metrics. */
    val ascendingMetrics = remember(orderedMetrics) { orderedMetrics.asReversed() }
    /** Dimension ids. */
    val dimensionIds = remember(orderedMetrics) {
        /** Ordered metrics. */
        orderedMetrics
            .flatMap { it.perDimensionScores.keys }
            .toSet()
            .toList()
    }
    /** X labels. */
    val xLabels = remember(orderedMetrics) { buildXAxisLabels(orderedMetrics) }
    /** Fallback dimension label. */
    val fallbackDimensionLabel = stringResource(id = R.string.loc_dimension_fallback_unassigned)
    /** Fallback dimension color. */
    val fallbackDimensionColor = MaterialTheme.colorScheme.tertiary
    /** Dimension meta. */
    val dimensionMeta = remember(dimensionIds, appPrefs, fallbackDimensionLabel, fallbackDimensionColor) {
        dimensionIds.associateWith { dimensionId ->
            /** Fallback name. */
            val fallbackName = DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.fallbackLabel
            /** Label. */
            val label = appPrefs.labelForDimensionId(dimensionId)
                ?: appPrefs.labelForDimension(dimensionId, fallbackName)
                ?: fallbackDimensionLabel
            /** Color. */
            val color = appPrefs.colorForDimensionId(dimensionId)
                ?: appPrefs.colorForDimension(dimensionId, fallbackName)
                ?: fallbackDimensionColor
            label to color
        }
    }
    /** Overall label. */
    val overallLabel = stringResource(id = R.string.loc_lens_group_overall)

    /** Score series. */
    val scoreSeries = remember(orderedMetrics, dimensionMeta) {
        /** Build score series. */
        buildScoreSeries(orderedMetrics, dimensionIds, dimensionMeta, overallLabel)
    }
    /** Progress by day. */
    val progressByDay = remember(ascendingMetrics, dimensionIds) {
        /** Build per dimension progress by day. */
        buildPerDimensionProgressByDay(ascendingMetrics, dimensionIds)
    }
    /** Progress series. */
    val progressSeries = remember(orderedMetrics, dimensionIds, dimensionMeta, progressByDay) {
        /** Build progress series. */
        buildProgressSeries(orderedMetrics, dimensionIds, dimensionMeta, progressByDay, overallLabel)
    }
    /** Streak by day. */
    val streakByDay = remember(ascendingMetrics, dimensionIds, progressByDay) {
        /** Build per dimension streak by day. */
        buildPerDimensionStreakByDay(ascendingMetrics, dimensionIds, progressByDay)
    }
    /** Streak series. */
    val streakSeries = remember(orderedMetrics, dimensionIds, dimensionMeta, streakByDay) {
        /** Build streak series. */
        buildStreakSeries(orderedMetrics, dimensionIds, dimensionMeta, streakByDay, overallLabel)
    }
    /** Rank series. */
    val rankSeries = remember(scoreSeries) {
        scoreSeries.map { series ->
            series.copy(values = denseRanksDescending(series.values).map { it.toDouble() })
        }
    }

    /** Score formatter. */
    val scoreFormatter = remember { scorePercentFormatter() }
    /** Progress formatter. */
    val progressFormatter = remember { signedScoreFormatter() }
    /** Rank value formatter. */
    val rankValueFormatter = remember { rankFormatter() }
    /** Streak formatter. */
    val streakFormatter = remember { integerFormatter() }
    /** Chart configs. */
    val chartConfigs = remember(
        /** Score series. */
        scoreSeries,
        /** Progress series. */
        progressSeries,
        /** Rank series. */
        rankSeries,
        /** Streak series. */
        streakSeries,
        /** Score formatter. */
        scoreFormatter,
        /** Progress formatter. */
        progressFormatter,
        /** Rank value formatter. */
        rankValueFormatter,
        /** Streak formatter. */
        streakFormatter,
        /** Show daily score trend. */
        showDailyScoreTrend,
        /** Show progress trend. */
        showProgressTrend,
        /** Show historical ranking. */
        showHistoricalRanking,
        /** Show momentum streak. */
        showMomentumStreak,
    ) {
        buildList {
            /** If. */
            if (showDailyScoreTrend) {
                /** Add. */
                add(LensChartConfig(id = "score", yLabelFormatter = scoreFormatter, series = scoreSeries))
            }
            /** If. */
            if (showProgressTrend) {
                /** Add. */
                add(LensChartConfig(id = "progress", yLabelFormatter = progressFormatter, series = progressSeries))
            }
            /** If. */
            if (showHistoricalRanking) {
                /** Add. */
                add(LensChartConfig(id = "rank", yLabelFormatter = rankValueFormatter, series = rankSeries))
            }
            /** If. */
            if (showMomentumStreak) {
                /** Add. */
                add(LensChartConfig(id = "streak", yLabelFormatter = streakFormatter, series = streakSeries))
            }
        }
    }
    /** Chart titles. */
    val chartTitles = mapOf(
        "score" to stringResource(id = R.string.settings_insights_time_daily_score_trend_title),
        "progress" to stringResource(id = R.string.settings_insights_time_progress_trend_title),
        "rank" to stringResource(id = R.string.settings_insights_time_historical_ranking_title),
        "streak" to stringResource(id = R.string.settings_insights_time_momentum_streak_title),
    )
    /** If. */
    if (useLazyContainer) {
        /** List state. */
        val listState = rememberLazyListState()
        /** Launched effect. */
        LaunchedEffect(listState, chartConfigs.size, history.totalDays) {
            snapshotFlow { listState.firstVisibleItemIndex }.drop(1).distinctUntilChanged().collect { index ->
                /** If. */
                if (index >= (chartConfigs.lastIndex - 1).coerceAtLeast(0)) onRequestMoreHistory()
            }
        }
        /** Lazy column. */
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            /** Items. */
            items(items = chartConfigs, key = { it.id }) { config ->
                /** Title. */
                val title = chartTitles[config.id].orEmpty()
                /** Multi series line chart card. */
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
        /** Column. */
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            chartConfigs.forEach { config ->
                /** Title. */
                val title = chartTitles[config.id].orEmpty()
                /** Multi series line chart card. */
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
    /** Id. */
    val id: String,
    /** Y label formatter. */
    val yLabelFormatter: CartesianValueFormatter,
    /** Series. */
    val series: List<MetricSeries>,
)

@Composable
private fun TimeKpiCard(
    /** Modifier. */
    modifier: Modifier,
    /** Title. */
    title: String,
    /** Subtitle. */
    subtitle: String,
) {
    /** Card. */
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.36f)),
    ) {
        /** Column. */
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            /** Text. */
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            /** Text. */
            Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MultiSeriesLineChartCard(
    /** Title. */
    title: String,
    xLabels: List<String>,
    /** Y label formatter. */
    yLabelFormatter: CartesianValueFormatter,
    series: List<MetricSeries>,
    onHorizontalExplore: () -> Unit = {},
) {
    /** If. */
    if (series.isEmpty() || xLabels.isEmpty()) return
    /** Logger. */
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
    /** Model producer. */
    val modelProducer = remember { CartesianChartModelProducer() }
    /** X values. */
    val xValues = remember(xLabels) { xLabels.indices.toList() }
    /** Scroll enabled. */
    val scrollEnabled = xLabels.size > LENS_CHART_VISIBLE_DAYS
    /** Style signature. */
    val styleSignature = remember(normalizedSeries) { normalizedSeries.map { it.id to it.color } }
    /** Line provider. */
    val lineProvider = rememberSeriesLineProvider(styleSignature)
    /** Series fingerprint. */
    val seriesFingerprint = remember(normalizedSeries) {
        normalizedSeries.fold(17) { acc, metricSeries ->
            31 * acc + metricSeries.id.hashCode() + metricSeries.values.hashCode()
        }
    }

    /** Launched effect. */
    LaunchedEffect(title, xLabels.size, normalizedSeries.size, scrollEnabled) {
        logger.d(
            "LensesTimeInsightsScreen.MultiSeriesLineChartCard",
            "Preparing time chart",
            /** Map of. */
            mapOf(
                "title" to title,
                "days" to xLabels.size,
                "seriesCount" to normalizedSeries.size,
                "scrollEnabled" to scrollEnabled,
            ),
        )
    }

    /** Launched effect. */
    LaunchedEffect(seriesFingerprint, xValues) {
        modelProducer.runTransaction {
            lineSeries {
                normalizedSeries.forEach { metricSeries ->
                    /** Series. */
                    series(xValues, metricSeries.values)
                }
            }
        }
    }

    /** Chart card container. */
    ChartCardContainer(
        title = title,
        showScrollHint = scrollEnabled,
        legend = { SeriesLegend(normalizedSeries) },
    ) {
        /** Cartesian chart host. */
        CartesianChartHost(
            chart = rememberCartesianChart(
                /** Remember line cartesian layer. */
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
    /** Lines. */
    val lines = seriesStyles.map { (_, color) ->
        /** Remember line. */
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
    /** Flow row. */
    FlowRow(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        series.forEach { line ->
            /** Text. */
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
    /** Title. */
    title: String,
    /** Show scroll hint. */
    showScrollHint: Boolean,
    legend: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    /** Card. */
    Card(modifier = Modifier.fillMaxWidth()) {
        /** Column. */
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            /** Text. */
            Text(text = title, fontWeight = FontWeight.SemiBold)
            /** Content. */
            content()
            /** Legend. */
            legend()
            /** If. */
            if (showScrollHint) {
                /** Text. */
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
    /** Canonical date. */
    val canonicalDate = if (length >= 10) substring(0, 10) else this
    return runCatching {
        LocalDate.parse(canonicalDate).format(lensDateFormatter)
    }.getOrElse { canonicalDate }
}

private fun buildScoreSeries(
    orderedMetrics: List<TimeModuleDayMetric>,
    dimensionIds: List<String>,
    dimensionMeta: Map<String, Pair<String, Color>>,
    /** Overall label. */
    overallLabel: String,
): List<MetricSeries> {
    /** Overall. */
    val overall = MetricSeries(
        id = "overall",
        label = overallLabel,
        color = Color(0xFF1565C0),
        values = orderedMetrics.map { it.dayScore },
    )
    /** Dimension series. */
    val dimensionSeries = dimensionIds.map { dimensionId ->
        /** Val. */
        val (label, color) = dimensionMeta[dimensionId] ?: ("[$dimensionId]" to Color(0xFF6D4C41))
        /** Metric series. */
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
    /** Overall label. */
    overallLabel: String,
): List<MetricSeries> {
    /** Overall. */
    val overall = MetricSeries(
        id = "overall",
        label = overallLabel,
        color = Color(0xFF2E7D32),
        values = orderedMetrics.map { it.progressDelta },
    )
    /** Dimension series. */
    val dimensionSeries = dimensionIds.map { dimensionId ->
        /** Val. */
        val (label, color) = dimensionMeta[dimensionId] ?: ("[$dimensionId]" to Color(0xFF6D4C41))
        /** Metric series. */
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
    /** Overall label. */
    overallLabel: String,
): List<MetricSeries> {
    /** Overall. */
    val overall = MetricSeries(
        id = "overall",
        label = overallLabel,
        color = Color(0xFF6A1B9A),
        values = orderedMetrics.map { it.progressStreak.toDouble() },
    )
    /** Dimension series. */
    val dimensionSeries = dimensionIds.map { dimensionId ->
        /** Val. */
        val (label, color) = dimensionMeta[dimensionId] ?: ("[$dimensionId]" to Color(0xFF6D4C41))
        /** Metric series. */
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
    /** Previous score by dimension. */
    val previousScoreByDimension = mutableMapOf<String, Double>()
    /** Result. */
    val result = mutableMapOf<String, MutableMap<String, Double>>()
    ascendingMetrics.forEachIndexed { index, metric ->
        /** Day map. */
        val dayMap = mutableMapOf<String, Double>()
        dimensionIds.forEach { dimensionId ->
            /** Score. */
            val score = metric.perDimensionScores[dimensionId] ?: 0.0
            /** Previous score. */
            val previousScore = previousScoreByDimension[dimensionId] ?: 0.0
            /** Delta. */
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
    /** Previous delta by dimension. */
    val previousDeltaByDimension = mutableMapOf<String, Double>()
    /** Previous streak by dimension. */
    val previousStreakByDimension = mutableMapOf<String, Int>()
    /** Result. */
    val result = mutableMapOf<String, MutableMap<String, Int>>()

    ascendingMetrics.forEachIndexed { index, metric ->
        /** Day map. */
        val dayMap = mutableMapOf<String, Int>()
        dimensionIds.forEach { dimensionId ->
            /** Delta. */
            val delta = progressByDay[metric.dayKey]?.get(dimensionId) ?: 0.0
            /** Previous delta. */
            val previousDelta = previousDeltaByDimension[dimensionId] ?: 0.0
            /** Previous streak. */
            val previousStreak = previousStreakByDimension[dimensionId] ?: 0
            /** Streak. */
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
    /** If. */
    if (values.isEmpty()) return emptyList()
    /** Unique sorted. */
    val uniqueSorted = values.toSet().sortedDescending()
    return values.map { value -> 1 + uniqueSorted.indexOf(value) }
}

private fun denseRankDescending(value: Double, allValues: List<Double>): Int {
    /** Unique sorted. */
    val uniqueSorted = allValues.toSet().sortedDescending()
    return 1 + uniqueSorted.indexOf(value)
}

private fun roundToLensScorePrecision(value: Double): Double = round(value * LENS_SCORE_SCALE) / LENS_SCORE_SCALE

private fun xAxisFormatter(labels: List<String>): CartesianValueFormatter {
    return object : CartesianValueFormatter {
        override fun format(
            /** Value. */
            value: Double,
            /** Chart values. */
            chartValues: ChartValues,
            verticalAxisPosition: Axis.Position.Vertical?,
        ): CharSequence {
            /** Index. */
            val index = value.toInt()
            return if (index in labels.indices) labels[index] else ""
        }
    }
}

private fun scorePercentFormatter(): CartesianValueFormatter = CartesianValueFormatter { value, _, _ ->
    "${(value.coerceIn(0.0, 1.0) * 100.0).toInt()}%"
}

private fun signedScoreFormatter(): CartesianValueFormatter = CartesianValueFormatter { value, _, _ ->
    /** Sign. */
    val sign = if (value > 0.0) "+" else ""
    "$sign${String.format(Locale.US, "%.5f", value)}"
}

private fun integerFormatter(): CartesianValueFormatter = CartesianValueFormatter { value, _, _ ->
    value.toInt().toString()
}

private fun rankFormatter(): CartesianValueFormatter = CartesianValueFormatter { value, _, _ ->
    "R${value.toInt()}"
}
