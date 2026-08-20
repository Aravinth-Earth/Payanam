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
        /** Budget. */
        val budget = computeTemplatePlanBudget(totalMinutes = 600)

        /** Assert equals. */
        assertEquals(600, budget.totalMinutes)
        /** Assert equals. */
        assertEquals(840, budget.remainingMinutes)
        /** Assert equals. */
        assertEquals(0, budget.excessMinutes)
        /** Assert false. */
        assertFalse(budget.isExcess)
    }

    @Test
    /**
     * Compute template plan budget when over24hours reports excess.
     */
    fun computeTemplatePlanBudget_whenOver24Hours_reportsExcess() {
        /** Budget. */
        val budget = computeTemplatePlanBudget(totalMinutes = 1500)

        /** Assert equals. */
        assertEquals(1500, budget.totalMinutes)
        /** Assert equals. */
        assertEquals(0, budget.remainingMinutes)
        /** Assert equals. */
        assertEquals(60, budget.excessMinutes)
        /** Assert true. */
        assertTrue(budget.isExcess)
    }
}
