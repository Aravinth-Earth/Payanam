//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.repository.DayPlanRepository

private fun safeDayPlanLabelLogger(): UnifiedLogger? = runCatching { UnifiedLogger.getInstance() }.getOrNull()

internal fun resolveDayPlanActionLabel(
    /** Day mode. */
    dayMode: String,
    resolvedTemplateName: String?,
    /** Plan label. */
    planLabel: String,
    /** Custom mode label. */
    customModeLabel: String,
    formatLabelWithHint: (String, String) -> String,
): String {
    /** Hint. */
    val hint = when {
        dayMode == DayPlanRepository.MODE_CUSTOM -> customModeLabel
        !resolvedTemplateName.isNullOrBlank() -> resolvedTemplateName
        else -> null
    } ?: return planLabel
    /** Label. */
    val label = formatLabelWithHint(planLabel, hint)
    /** Safe day plan label logger. */
    safeDayPlanLabelLogger()?.d(
        "TimeScreenDayPlanLabel.resolveDayPlanActionLabel",
        "Resolved day plan action label",
        /** Map of. */
        mapOf("dayMode" to dayMode, "hasHint" to hint.isNotBlank().toString()),
    )
    return label
}
