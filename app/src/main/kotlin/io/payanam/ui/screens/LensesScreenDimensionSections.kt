//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.ui.components.DimensionBadgeLabelRow
import io.payanam.ui.viewmodel.LensUiState
import io.payanam.ui.viewmodel.LocalAppPreferences
import io.payanam.ui.viewmodel.colorForDimension
import io.payanam.ui.viewmodel.colorForDimensionId
import io.payanam.ui.viewmodel.isVisibleDimensionId
import io.payanam.ui.viewmodel.labelForDimension
import io.payanam.ui.viewmodel.labelForDimensionId
import java.util.Locale
import kotlin.math.absoluteValue

private val logger = UnifiedLogger.getInstance()

@Composable
internal fun OverallDimensionSnapshotCard(uiState: LensUiState) {
    /** If. */
    if (uiState.selectedRangeSummary == null) {
        logger.d("LensesScreenDimensionSections", "Overall dimension snapshot awaiting summary")
    }
    /** App prefs. */
    val appPrefs = LocalAppPreferences.current
    /** Planned map. */
    val plannedMap = uiState.selectedRangeSummary?.plannedByDimension ?: emptyMap()
    /** Actual map. */
    val actualMap = uiState.selectedRangeSummary?.actualByDimension ?: emptyMap()
    /** Ids. */
    val ids = collectDimensionIds(uiState).filter(appPrefs::isVisibleDimensionId)

    /** Card. */
    Card(modifier = Modifier.fillMaxWidth()) {
        /** Column. */
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            /** Text. */
            Text(stringResource(id = R.string.loc_lens_group_by_dimension), fontWeight = FontWeight.SemiBold)
            /** If. */
            if (ids.isEmpty()) {
                /** Text. */
                Text(stringResource(id = R.string.loc_lens_no_dimension_distribution))
            } else {
                ids.forEach { id ->
                    /** Label. */
                    val label = appPrefs.labelForDimensionId(id)
                        ?: appPrefs.labelForDimension(id, DimensionTaxonomyCatalog.fromCanonicalId(id)?.fallbackLabel)
                        ?: stringResource(id = R.string.loc_dimension_fallback_unassigned)
                    /** Color. */
                    val color = appPrefs.colorForDimensionId(id)
                        ?: appPrefs.colorForDimension(id, DimensionTaxonomyCatalog.fromCanonicalId(id)?.fallbackLabel)
                        ?: MaterialTheme.colorScheme.primary
                    /** Planned. */
                    val planned = plannedMap[id] ?: 0
                    /** Actual. */
                    val actual = actualMap[id] ?: 0
                    /** Row. */
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        /** Dimension badge label row. */
                        DimensionBadgeLabelRow(
                            prefs = appPrefs,
                            dimensionId = id,
                            fallbackLabel = label,
                            fallbackColor = color,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        /** Spacer. */
                        Spacer(modifier = Modifier.width(8.dp))
                        /** Text. */
                        Text(
                            text = stringResource(
                                id = R.string.loc_plan_reality_totals_line,
                                /** Format minutes. */
                                formatMinutes(planned),
                                /** Format minutes. */
                                formatMinutes(actual),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun DimensionModuleDrilldownCard(uiState: LensUiState) {
    /** If. */
    if (uiState.selectedRangeSummary == null) {
        logger.d("LensesScreenDimensionSections", "Dimension module drilldown awaiting summary")
    }
    /** App prefs. */
    val appPrefs = LocalAppPreferences.current
    /** Summary. */
    val summary = uiState.selectedRangeSummary
    /** Ids. */
    val ids = collectDimensionIds(uiState).filter(appPrefs::isVisibleDimensionId)
    /** If. */
    if (ids.isEmpty()) {
        /** Card. */
        Card(modifier = Modifier.fillMaxWidth()) {
            /** Column. */
            Column(modifier = Modifier.padding(12.dp)) {
                /** Text. */
                Text(stringResource(id = R.string.loc_lens_no_dimension_distribution))
            }
        }
        /** Return. */
        return
    }

    ids.forEach { id ->
        /** Label. */
        val label = appPrefs.labelForDimensionId(id)
            ?: appPrefs.labelForDimension(id, DimensionTaxonomyCatalog.fromCanonicalId(id)?.fallbackLabel)
            ?: stringResource(id = R.string.loc_dimension_fallback_unassigned)
        /** Color. */
        val color = appPrefs.colorForDimensionId(id)
            ?: appPrefs.colorForDimension(id, DimensionTaxonomyCatalog.fromCanonicalId(id)?.fallbackLabel)
            ?: MaterialTheme.colorScheme.primary
        /** Planned time. */
        val plannedTime = summary?.plannedByDimension?.get(id) ?: 0
        /** Actual time. */
        val actualTime = summary?.actualByDimension?.get(id) ?: 0
        /** Planned tasks. */
        val plannedTasks = summary?.plannedTasksByDimension?.get(id) ?: 0
        /** Completed tasks. */
        val completedTasks = summary?.completedTasksByDimension?.get(id) ?: 0
        /** Missed tasks. */
        val missedTasks = summary?.missedTasksByDimension?.get(id) ?: 0
        /** Planned habits. */
        val plannedHabits = summary?.plannedHabitsByDimension?.get(id) ?: 0
        /** Completed habits. */
        val completedHabits = summary?.completedHabitsByDimension?.get(id) ?: 0
        /** Missed habits. */
        val missedHabits = summary?.missedHabitsByDimension?.get(id) ?: 0
        /** Reflections by dimension. */
        val reflectionsByDimension = uiState.reflections.filter { (it.dimensionId ?: LENS_UNASSIGNED_DIMENSION_KEY) == id }
        /** Addressed reflections. */
        val addressedReflections = reflectionsByDimension.count { it.isAddressed }
        /** Tagged reflections. */
        val taggedReflections = uiState.reflections.count { (it.dimensionId ?: LENS_UNASSIGNED_DIMENSION_KEY) == id }

        /** Card. */
        Card(modifier = Modifier.fillMaxWidth()) {
            /** Column. */
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                /** Dimension badge label row. */
                DimensionBadgeLabelRow(
                    prefs = appPrefs,
                    dimensionId = id,
                    fallbackLabel = label,
                    fallbackColor = color,
                    labelColor = color,
                    modifier = Modifier.fillMaxWidth(),
                )
                /** Text. */
                Text(
                    /** String resource. */
                    stringResource(
                        id = R.string.loc_tagged_title,
                        /** String resource. */
                        stringResource(id = R.string.loc_time),
                        /** String resource. */
                        stringResource(id = R.string.loc_plan_reality_totals_line, formatMinutes(plannedTime), formatMinutes(actualTime)),
                    ),
                )
                /** Text. */
                Text(
                    /** String resource. */
                    stringResource(
                        id = R.string.loc_tagged_title,
                        /** String resource. */
                        stringResource(id = R.string.settings_database_tasks),
                        /** String resource. */
                        stringResource(id = R.string.loc_completed_tasks_ratio, completedTasks, plannedTasks),
                    ),
                )
                /** Text. */
                Text(stringResource(id = R.string.loc_lens_missed_tasks_line, missedTasks))
                /** Text. */
                Text(
                    /** String resource. */
                    stringResource(
                        id = R.string.loc_tagged_title,
                        /** String resource. */
                        stringResource(id = R.string.loc_habits),
                        /** String resource. */
                        stringResource(id = R.string.loc_completed_habits_ratio, completedHabits, plannedHabits),
                    ),
                )
                /** Text. */
                Text(stringResource(id = R.string.loc_lens_missed_habits_line, missedHabits))
                /** Text. */
                Text(
                    /** String resource. */
                    stringResource(
                        id = R.string.loc_tagged_title,
                        /** String resource. */
                        stringResource(id = R.string.loc_journal_notes),
                        /** String resource. */
                        stringResource(id = R.string.loc_lens_addressed_reflections_line, addressedReflections, reflectionsByDimension.size),
                    ),
                )
                /** Text. */
                Text(
                    /** String resource. */
                    stringResource(
                        id = R.string.loc_tagged_title,
                        /** String resource. */
                        stringResource(id = R.string.settings_database_notes),
                        /** String resource. */
                        stringResource(id = R.string.loc_lens_dimension_tagged_reflections_line, taggedReflections),
                    ),
                )
            }
        }
    }
}

internal fun taggedDimensionLine(
    /** Line. */
    line: String,
    /** Dimension label. */
    dimensionLabel: String,
    /** Dimension color. */
    dimensionColor: Color,
): AnnotatedString {
    /** If. */
    if (dimensionLabel.isBlank()) {
        return AnnotatedString(line)
    }
    /** Start. */
    val start = line.indexOf(dimensionLabel)
    /** If. */
    if (start < 0) {
        return AnnotatedString(line)
    }
    return buildAnnotatedString {
        /** Append. */
        append(line)
        /** Add style. */
        addStyle(
            style = SpanStyle(color = dimensionColor),
            start = start,
            end = start + dimensionLabel.length,
        )
    }
}

internal fun formatSignedMinutes(minutes: Long): String {
    /** Base. */
    val base = formatMinutes(minutes.absoluteValue.toInt())
    return when {
        minutes > 0 -> "+$base"
        minutes < 0 -> "-$base"
        else -> base
    }
}

internal fun formatLensScore(score: Double): String = String.format(Locale.US, "%.5f", score.coerceIn(0.0, 1.0))

internal fun formatSignedLensScore(score: Double): String {
    /** Value. */
    val value = formatLensScore(score)
    return when {
        score > 0.0 -> "+$value"
        score < 0.0 -> "-${formatLensScore(score.absoluteValue)}"
        else -> value
    }
}

internal fun calculateBoundedTimeModuleScore(
    /** Planned minutes. */
    plannedMinutes: Int,
    /** Actual minutes. */
    actualMinutes: Int,
): Double {
    /** Planned. */
    val planned = plannedMinutes.coerceIn(0, LENS_DAY_MINUTES)
    /** Actual. */
    val actual = actualMinutes.coerceIn(0, LENS_DAY_MINUTES)
    /** If. */
    if (planned == actual) {
        return 1.0
    }
    /** If. */
    if (actual < planned) {
        /** If. */
        if (planned == 0) {
            return 0.0
        }
        /** Lower span. */
        val lowerSpan = planned.toDouble()
        /** Deviation. */
        val deviation = (planned - actual).toDouble()
        /** Return. */
        return (1.0 - (deviation / lowerSpan)).coerceIn(0.0, 1.0)
    }
    /** If. */
    if (planned == LENS_DAY_MINUTES) {
        return 0.0
    }
    /** Upper span. */
    val upperSpan = (LENS_DAY_MINUTES - planned).toDouble()
    /** Deviation. */
    val deviation = (actual - planned).toDouble()
    /** Return. */
    return (1.0 - (deviation / upperSpan)).coerceIn(0.0, 1.0)
}

internal fun calculateWeightedTimeModuleScore(rows: List<TimeDimensionScoreRow>): Double {
    /** If. */
    if (rows.isEmpty()) {
        return 0.0
    }
    /** Weighted score. */
    var weightedScore = 0.0
    /** Total weight. */
    var totalWeight = 0.0
    rows.forEach { row ->
        /** Weight. */
        val weight = DimensionTaxonomyCatalog.defaultWeightForDimensionId(row.dimensionId)
        weightedScore += row.score * weight
        totalWeight += weight
    }
    return if (totalWeight > 0.0) (weightedScore / totalWeight).coerceIn(0.0, 1.0) else 0.0
}

internal data class TimeDimensionScoreRow(
    /** Dimension id. */
    val dimensionId: String,
    /** Planned minutes. */
    val plannedMinutes: Int,
    /** Actual minutes. */
    val actualMinutes: Int,
    /** Score. */
    val score: Double,
    /** Deviation minutes. */
    val deviationMinutes: Int,
)
