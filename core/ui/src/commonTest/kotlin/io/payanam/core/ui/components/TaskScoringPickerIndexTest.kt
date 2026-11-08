//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The edit screen's energy and control pickers must never resolve an unknown stored label to index
 * 0, because saving rewrites the task with whatever the picker shows and index 0 is the strongest
 * option in both rows. An unmatched label resolves to the row's neutral step instead.
 */
class TaskScoringPickerIndexTest {
    @Test
    fun `energy index maps canonical labels to their row position`() {
        assertEquals(0, resolveEnergyIndex("Low"))
        assertEquals(1, resolveEnergyIndex("Moderate"))
        assertEquals(2, resolveEnergyIndex("High"))
    }

    @Test
    fun `energy index folds legacy synonyms onto their canonical row position`() {
        assertEquals(1, resolveEnergyIndex("Medium"))
        assertEquals(1, resolveEnergyIndex("Moderate Energy"))
        assertEquals(2, resolveEnergyIndex("High Energy"))
        assertEquals(0, resolveEnergyIndex("Low Energy"))
    }

    @Test
    fun `unknown energy label resolves to the moderate step`() {
        assertEquals(1, resolveEnergyIndex(""))
        assertEquals(1, resolveEnergyIndex("Whatever"))
    }

    @Test
    fun `control index maps canonical labels to their row position`() {
        assertEquals(0, resolveControlIndex("Full Control"))
        assertEquals(1, resolveControlIndex("Mostly Controllable"))
        assertEquals(2, resolveControlIndex("Office/Colleagues Dependent"))
        assertEquals(3, resolveControlIndex("External Dependent"))
    }

    @Test
    fun `control index folds legacy and out of row labels onto the row`() {
        assertEquals(0, resolveControlIndex("Self"))
        assertEquals(0, resolveControlIndex("Self Controlled"))
        assertEquals(0, resolveControlIndex("Fully Independent"))
        assertEquals(3, resolveControlIndex("Fully Dependent"))
        assertEquals(3, resolveControlIndex("No Control"))
    }

    @Test
    fun `unknown control label resolves to the repository default step`() {
        assertEquals(2, resolveControlIndex(""))
        assertEquals(2, resolveControlIndex("Whatever"))
    }
}
