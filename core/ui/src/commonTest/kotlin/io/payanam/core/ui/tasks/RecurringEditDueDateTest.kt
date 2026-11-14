//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.tasks

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * An edit to a recurring task keeps the stored due DATE and adopts only the new TIME.
 */
class RecurringEditDueDateTest {
    /** A past Saturday: a weekday-only rule does not match it, and a next-occurrence rule would advance it. */
    private val storedDay = LocalDate.of(2026, 10, 31)

    /** What the rule would offer instead: the next matching day, which is not the stored one. */
    private val nextOccurrence = LocalDateTime.of(2026, 11, 9, 18, 30)

    /**
     * The regression this guards: an existing recurring task with a stored due date, saved with a changed
     * time, keeps the original day and takes the new time of day.
     */
    @Test
    fun `a stored date keeps its day and takes the new time`() {
        val saved =
            resolveRecurringEditDueDateTime(
                storedDueDate = LocalDateTime.of(storedDay, LocalTime.of(9, 0)),
                selectedTime = LocalTime.of(18, 30),
                firstOccurrence = nextOccurrence,
            )

        assertEquals(LocalDateTime.of(2026, 10, 31, 18, 30), saved)
    }

    /** A task with no stored date is the one case the first occurrence answers, as creation does. */
    @Test
    fun `no stored date saves the first occurrence`() {
        val saved =
            resolveRecurringEditDueDateTime(
                storedDueDate = null,
                selectedTime = LocalTime.of(18, 30),
                firstOccurrence = nextOccurrence,
            )

        assertEquals(nextOccurrence, saved)
    }

    /** With no time chosen there is nothing to change, so the stored value is saved back unchanged. */
    @Test
    fun `a stored date with no selected time is left unchanged`() {
        val stored = LocalDateTime.of(storedDay, LocalTime.of(9, 0))

        val saved =
            resolveRecurringEditDueDateTime(
                storedDueDate = stored,
                selectedTime = null,
                firstOccurrence = null,
            )

        assertEquals(stored, saved)
    }

    /** No stored date and no chosen time leaves the date for the repository to keep. */
    @Test
    fun `no stored date and no selected time saves nothing`() {
        assertNull(
            resolveRecurringEditDueDateTime(
                storedDueDate = null,
                selectedTime = null,
                firstOccurrence = null,
            ),
        )
    }
}
