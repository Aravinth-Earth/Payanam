//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.lenses

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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.preferences.LocalAppPreferences
import io.payanam.core.ui.preferences.colorForDimension
import io.payanam.core.ui.preferences.colorForDimensionId
import io.payanam.core.ui.preferences.isVisibleDimensionId
import io.payanam.core.ui.preferences.labelForDimension
import io.payanam.core.ui.preferences.labelForDimensionId
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.core.ui.components.DimensionBadgeLabelRow
import java.util.Locale
import io.payanam.presentation.lenses.LensUiState
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiStrings
import io.payanam.shared.logging.AppLogger
import kotlin.math.absoluteValue

private val logger = AppLogger.current

@Composable
fun OverallDimensionSnapshotCard(uiState: LensUiState) {
    val strings = LocalUiStrings.current
    if (uiState.selectedRangeSummary == null) {
        logger.d("LensesScreenDimensionSections", "Overall dimension snapshot awaiting summary")
    }
    val appPrefs = LocalAppPreferences.current
    val plannedMap = uiState.selectedRangeSummary?.plannedByDimension ?: emptyMap()
    val actualMap = uiState.selectedRangeSummary?.actualByDimension ?: emptyMap()
    val ids = collectDimensionIds(uiState).filter(appPrefs::isVisibleDimensionId)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(strings.locLensGroupByDimension, fontWeight = FontWeight.SemiBold)
            if (ids.isEmpty()) {
                Text(strings.locLensNoDimensionDistribution)
            } else {
                ids.forEach { id ->
                    val label = appPrefs.labelForDimensionId(id)
                        ?: appPrefs.labelForDimension(id, DimensionTaxonomyCatalog.fromCanonicalId(id)?.fallbackLabel)
                        ?: strings.locDimensionFallbackUnassigned
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
                            text = strings.locPlanRealityTotalsLine.format(formatMinutes(planned),
                                formatMinutes(actual),),
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
fun DimensionModuleDrilldownCard(uiState: LensUiState) {
    val strings = LocalUiStrings.current
    if (uiState.selectedRangeSummary == null) {
        logger.d("LensesScreenDimensionSections", "Dimension module drilldown awaiting summary")
    }
    val appPrefs = LocalAppPreferences.current
    val summary = uiState.selectedRangeSummary
    val ids = collectDimensionIds(uiState).filter(appPrefs::isVisibleDimensionId)
    if (ids.isEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(strings.locLensNoDimensionDistribution)
            }
        }
        return
    }

    ids.forEach { id ->
        val label = appPrefs.labelForDimensionId(id)
            ?: appPrefs.labelForDimension(id, DimensionTaxonomyCatalog.fromCanonicalId(id)?.fallbackLabel)
            ?: strings.locDimensionFallbackUnassigned
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
                    strings.locTaggedTitle.format(strings.locTime,
                        strings.locPlanRealityTotalsLine.format(formatMinutes(plannedTime), formatMinutes(actualTime)),),
                )
                Text(
                    strings.locTaggedTitle.format(strings.settingsDatabaseTasks,
                        strings.locCompletedTasksRatio.format(completedTasks, plannedTasks),),
                )
                Text(strings.locLensMissedTasksLine.format(missedTasks))
                Text(
                    strings.locTaggedTitle.format(strings.locHabits,
                        strings.locCompletedHabitsRatio.format(completedHabits, plannedHabits),),
                )
                Text(strings.locLensMissedHabitsLine.format(missedHabits))
                Text(
                    strings.locTaggedTitle.format(strings.locJournalNotes,
                        strings.locLensAddressedReflectionsLine.format(addressedReflections, reflectionsByDimension.size),),
                )
                Text(
                    strings.locTaggedTitle.format(strings.settingsDatabaseNotes,
                        strings.locLensDimensionTaggedReflectionsLine.format(taggedReflections),),
                )
            }
        }
    }
}

/**
 * Tints the dimension caption inside an already-formatted line so the name carries its dimension
 * color, leaving the numbers in that line untouched.
 *
 * Only the FIRST occurrence of [dimensionLabel] in [line] is styled. A blank label, or a label
 * that does not appear in the line at all — the case when a user renames a dimension and the
 * caption was built from a different fallback — returns the line unstyled rather than throwing
 * or emitting an empty span, so callers can pass a computed label without pre-checking it.
 */
fun taggedDimensionLine(
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

/**
 * Formats a signed minute delta for the deviation chip, prefixing `+` or `-` on top of
 * [formatMinutes] — so `-90` becomes `-1h 30m`, not `-90m`.
 *
 * Zero is unsigned (`0m`). The sign comes from the Long as passed; the magnitude is formatted
 * from its absolute value, so a negative input never reaches [formatMinutes] as a negative
 * count and the compact form stays the same on both sides of the sign.
 */
fun formatSignedMinutes(minutes: Long): String {
    val base = formatMinutes(minutes.absoluteValue.toInt())
    return when {
        minutes > 0 -> "+$base"
        minutes < 0 -> "-$base"
        else -> base
    }
}

/**
 * Renders a 0..1 lens score as a fixed 5-decimal string (`0.84000`) via [Locale.US], so the
 * decimal point is always `.` regardless of device locale and card values stay column-aligned.
 *
 * The score is clamped into 0.0..1.0 first, so a value outside the scale renders as `0.00000` or
 * `1.00000` rather than a number the card cannot show. NaN is the exception: `coerceIn` leaves it
 * untouched and it formats as `NaN`, so callers holding a computed score should sanitize before
 * formatting. No rounding is applied beyond the 5 decimals shown.
 */
fun formatLensScore(score: Double): String = String.format(Locale.US, "%.5f", score.coerceIn(0.0, 1.0))

/**
 * Formats a signed lens metric (progress delta, and other signed values) with an explicit `+` or
 * `-`, keeping the same 5-decimal [Locale.US] shape as [formatLensScore].
 *
 * Zero is unsigned. Note the asymmetry inherited from [formatLensScore]: positive input is
 * clamped into 0.0..1.0, but negative input is sign-stripped first and the magnitude is then
 * clamped, so a metric below -1.0 displays as `-1.00000` and never below that. The sign is taken
 * from the value passed in, not from the clamped result, so a value of -0.5 stays visibly
 * negative as `-0.50000`.
 */
fun formatSignedLensScore(score: Double): String {
    val value = formatLensScore(score)
    return when {
        score > 0.0 -> "+$value"
        score < 0.0 -> "-${formatLensScore(score.absoluteValue)}"
        else -> value
    }
}

/**
 * Scores how closely a dimension's tracked minutes matched its planned minutes over a range,
 * on a 0.0..1.0 scale where 1.0 is an exact match and 0.0 is a total miss.
 *
 * The penalty is asymmetric, because under- and over-shooting a plan are not the same failure.
 * Both inputs are first clamped into 0..[LENS_DAY_MINUTES] (1440), so a single dimension can
 * never be booked for more than a day. Under-shoot is charged against the plan itself
 * (1 - shortfall/planned), reaching 0.0 when nothing was tracked against a non-empty plan.
 * Over-shoot is charged against the headroom left in the day (1 - excess/(1440 - planned)).
 * A plan filling the full 1440 minutes leaves zero headroom, so that branch returns 0.0
 * outright instead of dividing by it; in practice the input clamp already caps actual at 1440,
 * making an actual > planned impossible there. An exact match returns 1.0 before any arithmetic,
 * and both arithmetic branches are clamped back into 0.0..1.0. planned == 0 is handled the same
 * way for symmetry, though the input clamp makes it unreachable in practice.
 */
fun calculateBoundedTimeModuleScore(
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

/**
 * Rolls per-dimension [TimeDimensionScoreRow.score] values into one 0.0..1.0 lens score, weighting
 * each row by its taxonomy default weight via
 * [DimensionTaxonomyCatalog.defaultWeightForDimensionId] — currently 0.5 to 1.0, with 0.5 for a
 * dimension id outside the taxonomy.
 *
 * The result is a weighted MEAN (weighted sum divided by total weight), not a sum, so adding a
 * dimension cannot inflate the score past 1.0; the result is clamped into 0.0..1.0 regardless.
 * An empty [rows] list returns 0.0, and a zero total weight (only reachable if every weight
 * resolved to 0.0) returns 0.0 rather than dividing by zero — the same value an all-failing
 * row set produces, so a caller cannot distinguish "no data" from "scored zero".
 */
fun calculateWeightedTimeModuleScore(rows: List<TimeDimensionScoreRow>): Double {
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

/**
 * One dimension's row in the time module score cards: the planned-vs-tracked minutes behind a
 * dimension, the 0.0..1.0 [calculateBoundedTimeModuleScore] derived from them, and the signed gap.
 *
 * All three minute fields are whole minutes. Callers are expected to pass [plannedMinutes] and
 * [actualMinutes] already clamped into 0..[LENS_DAY_MINUTES] — the score function clamps them
 * anyway, but [deviationMinutes] is a raw subtraction and can exceed a day if they are not.
 * [score] is what [calculateWeightedTimeModuleScore] weights into the overall card, so it must
 * come from [calculateBoundedTimeModuleScore] on these same two values rather than being
 * computed independently.
 */
data class TimeDimensionScoreRow(
    val dimensionId: String,
    val plannedMinutes: Int,
    val actualMinutes: Int,
    val score: Double,
    val deviationMinutes: Int,
)
