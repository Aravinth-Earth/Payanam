//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.core.ui.lenses

import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.formatArgs
import io.payanam.shared.logging.AppLogger
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.LocalAppPreferences
import io.payanam.core.ui.preferences.colorForDimension
import io.payanam.core.ui.preferences.colorForDimensionId
import io.payanam.core.ui.preferences.labelForDimension
import io.payanam.core.ui.preferences.labelForDimensionId
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.repository.DailyFocusStat
import io.payanam.domain.repository.DailyFocusedHoursStat
import io.payanam.domain.repository.DailyTrackedTimeStat
import io.payanam.core.ui.components.DimensionIdentityRow
import io.payanam.presentation.lenses.LensRangeSummary
import io.payanam.presentation.lenses.LensUiState
import io.payanam.presentation.lenses.TimeModuleDayMetric
import io.payanam.core.ui.lenses.LENS_DAY_MINUTES
import io.payanam.core.ui.lenses.TimeDimensionScoreRow
import io.payanam.core.ui.lenses.calculateBoundedTimeModuleScore
import io.payanam.core.ui.lenses.calculateWeightedTimeModuleScore
import io.payanam.core.ui.lenses.formatLensScore
import io.payanam.core.ui.lenses.formatMinutes
import io.payanam.core.ui.lenses.formatSignedLensScore
import io.payanam.core.ui.lenses.formatSignedMinutes
import kotlin.math.round

private const val LENS_CARD_SCORE_SCALE = 100000.0
private val LENS_CHART_LOW_COLOR = Color(0xFFD32F2F)
private val LENS_CHART_MID_COLOR = Color(0xFFFBC02D)
private val LENS_CHART_HIGH_COLOR = Color(0xFF388E3C)
private val logger get() = AppLogger.current

@Composable
internal fun TimeModuleSectionContent(
    uiState: LensUiState,
    summary: LensRangeSummary?,
    dimensionIds: List<String>,
    includeSupplementalActual: Boolean,
    onRequestMoreHistory: () -> Unit = {},
) {
    val strings = LocalUiStrings.current
    val appPrefs = LocalAppPreferences.current
    val scoreCardsEnabled = appPrefs.chartTimeScoreCardsEnabled
    val overallScoreCardEnabled = scoreCardsEnabled && appPrefs.chartTimeOverallScoreCardEnabled
    val dimensionScoreCardsEnabled = scoreCardsEnabled && appPrefs.chartTimeDimensionScoreCardsEnabled
    val lineGraphsEnabled = appPrefs.chartTimeLineGraphsEnabled
    val dailyScoreTrendEnabled = lineGraphsEnabled && appPrefs.chartTimeDailyScoreTrendEnabled
    val progressTrendEnabled = lineGraphsEnabled && appPrefs.chartTimeProgressTrendEnabled
    val historicalRankingEnabled = lineGraphsEnabled && appPrefs.chartTimeHistoricalRankingEnabled
    val momentumStreakEnabled = lineGraphsEnabled && appPrefs.chartTimeMomentumStreakEnabled
    val plannedMap = summary?.plannedByDimension ?: emptyMap()
    val baseActualMap = summary?.actualByDimension ?: emptyMap()
    val supplementalActualMap = summary?.supplementalActualByDimension ?: emptyMap()
    val actualMap = if (includeSupplementalActual) {
        baseActualMap
    } else {
        baseActualMap.mapValues { (dimensionId, minutes) ->
            (minutes - (supplementalActualMap[dimensionId] ?: 0)).coerceAtLeast(0)
        }
    }
    val timeDimensionIds = dimensionIds.filter { id -> plannedMap.containsKey(id) || actualMap.containsKey(id) }
    val scoreRows = timeDimensionIds.map { id ->
        val planned = (plannedMap[id] ?: 0).coerceIn(0, LENS_DAY_MINUTES)
        val actual = (actualMap[id] ?: 0).coerceIn(0, LENS_DAY_MINUTES)
        TimeDimensionScoreRow(
            dimensionId = id,
            plannedMinutes = planned,
            actualMinutes = actual,
            score = calculateBoundedTimeModuleScore(plannedMinutes = planned, actualMinutes = actual),
            deviationMinutes = actual - planned,
        )
    }
    val history = uiState.timeModuleHistorySummary
    val orderedMetrics = history?.metrics?.sortedByDescending { it.dayKey } ?: emptyList()
    val ascendingMetrics = orderedMetrics.asReversed()
    val currentMetric = orderedMetrics.firstOrNull()
    val scoreValues = orderedMetrics.map { it.dayScore }
    val progressValues = orderedMetrics.map { it.progressDelta }
    val streakValues = orderedMetrics.map { it.progressStreak.toDouble() }
    val progressByDay = buildPerDimensionProgressByDay(ascendingMetrics, timeDimensionIds)
    val streakByDay = buildPerDimensionStreakByDay(ascendingMetrics, timeDimensionIds, progressByDay)
    LaunchedEffect(timeDimensionIds.size, orderedMetrics.size) {
        logger.d(
            "LensesScreen.TimeModuleSectionContent",
            "Built time module card section",
            mapOf("dimensionCards" to timeDimensionIds.size, "historyDays" to orderedMetrics.size),
        )
    }
    if (overallScoreCardEnabled && history != null) {
        LensTimeMetricsCard(
            appPrefs = appPrefs,
            title = strings.locLensTimeOverallLabel,
            accentColor = MaterialTheme.colorScheme.primary,
            plannedMinutes = summary?.totalPlannedMinutes ?: 0,
            trackedMinutes = ((summary?.totalActualMinutes ?: 0) - if (includeSupplementalActual) 0 else (summary?.supplementalActualMinutes ?: 0)).coerceAtLeast(0),
            tertiaryTimeLabel = strings.locUntracked,
            tertiaryTimeValue = formatMinutes(summary?.totalUntrackedMinutes ?: 0),
            score = currentMetric?.dayScore ?: calculateWeightedTimeModuleScore(scoreRows),
            scoreRank = rankLabel(
                rank = currentMetric?.let { denseRankDescending(it.dayScore, scoreValues) } ?: 0,
                total = scoreValues.toSet().size,
            ),
            progress = currentMetric?.progressDelta ?: 0.0,
            progressRank = rankLabel(
                rank = currentMetric?.let { denseRankDescending(it.progressDelta, progressValues) } ?: 0,
                total = progressValues.toSet().size,
            ),
            streak = currentMetric?.progressStreak ?: 0,
            streakRank = rankLabel(
                rank = currentMetric?.let { denseRankDescending(it.progressStreak.toDouble(), streakValues) } ?: 0,
                total = streakValues.toSet().size,
            ),
        )
    }
    if (dimensionScoreCardsEnabled && history != null) {
        if (timeDimensionIds.isEmpty()) {
            Text(strings.locLensNoDimensionDistribution)
        } else {
            Text(strings.locLensGroupByDimension, fontWeight = FontWeight.Medium)
            scoreRows.forEach { row ->
                val id = row.dimensionId
                val label = appPrefs.labelForDimensionId(id)
                    ?: appPrefs.labelForDimension(id, DimensionTaxonomyCatalog.fromCanonicalId(id)?.fallbackLabel)
                    ?: strings.locDimensionFallbackUnassigned
                val color = appPrefs.colorForDimensionId(id)
                    ?: appPrefs.colorForDimension(id, DimensionTaxonomyCatalog.fromCanonicalId(id)?.fallbackLabel)
                    ?: MaterialTheme.colorScheme.primary
                val dimensionScoreValues = orderedMetrics.map { metric -> metric.perDimensionScores[id] ?: 0.0 }
                val dimensionProgressValues = orderedMetrics.map { metric ->
                    progressByDay[metric.dayKey]?.get(id) ?: 0.0
                }
                val dimensionStreakValues = orderedMetrics.map { metric ->
                    (streakByDay[metric.dayKey]?.get(id) ?: 0).toDouble()
                }
                val currentDimensionScore = currentMetric?.perDimensionScores?.get(id) ?: row.score
                val currentDimensionProgress = currentMetric?.dayKey?.let { dayKey ->
                    progressByDay[dayKey]?.get(id)
                } ?: 0.0
                val currentDimensionStreak = currentMetric?.dayKey?.let { dayKey ->
                    streakByDay[dayKey]?.get(id)
                } ?: 0
                LensTimeMetricsCard(
                    appPrefs = appPrefs,
                    dimensionId = id,
                    title = label,
                    accentColor = color,
                    plannedMinutes = row.plannedMinutes,
                    trackedMinutes = row.actualMinutes,
                    tertiaryTimeLabel = strings.locDeviation,
                    tertiaryTimeValue = formatSignedMinutes(row.deviationMinutes.toLong()),
                    score = currentDimensionScore,
                    scoreRank = rankLabel(
                        rank = denseRankDescending(currentDimensionScore, dimensionScoreValues),
                        total = dimensionScoreValues.toSet().size,
                    ),
                    progress = currentDimensionProgress,
                    progressRank = rankLabel(
                        rank = denseRankDescending(currentDimensionProgress, dimensionProgressValues),
                        total = dimensionProgressValues.toSet().size,
                    ),
                    streak = currentDimensionStreak,
                    streakRank = rankLabel(
                        rank = denseRankDescending(currentDimensionStreak.toDouble(), dimensionStreakValues),
                        total = dimensionStreakValues.toSet().size,
                    ),
                )
            }
        }
    }
    if (lineGraphsEnabled && history != null) {
        LensesTimeInlineCharts(
            history = history,
            useLazyContainer = false,
            onRequestMoreHistory = onRequestMoreHistory,
            showDailyScoreTrend = dailyScoreTrendEnabled,
            showProgressTrend = progressTrendEnabled,
            showHistoricalRanking = historicalRankingEnabled,
            showMomentumStreak = momentumStreakEnabled,
        )
    }
}

@Composable
private fun LensTimeMetricsCard(
    appPrefs: AppPreferencesState,
    dimensionId: String? = null,
    title: String,
    accentColor: Color,
    plannedMinutes: Int,
    trackedMinutes: Int,
    tertiaryTimeLabel: String,
    tertiaryTimeValue: String,
    score: Double,
    scoreRank: String,
    progress: Double,
    progressRank: String,
    streak: Int,
    streakRank: String,
) {
    val strings = LocalUiStrings.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DimensionIdentityRow(
                    prefs = appPrefs,
                    dimensionId = dimensionId,
                    fallbackLabel = title,
                    fallbackColor = accentColor,
                    showLabel = false,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = title, fontWeight = FontWeight.SemiBold, color = accentColor)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeSplitChip(
                    modifier = Modifier.weight(1f),
                    label = strings.locPlanned,
                    value = formatMinutes(plannedMinutes),
                )
                TimeSplitChip(
                    modifier = Modifier.weight(1f),
                    label = strings.locTrackedTime,
                    value = formatMinutes(trackedMinutes),
                )
                TimeSplitChip(
                    modifier = Modifier.weight(1f),
                    label = tertiaryTimeLabel,
                    value = tertiaryTimeValue,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCell(
                    modifier = Modifier.weight(1f),
                    label = strings.locScore,
                    value = formatLensScore(score),
                )
                MetricCell(
                    modifier = Modifier.weight(1f),
                    label = strings.locLensTimeProgressLabel,
                    value = formatSignedLensScore(progress),
                )
                MetricCell(
                    modifier = Modifier.weight(1f),
                    label = strings.locLensTimeStreakLabel,
                    value = streak.toString(),
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCell(
                    modifier = Modifier.weight(1f),
                    label = strings.locLensTimeScoreRankLabel,
                    value = scoreRank,
                )
                MetricCell(
                    modifier = Modifier.weight(1f),
                    label = strings.locLensTimeProgressRankLabel,
                    value = progressRank,
                )
                MetricCell(
                    modifier = Modifier.weight(1f),
                    label = strings.locLensTimeStreakRankLabel,
                    value = streakRank,
                )
            }
        }
    }
}

@Composable
private fun TimeSplitChip(
    modifier: Modifier,
    label: String,
    value: String,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun MetricCell(
    modifier: Modifier,
    label: String,
    value: String,
) {
    Column(modifier = modifier) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun rankLabel(rank: Int, total: Int): String {
    val strings = LocalUiStrings.current
    return strings.locLensRankShortLine.formatArgs(rank.coerceAtLeast(0), total.coerceAtLeast(0))
}

private fun denseRankDescending(current: Double, values: List<Double>): Int {
    if (values.isEmpty()) {
        return 0
    }
    return 1 + values.toSet().count { it > current }
}

private fun roundLensScorePrecision(value: Double): Double = round(value * LENS_CARD_SCORE_SCALE) / LENS_CARD_SCORE_SCALE

private fun buildPerDimensionProgressByDay(
    ascendingMetrics: List<TimeModuleDayMetric>,
    dimensionIds: List<String>,
): Map<String, Map<String, Double>> {
    if (ascendingMetrics.isEmpty() || dimensionIds.isEmpty()) {
        return emptyMap()
    }
    val previousScoreByDimension = mutableMapOf<String, Double>()
    val progressByDay = mutableMapOf<String, Map<String, Double>>()
    ascendingMetrics.forEachIndexed { index, metric ->
        val dayMap = mutableMapOf<String, Double>()
        dimensionIds.forEach { dimensionId ->
            val score = metric.perDimensionScores[dimensionId] ?: 0.0
            val previousScore = previousScoreByDimension[dimensionId] ?: 0.0
            val delta = if (index == 0) 0.0 else roundLensScorePrecision(score - previousScore)
            dayMap[dimensionId] = delta
            previousScoreByDimension[dimensionId] = score
        }
        progressByDay[metric.dayKey] = dayMap
    }
    return progressByDay
}

private fun buildPerDimensionStreakByDay(
    ascendingMetrics: List<TimeModuleDayMetric>,
    dimensionIds: List<String>,
    progressByDay: Map<String, Map<String, Double>>,
): Map<String, Map<String, Int>> {
    if (ascendingMetrics.isEmpty() || dimensionIds.isEmpty()) {
        return emptyMap()
    }
    val previousDeltaByDimension = mutableMapOf<String, Double>()
    val previousStreakByDimension = mutableMapOf<String, Int>()
    val streakByDay = mutableMapOf<String, Map<String, Int>>()
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
        streakByDay[metric.dayKey] = dayMap
    }
    return streakByDay
}

@Composable
internal fun MinimalFocusBarChart(items: List<DailyFocusStat>) {
    val strings = LocalUiStrings.current
    val maxBarHeightDp = 100f
    val colorScheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (items.isEmpty()) {
            Text(
                text = strings.locLensTimeNoHistory,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
            )
            return
        }

        // Overall + rolling 7-day average header
        val validScores = items.mapNotNull { it.avgFocus }
        val overallAvg = if (validScores.isNotEmpty()) validScores.average() else null
        val rolling7Avg = items.takeLast(7).mapNotNull { it.avgFocus }.takeIf { it.isNotEmpty() }?.average()
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = strings.locLensMinimalFocusChartTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (overallAvg != null || rolling7Avg != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (rolling7Avg != null) {
                        val r7Percent = (rolling7Avg * 100).toInt()
                        val r7Color = focusColor(rolling7Avg.toFloat())
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = strings.locLensChartStat7dLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = colorScheme.onSurfaceVariant,
                            )
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(r7Color))
                            Text(
                                text = strings.locLensFocusAvgValue.formatArgs(r7Percent),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = r7Color,
                            )
                        }
                    }
                    if (overallAvg != null) {
                        val avgPercent = (overallAvg * 100).toInt()
                        val avgColor = focusColor(overallAvg.toFloat())
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = strings.locAll,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = colorScheme.onSurfaceVariant,
                            )
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(avgColor))
                            Text(
                                text = strings.locLensFocusAvgValue.formatArgs(avgPercent),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = avgColor,
                            )
                        }
                    }
                }
            }
        }

        // Bar chart — reversed so today is leftmost; scroll right for older dates
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            items.reversed().forEach { stat ->
                val focusFloat = stat.avgFocus?.toFloat()?.coerceIn(0f, 1f) ?: 0f
                val barHeightFraction = focusFloat.coerceAtLeast(0.04f)
                val barColor = when {
                    stat.avgFocus == null -> colorScheme.onSurface.copy(alpha = 0.12f)
                    else -> focusColor(focusFloat)
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    // Percentage label above bar
                    if (stat.avgFocus != null) {
                        Text(
                            text = "${(focusFloat * 100).toInt()}",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(24.dp)
                            .height((maxBarHeightDp * barHeightFraction).dp)
                            .background(barColor, shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = dayLabel(stat.dayKey),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        maxLines = 1,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun focusColor(
    focus: Float,
): Color = lensesTimeGradientColor(focus)

@Composable
internal fun MinimalTrackedTimeBarChart(items: List<DailyTrackedTimeStat>) {
    val strings = LocalUiStrings.current
    val maxBarHeightDp = 100f
    val colorScheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (items.isEmpty()) {
            Text(
                text = strings.locLensTimeNoHistory,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
            )
            return
        }
        val overallAvg = items.map { it.trackedPercent }.average()
        val rolling7Avg = items.takeLast(7).map { it.trackedPercent }.average()
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = strings.locLensTrackedTimeChartTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val r7Percent = rolling7Avg.toInt()
                val r7Color = trackedTimeColor((rolling7Avg / 100.0).toFloat())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = strings.locLensChartStat7dLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = colorScheme.onSurfaceVariant,
                    )
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(r7Color))
                    Text(
                        text = strings.locLensFocusAvgValue.formatArgs(r7Percent),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = r7Color,
                    )
                }
                val avgPercent = overallAvg.toInt()
                val avgColor = trackedTimeColor((overallAvg / 100.0).toFloat())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = strings.locAll,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = colorScheme.onSurfaceVariant,
                    )
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(avgColor))
                    Text(
                        text = strings.locLensFocusAvgValue.formatArgs(avgPercent),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = avgColor,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            items.reversed().forEach { stat ->
                val pct = (stat.trackedPercent / 100.0).toFloat().coerceIn(0f, 1f)
                val hasTracking = stat.trackedPercent > 0.0
                val barFraction = if (hasTracking) pct.coerceAtLeast(0.04f) else 0.04f
                val barColor = if (hasTracking) trackedTimeColor(pct) else colorScheme.onSurface.copy(alpha = 0.12f)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    if (hasTracking) {
                        Text(
                            text = "${stat.trackedPercent.toInt()}",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(24.dp)
                            .height((maxBarHeightDp * barFraction).dp)
                            .background(barColor, shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = dayLabel(stat.dayKey), style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, maxLines = 1, color = colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun trackedTimeColor(
    fraction: Float,
): Color = lensesTimeGradientColor(fraction)

@Composable
internal fun MinimalFocusedHoursBarChart(items: List<DailyFocusedHoursStat>) {
    val strings = LocalUiStrings.current
    val maxBarHeightDp = 100f
    val maxHours = 24.0
    val colorScheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (items.isEmpty()) {
            Text(
                text = strings.locLensTimeNoHistory,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
            )
            return
        }
        val validHours = items.map { it.focusedHours }
        val overallAvg = validHours.average()
        val rolling7Avg = items.takeLast(7).map { it.focusedHours }.average()
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = strings.locLensFocusedHoursChartTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val rolling7Percent = focusedHoursToPercent(rolling7Avg, maxHours)
                val r7Color = focusedHoursColor(rolling7Avg)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = strings.locLensChartStat7dLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = colorScheme.onSurfaceVariant,
                    )
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(r7Color))
                    Text(
                        text = strings.locLensFocusAvgValue.formatArgs(rolling7Percent),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = r7Color,
                    )
                }
                val overallPercent = focusedHoursToPercent(overallAvg, maxHours)
                val avgColor = focusedHoursColor(overallAvg)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = strings.locAll,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = colorScheme.onSurfaceVariant,
                    )
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(avgColor))
                    Text(
                        text = strings.locLensFocusAvgValue.formatArgs(overallPercent),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = avgColor,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            items.reversed().forEach { stat ->
                val safeFocusedHours = sanitizeFocusedHours(stat.focusedHours)
                val barFraction = (safeFocusedHours / maxHours).toFloat().coerceIn(0f, 1f).coerceAtLeast(0.04f)
                val barColor = focusedHoursColor(safeFocusedHours)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    if (safeFocusedHours > 0.0) {
                        Text(
                            text = focusedHoursToPercent(safeFocusedHours, maxHours).toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(24.dp)
                            .height((maxBarHeightDp * barFraction).dp)
                            .background(barColor, shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = dayLabel(stat.dayKey), style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, maxLines = 1, color = colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun focusedHoursColor(
    hours: Double,
): Color = lensesTimeGradientColor((hours / 24.0).toFloat())

internal fun sanitizeFocusedHours(hours: Double): Double = if (hours.isFinite()) hours else 0.0

internal fun focusedHoursToPercent(hours: Double, maxHours: Double = 24.0): Int {
    if (!hours.isFinite() || !maxHours.isFinite() || maxHours <= 0.0) {
        return 0
    }
    val normalized = (hours / maxHours).coerceIn(0.0, 1.0)
    return (normalized * 100.0).toInt()
}

internal fun lensesTimeGradientColor(normalizedFraction: Float): Color {
    val safeFraction = if (normalizedFraction.isFinite()) normalizedFraction else 0f
    val clamped = safeFraction.coerceIn(0f, 1f)
    return if (clamped <= 0.5f) {
        lerp(
            start = LENS_CHART_LOW_COLOR,
            stop = LENS_CHART_MID_COLOR,
            fraction = clamped / 0.5f,
        )
    } else {
        lerp(
            start = LENS_CHART_MID_COLOR,
            stop = LENS_CHART_HIGH_COLOR,
            fraction = (clamped - 0.5f) / 0.5f,
        )
    }
}

private fun dayLabel(dayKey: String): String {
    // dayKey format: "YYYY-MM-DD" — show "DD/MM" for compact readable label
    return if (dayKey.length >= 10) {
        "${dayKey.substring(8, 10)}/${dayKey.substring(5, 7)}"
    } else {
        dayKey.takeLast(5)
    }
}
