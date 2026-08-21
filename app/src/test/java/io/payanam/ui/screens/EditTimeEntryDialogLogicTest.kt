//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
/**
 * EditTimeEntryDialogLogicTest.
 */
class EditTimeEntryDialogLogicTest {
    private val logger: UnifiedLogger by lazy {
        val context = ApplicationProvider.getApplicationContext<Application>()
        UnifiedLogger.initialize(context, "test", 0)
    }

    @Test
    fun `end date picker opens when end time is null`() {
        val shouldOpen = shouldOpenEditDialogEndDatePicker(null)
        logger.i(
            "EditTimeEntryDialogLogicTest",
            "Validated end-date picker behavior for active entry",
            mapOf("endTimeNull" to "true", "shouldOpen" to shouldOpen.toString()),
        )
        assertTrue(shouldOpen)
    }

    @Test
    fun `end date picker opens when end time exists`() {
        val shouldOpen = shouldOpenEditDialogEndDatePicker(LocalTime.of(12, 30))
        assertTrue(shouldOpen)
    }

    @Test
    fun `save blocked when only end date is set`() {
        val canSave = canSaveEditedTimeEntry(
            startDate = LocalDate.of(2026, 2, 14),
            startTime = LocalTime.of(10, 0),
            endDate = LocalDate.of(2026, 2, 14),
            endTime = null,
        )
        assertFalse(canSave)
    }

    @Test
    fun `save blocked when only end time is set`() {
        val canSave = canSaveEditedTimeEntry(
            startDate = LocalDate.of(2026, 2, 14),
            startTime = LocalTime.of(10, 0),
            endDate = null,
            endTime = LocalTime.of(11, 0),
        )
        assertFalse(canSave)
    }

    @Test
    fun `save allowed when end is unset for active edit`() {
        val canSave = canSaveEditedTimeEntry(
            startDate = LocalDate.of(2026, 2, 14),
            startTime = LocalTime.of(10, 0),
            endDate = null,
            endTime = null,
        )
        assertTrue(canSave)
    }

    @Test
    fun `save allowed when complete end is after start`() {
        val canSave = canSaveEditedTimeEntry(
            startDate = LocalDate.of(2026, 2, 14),
            startTime = LocalTime.of(10, 0),
            endDate = LocalDate.of(2026, 2, 14),
            endTime = LocalTime.of(11, 0),
        )
        assertTrue(canSave)
    }

    @Test
    fun `save blocked when complete end is not after start`() {
        val canSave = canSaveEditedTimeEntry(
            startDate = LocalDate.of(2026, 2, 14),
            startTime = LocalTime.of(10, 0),
            endDate = LocalDate.of(2026, 2, 14),
            endTime = LocalTime.of(9, 59),
        )
        assertFalse(canSave)
    }
}
