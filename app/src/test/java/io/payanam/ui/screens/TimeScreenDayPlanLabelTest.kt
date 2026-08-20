//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.repository.DayPlanRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TimeScreenDayPlanLabelTest.
 */
class TimeScreenDayPlanLabelTest {

    private val logger: UnifiedLogger? by lazy {
        runCatching { UnifiedLogger.getInstance() }.getOrNull()
    }

    @Test
    /**
     * Resolve day plan action label custom mode returns custom hint label.
     */
    fun resolveDayPlanActionLabel_customMode_returnsCustomHintLabel() {
        /** Result. */
        val result = resolveDayPlanActionLabel(
            dayMode = DayPlanRepository.MODE_CUSTOM,
            resolvedTemplateName = "Workday Deep Focus",
            planLabel = "Plan",
            customModeLabel = "Custom Split",
            formatLabelWithHint = { label, hint -> "$label: $hint" },
        )

        logger?.d("TimeScreenDayPlanLabelTest.custom", "Result", mapOf("label" to result))
        /** Assert equals. */
        assertEquals("Plan: Custom Split", result)
    }

    @Test
    /**
     * Resolve day plan action label with resolved template returns template hint label.
     */
    fun resolveDayPlanActionLabel_withResolvedTemplate_returnsTemplateHintLabel() {
        /** Result. */
        val result = resolveDayPlanActionLabel(
            dayMode = DayPlanRepository.MODE_AUTO,
            resolvedTemplateName = "Weekend Light",
            planLabel = "Plan",
            customModeLabel = "Custom Split",
            formatLabelWithHint = { label, hint -> "$label: $hint" },
        )

        logger?.d("TimeScreenDayPlanLabelTest.template", "Result", mapOf("label" to result))
        /** Assert equals. */
        assertEquals("Plan: Weekend Light", result)
    }

    @Test
    /**
     * Resolve day plan action label without resolved template returns base label.
     */
    fun resolveDayPlanActionLabel_withoutResolvedTemplate_returnsBaseLabel() {
        /** Result. */
        val result = resolveDayPlanActionLabel(
            dayMode = DayPlanRepository.MODE_TEMPLATE,
            resolvedTemplateName = null,
            planLabel = "Plan",
            customModeLabel = "Custom Split",
            formatLabelWithHint = { label, hint -> "$label: $hint" },
        )

        logger?.d("TimeScreenDayPlanLabelTest.base", "Result", mapOf("label" to result))
        /** Assert equals. */
        assertEquals("Plan", result)
    }
}
