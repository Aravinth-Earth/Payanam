//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.time

import io.payanam.domain.repository.DayPlanRepository
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.UnifiedLogger

private fun safeDayPlanLabelLogger(): UnifiedLogger? = runCatching { AppLogger.current }.getOrNull()

/**
 * Builds the day-plan button label, adding the mode or template it acts on as a hint.
 *
 * The hint is chosen by precedence, and a blank hint is treated as no hint:
 * [DayPlanRepository.MODE_CUSTOM] shows [customModeLabel] regardless of the template, otherwise a
 * non-blank [resolvedTemplateName] is used, otherwise there is no hint and [planLabel] is returned
 * untouched - [formatLabelWithHint] is not called in that case, so the label is never decorated with
 * an empty hint. The hint therefore identifies what the tap will operate on: the custom plan, or the
 * named template, or nothing when the day has no plan to act on yet.
 */
fun resolveDayPlanActionLabel(
    dayMode: String,
    resolvedTemplateName: String?,
    planLabel: String,
    customModeLabel: String,
    formatLabelWithHint: (String, String) -> String,
): String {
    val hint =
        when {
            dayMode == DayPlanRepository.MODE_CUSTOM -> customModeLabel
            !resolvedTemplateName.isNullOrBlank() -> resolvedTemplateName
            else -> null
        } ?: return planLabel
    val label = formatLabelWithHint(planLabel, hint)
    safeDayPlanLabelLogger()?.d(
        "TimeScreenDayPlanLabel.resolveDayPlanActionLabel",
        "Resolved day plan action label",
        mapOf("dayMode" to dayMode, "hasHint" to hint.isNotBlank().toString()),
    )
    return label
}
