//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.repository.DayPlanRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeScreenDayPlanLabelTest {

    private val logger: UnifiedLogger? by lazy {
        runCatching { UnifiedLogger.getInstance() }.getOrNull()
    }

    @Test
    fun resolveDayPlanActionLabel_customMode_returnsCustomHintLabel() {
        val result = resolveDayPlanActionLabel(
            dayMode = DayPlanRepository.MODE_CUSTOM,
            resolvedTemplateName = "Workday Deep Focus",
            planLabel = "Plan",
            customModeLabel = "Custom Split",
            formatLabelWithHint = { label, hint -> "$label: $hint" },
        )

        logger?.d("TimeScreenDayPlanLabelTest.custom", "Result", mapOf("label" to result))
        assertEquals("Plan: Custom Split", result)
    }

    @Test
    fun resolveDayPlanActionLabel_withResolvedTemplate_returnsTemplateHintLabel() {
        val result = resolveDayPlanActionLabel(
            dayMode = DayPlanRepository.MODE_AUTO,
            resolvedTemplateName = "Weekend Light",
            planLabel = "Plan",
            customModeLabel = "Custom Split",
            formatLabelWithHint = { label, hint -> "$label: $hint" },
        )

        logger?.d("TimeScreenDayPlanLabelTest.template", "Result", mapOf("label" to result))
        assertEquals("Plan: Weekend Light", result)
    }

    @Test
    fun resolveDayPlanActionLabel_withoutResolvedTemplate_returnsBaseLabel() {
        val result = resolveDayPlanActionLabel(
            dayMode = DayPlanRepository.MODE_TEMPLATE,
            resolvedTemplateName = null,
            planLabel = "Plan",
            customModeLabel = "Custom Split",
            formatLabelWithHint = { label, hint -> "$label: $hint" },
        )

        logger?.d("TimeScreenDayPlanLabelTest.base", "Result", mapOf("label" to result))
        assertEquals("Plan", result)
    }
}
