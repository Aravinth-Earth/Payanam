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
    if (uiState.selectedRangeSummary == null) {
        logger.d("LensesScreenDimensionSections", "Overall dimension snapshot awaiting summary")
    }
    val appPrefs = LocalAppPreferences.current
    val plannedMap = uiState.selectedRangeSummary?.plannedByDimension ?: emptyMap()
    val actualMap = uiState.selectedRangeSummary?.actualByDimension ?: emptyMap()
    val ids = collectDimensionIds(uiState).filter(appPrefs::isVisibleDimensionId)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(id = R.string.loc_lens_group_by_dimension), fontWeight = FontWeight.SemiBold)
            if (ids.isEmpty()) {
                Text(stringResource(id = R.string.loc_lens_no_dimension_distribution))
            } else {
                ids.forEach { id ->
                    val label = appPrefs.labelForDimensionId(id)
                        ?: appPrefs.labelForDimension(id, DimensionTaxonomyCatalog.fromCanonicalId(id)?.fallbackLabel)
                        ?: stringResource(id = R.string.loc_dimension_fallback_unassigned)
                    val color = appPrefs.colorForDimensionId(id)
                        ?: appPrefs.colorForDimension(id, DimensionTaxonomyCatalog.fromCanonicalId(id)?.fallbackLabel)
                        ?: MaterialTheme.colorScheme.primary
                    val planned = plannedMap[id] ?: 0
                    val actual = actualMap[id] ?: 0
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DimensionBadgeLabelRow(
                            prefs = appPrefs,
                            dimensionId = id,
                            fallbackLabel = label,
                            fallbackColor = color,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                id = R.string.loc_plan_reality_totals_line,
                                formatMinutes(planned),
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
    if (uiState.selectedRangeSummary == null) {
        logger.d("LensesScreenDimensionSections", "Dimension module drilldown awaiting summary")
    }
    val appPrefs = LocalAppPreferences.current
    val summary = uiState.selectedRangeSummary
    val ids = collectDimensionIds(uiState).filter(appPrefs::isVisibleDimensionId)
    if (ids.isEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(stringResource(id = R.string.loc_lens_no_dimension_distribution))
            }
        }
        return
    }

    ids.forEach { id ->
        val label = appPrefs.labelForDimensionId(id)
            ?: appPrefs.labelForDimension(id, DimensionTaxonomyCatalog.fromCanonicalId(id)?.fallbackLabel)
            ?: stringResource(id = R.string.loc_dimension_fallback_unassigned)
        val color = appPrefs.colorForDimensionId(id)
            ?: appPrefs.colorForDimension(id, DimensionTaxonomyCatalog.fromCanonicalId(id)?.fallbackLabel)
            ?: MaterialTheme.colorScheme.primary
        val plannedTime = summary?.plannedByDimension?.get(id) ?: 0
        val actualTime = summary?.actualByDimension?.get(id) ?: 0
        val plannedTasks = summary?.plannedTasksByDimension?.get(id) ?: 0
        val completedTasks = summary?.completedTasksByDimension?.get(id) ?: 0
        val missedTasks = summary?.missedTasksByDimension?.get(id) ?: 0
        val plannedHabits = summary?.plannedHabitsByDimension?.get(id) ?: 0
        val completedHabits = summary?.completedHabitsByDimension?.get(id) ?: 0
        val missedHabits = summary?.missedHabitsByDimension?.get(id) ?: 0
        val reflectionsByDimension = uiState.reflections.filter { (it.dimensionId ?: LENS_UNASSIGNED_DIMENSION_KEY) == id }
        val addressedReflections = reflectionsByDimension.count { it.isAddressed }
        val taggedReflections = uiState.reflections.count { (it.dimensionId ?: LENS_UNASSIGNED_DIMENSION_KEY) == id }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DimensionBadgeLabelRow(
                    prefs = appPrefs,
                    dimensionId = id,
                    fallbackLabel = label,
                    fallbackColor = color,
                    labelColor = color,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(
                        id = R.string.loc_tagged_title,
                        stringResource(id = R.string.loc_time),
                        stringResource(id = R.string.loc_plan_reality_totals_line, formatMinutes(plannedTime), formatMinutes(actualTime)),
                    ),
                )
                Text(
                    stringResource(
                        id = R.string.loc_tagged_title,
                        stringResource(id = R.string.settings_database_tasks),
                        stringResource(id = R.string.loc_completed_tasks_ratio, completedTasks, plannedTasks),
                    ),
                )
                Text(stringResource(id = R.string.loc_lens_missed_tasks_line, missedTasks))
                Text(
                    stringResource(
                        id = R.string.loc_tagged_title,
                        stringResource(id = R.string.loc_habits),
                        stringResource(id = R.string.loc_completed_habits_ratio, completedHabits, plannedHabits),
                    ),
                )
                Text(stringResource(id = R.string.loc_lens_missed_habits_line, missedHabits))
                Text(
                    stringResource(
                        id = R.string.loc_tagged_title,
                        stringResource(id = R.string.loc_journal_notes),
                        stringResource(id = R.string.loc_lens_addressed_reflections_line, addressedReflections, reflectionsByDimension.size),
                    ),
                )
                Text(
                    stringResource(
                        id = R.string.loc_tagged_title,
                        stringResource(id = R.string.settings_database_notes),
                        stringResource(id = R.string.loc_lens_dimension_tagged_reflections_line, taggedReflections),
                    ),
                )
            }
        }
    }
}

internal fun taggedDimensionLine(
    line: String,
    dimensionLabel: String,
    dimensionColor: Color,
): AnnotatedString {
    if (dimensionLabel.isBlank()) {
        return AnnotatedString(line)
    }
    val start = line.indexOf(dimensionLabel)
    if (start < 0) {
        return AnnotatedString(line)
    }
    return buildAnnotatedString {
        append(line)
        addStyle(
            style = SpanStyle(color = dimensionColor),
            start = start,
            end = start + dimensionLabel.length,
        )
    }
}

internal fun formatSignedMinutes(minutes: Long): String {
    val base = formatMinutes(minutes.absoluteValue.toInt())
    return when {
        minutes > 0 -> "+$base"
        minutes < 0 -> "-$base"
        else -> base
    }
}

internal fun formatLensScore(score: Double): String = String.format(Locale.US, "%.5f", score.coerceIn(0.0, 1.0))

internal fun formatSignedLensScore(score: Double): String {
    val value = formatLensScore(score)
    return when {
        score > 0.0 -> "+$value"
        score < 0.0 -> "-${formatLensScore(score.absoluteValue)}"
        else -> value
    }
}

internal fun calculateBoundedTimeModuleScore(
    plannedMinutes: Int,
    actualMinutes: Int,
): Double {
    val planned = plannedMinutes.coerceIn(0, LENS_DAY_MINUTES)
    val actual = actualMinutes.coerceIn(0, LENS_DAY_MINUTES)
    if (planned == actual) {
        return 1.0
    }
    if (actual < planned) {
        if (planned == 0) {
            return 0.0
        }
        val lowerSpan = planned.toDouble()
        val deviation = (planned - actual).toDouble()
        return (1.0 - (deviation / lowerSpan)).coerceIn(0.0, 1.0)
    }
    if (planned == LENS_DAY_MINUTES) {
        return 0.0
    }
    val upperSpan = (LENS_DAY_MINUTES - planned).toDouble()
    val deviation = (actual - planned).toDouble()
    return (1.0 - (deviation / upperSpan)).coerceIn(0.0, 1.0)
}

internal fun calculateWeightedTimeModuleScore(rows: List<TimeDimensionScoreRow>): Double {
    if (rows.isEmpty()) {
        return 0.0
    }
    var weightedScore = 0.0
    var totalWeight = 0.0
    rows.forEach { row ->
        val weight = DimensionTaxonomyCatalog.defaultWeightForDimensionId(row.dimensionId)
        weightedScore += row.score * weight
        totalWeight += weight
    }
    return if (totalWeight > 0.0) (weightedScore / totalWeight).coerceIn(0.0, 1.0) else 0.0
}

internal data class TimeDimensionScoreRow(
    val dimensionId: String,
    val plannedMinutes: Int,
    val actualMinutes: Int,
    val score: Double,
    val deviationMinutes: Int,
)
