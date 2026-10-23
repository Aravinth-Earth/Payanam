//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.day

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The day-plan template budget arithmetic, tested on the JVM.
 *
 * Moved out of `:app` with the function it covers, when `DayPlanTemplateScreen` joined the shared UI layer.
 */
class DayPlanTemplateBudgetTest {
    /**
     * Compute template plan budget when under24hours reports remaining.
     */
    @Test
    fun computeTemplatePlanBudget_whenUnder24Hours_reportsRemaining() {
        val budget = computeTemplatePlanBudget(totalMinutes = 600)
        assertEquals(600, budget.totalMinutes)
        assertEquals(840, budget.remainingMinutes)
        assertEquals(0, budget.excessMinutes)
        assertFalse(budget.isExcess)
    }

    /**
     * Compute template plan budget when over24hours reports excess.
     */
    @Test
    fun computeTemplatePlanBudget_whenOver24Hours_reportsExcess() {
        val budget = computeTemplatePlanBudget(totalMinutes = 1500)
        assertEquals(1500, budget.totalMinutes)
        assertEquals(0, budget.remainingMinutes)
        assertEquals(60, budget.excessMinutes)
        assertTrue(budget.isExcess)
    }
}
