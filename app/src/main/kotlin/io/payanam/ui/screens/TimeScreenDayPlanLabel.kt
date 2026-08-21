//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.repository.DayPlanRepository

private fun safeDayPlanLabelLogger(): UnifiedLogger? = runCatching { UnifiedLogger.getInstance() }.getOrNull()

internal fun resolveDayPlanActionLabel(
    dayMode: String,
    resolvedTemplateName: String?,
    planLabel: String,
    customModeLabel: String,
    formatLabelWithHint: (String, String) -> String,
): String {
    val hint = when {
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
