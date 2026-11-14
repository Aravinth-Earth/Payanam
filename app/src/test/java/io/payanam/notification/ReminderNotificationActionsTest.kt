//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.notification

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the count-to-slots table behind [reminderActionSlots]: the platform renders at most three
 * action buttons, so no notification may carry a fourth choice the user cannot reach.
 */
class ReminderNotificationActionsTest {
    @Test
    fun `three visible actions yield complete miss and more`() {
        assertEquals(
            listOf(ReminderActionSlot.COMPLETE, ReminderActionSlot.MISS, ReminderActionSlot.MORE),
            reminderActionSlotsForCount(3),
        )
    }

    @Test
    fun `two visible actions yield complete and more`() {
        assertEquals(
            listOf(ReminderActionSlot.COMPLETE, ReminderActionSlot.MORE),
            reminderActionSlotsForCount(2),
        )
    }

    @Test
    fun `one visible action yields complete only`() {
        assertEquals(
            listOf(ReminderActionSlot.COMPLETE),
            reminderActionSlotsForCount(1),
        )
    }

    @Test
    fun `more than three visible actions still yield three slots`() {
        assertEquals(
            listOf(ReminderActionSlot.COMPLETE, ReminderActionSlot.MISS, ReminderActionSlot.MORE),
            reminderActionSlotsForCount(5),
        )
    }

    @Test
    fun `supported sdk levels assume three visible actions`() {
        assertEquals(
            listOf(ReminderActionSlot.COMPLETE, ReminderActionSlot.MISS, ReminderActionSlot.MORE),
            reminderActionSlots(sdkInt = 28),
        )
    }
}
