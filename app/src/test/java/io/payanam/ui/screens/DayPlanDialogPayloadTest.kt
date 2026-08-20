//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.ui.graphics.Color
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.LifeDimension
import io.payanam.domain.repository.DayPlanRepository
import io.payanam.ui.viewmodel.DimensionPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DayPlanDialogPayloadTest.
 */
class DayPlanDialogPayloadTest {

    private val logger: UnifiedLogger? by lazy {
        runCatching { UnifiedLogger.getInstance() }.getOrNull()
    }

    private fun dimensionOptions(): List<DimensionPreference> = listOf(
        /** Dimension preference. */
        DimensionPreference(
            key = LifeDimension.CAREER_WORK.id,
            id = "dim_work_livelihood",
            label = "Career",
            color = Color.Red,
            isVisible = true,
            canonicalId = "dim_work_livelihood",
        ),
        /** Dimension preference. */
        DimensionPreference(
            key = LifeDimension.LEARNING.id,
            id = "dim_learning_growth",
            label = "Learning",
            color = Color.Blue,
            isVisible = true,
            canonicalId = "dim_learning_growth",
        ),
    )

    @Test
    /**
     * Build day plan dialog save payload filters invalid minutes and keeps positive values.
     */
    fun buildDayPlanDialogSavePayload_filters_invalid_minutes_and_keeps_positive_values() {
        /** Payload. */
        val payload = buildDayPlanDialogSavePayload(
            dayMode = DayPlanRepository.MODE_CUSTOM,
            selectedTemplateId = null,
            starredDay = true,
            weekdayTemplateId = "tpl-weekday",
            weekendTemplateId = "tpl-weekend",
            starredTemplateId = "tpl-starred",
            dimensionOptions = dimensionOptions(),
            allocationInputs = mapOf(
                "dim_work_livelihood" to "90",
                "dim_learning_growth" to "0",
                "unknown" to "75",
            ),
        )

        logger?.d("DayPlanDialogPayloadTest.filtering", "Payload built", mapOf("count" to payload.allocations.size.toString()))
        /** Assert equals. */
        assertEquals(DayPlanRepository.MODE_CUSTOM, payload.mode)
        /** Assert equals. */
        assertEquals(1, payload.allocations.size)
        /** Assert equals. */
        assertEquals(90, payload.allocations["dim_work_livelihood"])
        /** Assert equals. */
        assertEquals(true, payload.isStarredDay)
    }

    @Test
    /**
     * Build day plan dialog save payload maps day type template ids and template selection.
     */
    fun buildDayPlanDialogSavePayload_maps_day_type_template_ids_and_template_selection() {
        /** Payload. */
        val payload = buildDayPlanDialogSavePayload(
            dayMode = DayPlanRepository.MODE_TEMPLATE,
            selectedTemplateId = "tpl-main",
            starredDay = false,
            weekdayTemplateId = "tpl-weekday",
            weekendTemplateId = null,
            starredTemplateId = "tpl-starred",
            dimensionOptions = dimensionOptions(),
            allocationInputs = emptyMap(),
        )

        logger?.d("DayPlanDialogPayloadTest.mappings", "Payload built", mapOf("mode" to payload.mode))
        /** Assert equals. */
        assertEquals("tpl-main", payload.templateId)
        /** Assert equals. */
        assertEquals("tpl-weekday", payload.dayTypeTemplateByType[DayPlanRepository.DAY_TYPE_WEEKDAY])
        /** Assert null. */
        assertNull(payload.dayTypeTemplateByType[DayPlanRepository.DAY_TYPE_WEEKEND])
        /** Assert equals. */
        assertEquals("tpl-starred", payload.dayTypeTemplateByType[DayPlanRepository.DAY_TYPE_STARRED])
    }
}
