//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.service

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime

class ClockChangeTest {
    @Test
    fun `only the clock and zone broadcasts rebase the notification`() {
        assertTrue(ClockChange.rebases(Intent.ACTION_TIMEZONE_CHANGED))
        assertTrue(ClockChange.rebases(Intent.ACTION_TIME_CHANGED))
        assertFalse(ClockChange.rebases(Intent.ACTION_BOOT_COMPLETED))
        assertFalse(ClockChange.rebases(null))
    }

    @Test
    fun `the base follows the stored elapsed after a zone jump`() {
        // A start of 10:00 and a wall clock of 10:30: thirty minutes on both sides of the base.
        val start = LocalDateTime.of(2026, 3, 1, 10, 0)
        val epochAtStart = 1_000_000L
        val before = ClockChange.chronometerBase(start, LocalDateTime.of(2026, 3, 1, 10, 30), epochAtStart)

        assertEquals(epochAtStart - Duration.ofMinutes(30).toMillis(), before)

        // The zone moves five hours on, so the wall clock reads 15:30 while the epoch clock moved half an hour.
        val after =
            ClockChange.chronometerBase(start, LocalDateTime.of(2026, 3, 1, 15, 30), epochAtStart + Duration.ofMinutes(30).toMillis())

        assertEquals(epochAtStart + Duration.ofMinutes(30).toMillis() - Duration.ofMinutes(330).toMillis(), after)
        assertNotEquals(before, after)
    }
}
