//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.ui.screens

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.repository.DailyFocusStat
import io.payanam.domain.repository.DailyFocusedHoursStat
import io.payanam.domain.repository.DailyTrackedTimeStat
import io.payanam.ui.components.DimensionIdentityRow
import io.payanam.ui.viewmodel.AppPreferencesState
import io.payanam.ui.viewmodel.LensRangeSummary
import io.payanam.ui.viewmodel.LensUiState
import io.payanam.ui.viewmodel.LocalAppPreferences
import io.payanam.ui.viewmodel.TimeModuleDayMetric
import io.payanam.ui.viewmodel.colorForDimension
import io.payanam.ui.viewmodel.colorForDimensionId
import io.payanam.ui.viewmodel.labelForDimension
import io.payanam.ui.viewmodel.labelForDimensionId
import kotlin.math.round

private const val LENS_CARD_SCORE_SCALE = 100000.0
private val LENS_CHART_LOW_COLOR = Color(0xFFD32F2F)
private val LENS_CHART_MID_COLOR = Color(0xFFFBC02D)
private val LENS_CHART_HIGH_COLOR = Color(0xFF388E3C)
private val logger = UnifiedLogger.getInstance()

@Composable
internal fun TimeModuleSectionContent(
    /** Ui state. */
    uiState: LensUiState,
    summary: LensRangeSummary?,
    dimensionIds: List<String>,
    /** Include supplemental actual. */
    includeSupplementalActual: Boolean,
    onRequestMoreHistory: () -> Unit = {},
) {
    /** App prefs. */
    val appPrefs = LocalAppPreferences.current
    /** Score cards enabled. */
    val scoreCardsEnabled = appPrefs.chartTimeScoreCardsEnabled
    /** Overall score card enabled. */
    val overallScoreCardEnabled = scoreCardsEnabled && appPrefs.chartTimeOverallScoreCardEnabled
    /** Dimension score cards enabled. */
    val dimensionScoreCardsEnabled = scoreCardsEnabled && appPrefs.chartTimeDimensionScoreCardsEnabled
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
    /** Planned map. */
    val plannedMap = summary?.plannedByDimension ?: emptyMap()
    /** Base actual map. */
    val baseActualMap = summary?.actualByDimension ?: emptyMap()
    /** Supplemental actual map. */
    val supplementalActualMap = summary?.supplementalActualByDimension ?: emptyMap()
    /** Actual map. */
    val actualMap = if (includeSupplementalActual) {
        /** Base actual map. */
        baseActualMap
    } else {
        baseActualMap.mapValues { (dimensionId, minutes) ->
            (minutes - (supplementalActualMap[dimensionId] ?: 0)).coerceAtLeast(0)
        }
    }
    /** Time dimension ids. */
    val timeDimensionIds = dimensionIds.filter { id -> plannedMap.containsKey(id) || actualMap.containsKey(id) }
    /** Score rows. */
    val scoreRows = timeDimensionIds.map { id ->
        /** Planned. */
        val planned = (plannedMap[id] ?: 0).coerceIn(0, LENS_DAY_MINUTES)
        /** Actual. */
        val actual = (actualMap[id] ?: 0).coerceIn(0, LENS_DAY_MINUTES)
        /** Time dimension score row. */
        TimeDimensionScoreRow(
            dimensionId = id,
            plannedMinutes = planned,
            actualMinutes = actual,
            score = calculateBoundedTimeModuleScore(plannedMinutes = planned, actualMinutes = actual),
            deviationMinutes = actual - planned,
        )
    }
    /** History. */
    val history = uiState.timeModuleHistorySummary
    /** Ordered metrics. */
    val orderedMetrics = history?.metrics?.sortedByDescending { it.dayKey } ?: emptyList()
    /** Ascending metrics. */
    val ascendingMetrics = orderedMetrics.asReversed()
    /** Current metric. */
    val currentMetric = orderedMetrics.firstOrNull()
    /** Score values. */
    val scoreValues = orderedMetrics.map { it.dayScore }
    /** Progress values. */
    val progressValues = orderedMetrics.map { it.progressDelta }
    /** Streak values. */
    val streakValues = orderedMetrics.map { it.progressStreak.toDouble() }
    /** Progress by day. */
    val progressByDay = buildPerDimensionProgressByDay(ascendingMetrics, timeDimensionIds)
    /** Streak by day. */
    val streakByDay = buildPerDimensionStreakByDay(ascendingMetrics, timeDimensionIds, progressByDay)

    /** Launched effect. */
    LaunchedEffect(timeDimensionIds.size, orderedMetrics.size) {
        logger.d(
            "LensesScreen.TimeModuleSectionContent",
            "Built time module card section",
            /** Map of. */
            mapOf("dimensionCards" to timeDimensionIds.size, "historyDays" to orderedMetrics.size),
        )
    }

    /** If. */
    if (overallScoreCardEnabled && history != null) {
        /** Lens time metrics card. */
        LensTimeMetricsCard(
            appPrefs = appPrefs,
            title = stringResource(id = R.string.loc_lens_time_overall_label),
            accentColor = MaterialTheme.colorScheme.primary,
            plannedMinutes = summary?.totalPlannedMinutes ?: 0,
            trackedMinutes = ((summary?.totalActualMinutes ?: 0) - if (includeSupplementalActual) 0 else (summary?.supplementalActualMinutes ?: 0)).coerceAtLeast(0),
            tertiaryTimeLabel = stringResource(id = R.string.loc_untracked),
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

    /** If. */
    if (dimensionScoreCardsEnabled && history != null) {
        /** If. */
        if (timeDimensionIds.isEmpty()) {
            /** Text. */
            Text(stringResource(id = R.string.loc_lens_no_dimension_distribution))
        } else {
            /** Text. */
            Text(stringResource(id = R.string.loc_lens_group_by_dimension), fontWeight = FontWeight.Medium)
            scoreRows.forEach { row ->
                /** Id. */
                val id = row.dimensionId
                /** Label. */
                val label = appPrefs.labelForDimensionId(id)
                    ?: appPrefs.labelForDimension(id, DimensionTaxonomyCatalog.fromCanonicalId(id)?.fallbackLabel)
                    ?: stringResource(id = R.string.loc_dimension_fallback_unassigned)
                /** Color. */
                val color = appPrefs.colorForDimensionId(id)
                    ?: appPrefs.colorForDimension(id, DimensionTaxonomyCatalog.fromCanonicalId(id)?.fallbackLabel)
                    ?: MaterialTheme.colorScheme.primary
                /** Dimension score values. */
                val dimensionScoreValues = orderedMetrics.map { metric -> metric.perDimensionScores[id] ?: 0.0 }
                /** Dimension progress values. */
                val dimensionProgressValues = orderedMetrics.map { metric ->
                    progressByDay[metric.dayKey]?.get(id) ?: 0.0
                }
                /** Dimension streak values. */
                val dimensionStreakValues = orderedMetrics.map { metric ->
                    (streakByDay[metric.dayKey]?.get(id) ?: 0).toDouble()
                }
                /** Current dimension score. */
                val currentDimensionScore = currentMetric?.perDimensionScores?.get(id) ?: row.score
                /** Current dimension progress. */
                val currentDimensionProgress = currentMetric?.dayKey?.let { dayKey ->
                    progressByDay[dayKey]?.get(id)
                } ?: 0.0
                /** Current dimension streak. */
                val currentDimensionStreak = currentMetric?.dayKey?.let { dayKey ->
                    streakByDay[dayKey]?.get(id)
                } ?: 0

                /** Lens time metrics card. */
                LensTimeMetricsCard(
                    appPrefs = appPrefs,
                    dimensionId = id,
                    title = label,
                    accentColor = color,
                    plannedMinutes = row.plannedMinutes,
                    trackedMinutes = row.actualMinutes,
                    tertiaryTimeLabel = stringResource(id = R.string.loc_deviation),
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

    /** If. */
    if (lineGraphsEnabled && history != null) {
        /** Lenses time inline charts. */
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
    /** App prefs. */
    appPrefs: AppPreferencesState,
    dimensionId: String? = null,
    /** Title. */
    title: String,
    /** Accent color. */
    accentColor: Color,
    /** Planned minutes. */
    plannedMinutes: Int,
    /** Tracked minutes. */
    trackedMinutes: Int,
    /** Tertiary time label. */
    tertiaryTimeLabel: String,
    /** Tertiary time value. */
    tertiaryTimeValue: String,
    /** Score. */
    score: Double,
    /** Score rank. */
    scoreRank: String,
    /** Progress. */
    progress: Double,
    /** Progress rank. */
    progressRank: String,
    /** Streak. */
    streak: Int,
    /** Streak rank. */
    streakRank: String,
) {
    /** Card. */
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)),
    ) {
        /** Column. */
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            /** Row. */
            Row(verticalAlignment = Alignment.CenterVertically) {
                /** Dimension identity row. */
                DimensionIdentityRow(
                    prefs = appPrefs,
                    dimensionId = dimensionId,
                    fallbackLabel = title,
                    fallbackColor = accentColor,
                    showLabel = false,
                )
                /** Spacer. */
                Spacer(modifier = Modifier.width(8.dp))
                /** Text. */
                Text(text = title, fontWeight = FontWeight.SemiBold, color = accentColor)
            }
            /** Row. */
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                /** Time split chip. */
                TimeSplitChip(
                    modifier = Modifier.weight(1f),
                    label = stringResource(id = R.string.loc_planned),
                    value = formatMinutes(plannedMinutes),
                )
                /** Time split chip. */
                TimeSplitChip(
                    modifier = Modifier.weight(1f),
                    label = stringResource(id = R.string.loc_tracked_time),
                    value = formatMinutes(trackedMinutes),
                )
                /** Time split chip. */
                TimeSplitChip(
                    modifier = Modifier.weight(1f),
                    label = tertiaryTimeLabel,
                    value = tertiaryTimeValue,
                )
            }
            /** Row. */
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                /** Metric cell. */
                MetricCell(
                    modifier = Modifier.weight(1f),
                    label = stringResource(id = R.string.loc_score),
                    value = formatLensScore(score),
                )
                /** Metric cell. */
                MetricCell(
                    modifier = Modifier.weight(1f),
                    label = stringResource(id = R.string.loc_lens_time_progress_label),
                    value = formatSignedLensScore(progress),
                )
                /** Metric cell. */
                MetricCell(
                    modifier = Modifier.weight(1f),
                    label = stringResource(id = R.string.loc_lens_time_streak_label),
                    value = streak.toString(),
                )
            }
            /** Row. */
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                /** Metric cell. */
                MetricCell(
                    modifier = Modifier.weight(1f),
                    label = stringResource(id = R.string.loc_lens_time_score_rank_label),
                    value = scoreRank,
                )
                /** Metric cell. */
                MetricCell(
                    modifier = Modifier.weight(1f),
                    label = stringResource(id = R.string.loc_lens_time_progress_rank_label),
                    value = progressRank,
                )
                /** Metric cell. */
                MetricCell(
                    modifier = Modifier.weight(1f),
                    label = stringResource(id = R.string.loc_lens_time_streak_rank_label),
                    value = streakRank,
                )
            }
        }
    }
}

@Composable
private fun TimeSplitChip(
    /** Modifier. */
    modifier: Modifier,
    /** Label. */
    label: String,
    /** Value. */
    value: String,
) {
    /** Card. */
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f)),
    ) {
        /** Column. */
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            /** Text. */
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            /** Text. */
            Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun MetricCell(
    /** Modifier. */
    modifier: Modifier,
    /** Label. */
    label: String,
    /** Value. */
    value: String,
) {
    /** Column. */
    Column(modifier = modifier) {
        /** Text. */
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        /** Text. */
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun rankLabel(rank: Int, total: Int): String = stringResource(id = R.string.loc_lens_rank_short_line, rank.coerceAtLeast(0), total.coerceAtLeast(0))

private fun denseRankDescending(current: Double, values: List<Double>): Int {
    /** If. */
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
    /** If. */
    if (ascendingMetrics.isEmpty() || dimensionIds.isEmpty()) {
        return emptyMap()
    }
    /** Previous score by dimension. */
    val previousScoreByDimension = mutableMapOf<String, Double>()
    /** Progress by day. */
    val progressByDay = mutableMapOf<String, Map<String, Double>>()
    ascendingMetrics.forEachIndexed { index, metric ->
        /** Day map. */
        val dayMap = mutableMapOf<String, Double>()
        dimensionIds.forEach { dimensionId ->
            /** Score. */
            val score = metric.perDimensionScores[dimensionId] ?: 0.0
            /** Previous score. */
            val previousScore = previousScoreByDimension[dimensionId] ?: 0.0
            /** Delta. */
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
    /** If. */
    if (ascendingMetrics.isEmpty() || dimensionIds.isEmpty()) {
        return emptyMap()
    }
    /** Previous delta by dimension. */
    val previousDeltaByDimension = mutableMapOf<String, Double>()
    /** Previous streak by dimension. */
    val previousStreakByDimension = mutableMapOf<String, Int>()
    /** Streak by day. */
    val streakByDay = mutableMapOf<String, Map<String, Int>>()
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
        streakByDay[metric.dayKey] = dayMap
    }
    return streakByDay
}

@Composable
internal fun MinimalFocusBarChart(items: List<DailyFocusStat>) {
    /** Max bar height dp. */
    val maxBarHeightDp = 100f
    /** Color scheme. */
    val colorScheme = MaterialTheme.colorScheme

    /** Column. */
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        /** If. */
        if (items.isEmpty()) {
            /** Text. */
            Text(
                text = stringResource(id = R.string.loc_lens_time_no_history),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
            )
            /** Return. */
            return
        }

        // Overall + rolling 7-day average header
        /** Valid scores. */
        val validScores = items.mapNotNull { it.avgFocus }
        /** Overall avg. */
        val overallAvg = if (validScores.isNotEmpty()) validScores.average() else null
        /** Rolling7avg. */
        val rolling7Avg = items.takeLast(7).mapNotNull { it.avgFocus }.takeIf { it.isNotEmpty() }?.average()
        /** Column. */
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            /** Text. */
            Text(
                text = stringResource(id = R.string.loc_lens_minimal_focus_chart_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            /** If. */
            if (overallAvg != null || rolling7Avg != null) {
                /** Row. */
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    /** If. */
                    if (rolling7Avg != null) {
                        /** R7percent. */
                        val r7Percent = (rolling7Avg * 100).toInt()
                        /** R7color. */
                        val r7Color = focusColor(rolling7Avg.toFloat())
                        /** Row. */
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            /** Text. */
                            Text(
                                text = stringResource(id = R.string.loc_lens_chart_stat_7d_label),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = colorScheme.onSurfaceVariant,
                            )
                            /** Box. */
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(r7Color))
                            /** Text. */
                            Text(
                                text = stringResource(id = R.string.loc_lens_focus_avg_value, r7Percent),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = r7Color,
                            )
                        }
                    }
                    /** If. */
                    if (overallAvg != null) {
                        /** Avg percent. */
                        val avgPercent = (overallAvg * 100).toInt()
                        /** Avg color. */
                        val avgColor = focusColor(overallAvg.toFloat())
                        /** Row. */
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            /** Text. */
                            Text(
                                text = stringResource(id = R.string.loc_all),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = colorScheme.onSurfaceVariant,
                            )
                            /** Box. */
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(avgColor))
                            /** Text. */
                            Text(
                                text = stringResource(id = R.string.loc_lens_focus_avg_value, avgPercent),
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
        /** Row. */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            items.reversed().forEach { stat ->
                /** Focus float. */
                val focusFloat = stat.avgFocus?.toFloat()?.coerceIn(0f, 1f) ?: 0f
                /** Bar height fraction. */
                val barHeightFraction = focusFloat.coerceAtLeast(0.04f)
                /** Bar color. */
                val barColor = when {
                    stat.avgFocus == null -> colorScheme.onSurface.copy(alpha = 0.12f)
                    else -> focusColor(focusFloat)
                }
                /** Column. */
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    // Percentage label above bar
                    /** If. */
                    if (stat.avgFocus != null) {
                        /** Text. */
                        Text(
                            text = "${(focusFloat * 100).toInt()}",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                    /** Box. */
                    Box(
                        modifier = Modifier
                            .width(24.dp)
                            .height((maxBarHeightDp * barHeightFraction).dp)
                            .background(barColor, shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                    )
                    /** Spacer. */
                    Spacer(modifier = Modifier.height(2.dp))
                    /** Text. */
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
    /** Focus. */
    focus: Float,
): Color = lensesTimeGradientColor(focus)

@Composable
internal fun MinimalTrackedTimeBarChart(items: List<DailyTrackedTimeStat>) {
    /** Max bar height dp. */
    val maxBarHeightDp = 100f
    /** Color scheme. */
    val colorScheme = MaterialTheme.colorScheme

    /** Column. */
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        /** If. */
        if (items.isEmpty()) {
            /** Text. */
            Text(
                text = stringResource(id = R.string.loc_lens_time_no_history),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
            )
            /** Return. */
            return
        }

        /** Overall avg. */
        val overallAvg = items.map { it.trackedPercent }.average()
        /** Rolling7avg. */
        val rolling7Avg = items.takeLast(7).map { it.trackedPercent }.average()
        /** Column. */
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            /** Text. */
            Text(
                text = stringResource(id = R.string.loc_lens_tracked_time_chart_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            /** Row. */
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                /** R7percent. */
                val r7Percent = rolling7Avg.toInt()
                /** R7color. */
                val r7Color = trackedTimeColor((rolling7Avg / 100.0).toFloat())
                /** Row. */
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    /** Text. */
                    Text(
                        text = stringResource(id = R.string.loc_lens_chart_stat_7d_label),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = colorScheme.onSurfaceVariant,
                    )
                    /** Box. */
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(r7Color))
                    /** Text. */
                    Text(
                        text = stringResource(id = R.string.loc_lens_focus_avg_value, r7Percent),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = r7Color,
                    )
                }
                /** Avg percent. */
                val avgPercent = overallAvg.toInt()
                /** Avg color. */
                val avgColor = trackedTimeColor((overallAvg / 100.0).toFloat())
                /** Row. */
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    /** Text. */
                    Text(
                        text = stringResource(id = R.string.loc_all),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = colorScheme.onSurfaceVariant,
                    )
                    /** Box. */
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(avgColor))
                    /** Text. */
                    Text(
                        text = stringResource(id = R.string.loc_lens_focus_avg_value, avgPercent),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = avgColor,
                    )
                }
            }
        }

        /** Row. */
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            items.reversed().forEach { stat ->
                /** Pct. */
                val pct = (stat.trackedPercent / 100.0).toFloat().coerceIn(0f, 1f)
                /** Has tracking. */
                val hasTracking = stat.trackedPercent > 0.0
                /** Bar fraction. */
                val barFraction = if (hasTracking) pct.coerceAtLeast(0.04f) else 0.04f
                /** Bar color. */
                val barColor = if (hasTracking) trackedTimeColor(pct) else colorScheme.onSurface.copy(alpha = 0.12f)
                /** Column. */
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    /** If. */
                    if (hasTracking) {
                        /** Text. */
                        Text(
                            text = "${stat.trackedPercent.toInt()}",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                    /** Box. */
                    Box(
                        modifier = Modifier
                            .width(24.dp)
                            .height((maxBarHeightDp * barFraction).dp)
                            .background(barColor, shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                    )
                    /** Spacer. */
                    Spacer(modifier = Modifier.height(2.dp))
                    /** Text. */
                    Text(text = dayLabel(stat.dayKey), style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, maxLines = 1, color = colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun trackedTimeColor(
    /** Fraction. */
    fraction: Float,
): Color = lensesTimeGradientColor(fraction)

@Composable
internal fun MinimalFocusedHoursBarChart(items: List<DailyFocusedHoursStat>) {
    /** Max bar height dp. */
    val maxBarHeightDp = 100f
    /** Max hours. */
    val maxHours = 24.0
    /** Color scheme. */
    val colorScheme = MaterialTheme.colorScheme

    /** Column. */
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        /** If. */
        if (items.isEmpty()) {
            /** Text. */
            Text(
                text = stringResource(id = R.string.loc_lens_time_no_history),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
            )
            /** Return. */
            return
        }

        /** Valid hours. */
        val validHours = items.map { it.focusedHours }
        /** Overall avg. */
        val overallAvg = validHours.average()
        /** Rolling7avg. */
        val rolling7Avg = items.takeLast(7).map { it.focusedHours }.average()
        /** Column. */
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            /** Text. */
            Text(
                text = stringResource(id = R.string.loc_lens_focused_hours_chart_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            /** Row. */
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                /** Rolling7percent. */
                val rolling7Percent = focusedHoursToPercent(rolling7Avg, maxHours)
                /** R7color. */
                val r7Color = focusedHoursColor(rolling7Avg)
                /** Row. */
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    /** Text. */
                    Text(
                        text = stringResource(id = R.string.loc_lens_chart_stat_7d_label),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = colorScheme.onSurfaceVariant,
                    )
                    /** Box. */
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(r7Color))
                    /** Text. */
                    Text(
                        text = stringResource(id = R.string.loc_lens_focus_avg_value, rolling7Percent),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = r7Color,
                    )
                }
                /** Overall percent. */
                val overallPercent = focusedHoursToPercent(overallAvg, maxHours)
                /** Avg color. */
                val avgColor = focusedHoursColor(overallAvg)
                /** Row. */
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    /** Text. */
                    Text(
                        text = stringResource(id = R.string.loc_all),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = colorScheme.onSurfaceVariant,
                    )
                    /** Box. */
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(avgColor))
                    /** Text. */
                    Text(
                        text = stringResource(id = R.string.loc_lens_focus_avg_value, overallPercent),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = avgColor,
                    )
                }
            }
        }

        /** Row. */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            items.reversed().forEach { stat ->
                /** Safe focused hours. */
                val safeFocusedHours = sanitizeFocusedHours(stat.focusedHours)
                /** Bar fraction. */
                val barFraction = (safeFocusedHours / maxHours).toFloat().coerceIn(0f, 1f).coerceAtLeast(0.04f)
                /** Bar color. */
                val barColor = focusedHoursColor(safeFocusedHours)
                /** Column. */
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    /** If. */
                    if (safeFocusedHours > 0.0) {
                        /** Text. */
                        Text(
                            text = focusedHoursToPercent(safeFocusedHours, maxHours).toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                    /** Box. */
                    Box(
                        modifier = Modifier
                            .width(24.dp)
                            .height((maxBarHeightDp * barFraction).dp)
                            .background(barColor, shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                    )
                    /** Spacer. */
                    Spacer(modifier = Modifier.height(2.dp))
                    /** Text. */
                    Text(text = dayLabel(stat.dayKey), style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, maxLines = 1, color = colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun focusedHoursColor(
    /** Hours. */
    hours: Double,
): Color = lensesTimeGradientColor((hours / 24.0).toFloat())

internal fun sanitizeFocusedHours(hours: Double): Double = if (hours.isFinite()) hours else 0.0

internal fun focusedHoursToPercent(hours: Double, maxHours: Double = 24.0): Int {
    /** If. */
    if (!hours.isFinite() || !maxHours.isFinite() || maxHours <= 0.0) {
        return 0
    }
    /** Normalized. */
    val normalized = (hours / maxHours).coerceIn(0.0, 1.0)
    /** Return. */
    return (normalized * 100.0).toInt()
}

internal fun lensesTimeGradientColor(normalizedFraction: Float): Color {
    /** Safe fraction. */
    val safeFraction = if (normalizedFraction.isFinite()) normalizedFraction else 0f
    /** Clamped. */
    val clamped = safeFraction.coerceIn(0f, 1f)
    return if (clamped <= 0.5f) {
        /** Lerp. */
        lerp(
            start = LENS_CHART_LOW_COLOR,
            stop = LENS_CHART_MID_COLOR,
            fraction = clamped / 0.5f,
        )
    } else {
        /** Lerp. */
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
