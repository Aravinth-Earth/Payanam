//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DayPlanTemplateBudgetTest.
 */
class DayPlanTemplateBudgetTest {

    @Test
    /**
     * Compute template plan budget when under24hours reports remaining.
     */
    fun computeTemplatePlanBudget_whenUnder24Hours_reportsRemaining() {
        val budget = computeTemplatePlanBudget(totalMinutes = 600)
        assertEquals(600, budget.totalMinutes)
        assertEquals(840, budget.remainingMinutes)
        assertEquals(0, budget.excessMinutes)
        assertFalse(budget.isExcess)
    }

    @Test
    /**
     * Compute template plan budget when over24hours reports excess.
     */
    fun computeTemplatePlanBudget_whenOver24Hours_reportsExcess() {
        val budget = computeTemplatePlanBudget(totalMinutes = 1500)
        assertEquals(1500, budget.totalMinutes)
        assertEquals(0, budget.remainingMinutes)
        assertEquals(60, budget.excessMinutes)
        assertTrue(budget.isExcess)
    }
}
