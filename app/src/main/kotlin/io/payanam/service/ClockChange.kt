//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.service

import android.content.Intent
import java.time.Duration
import java.time.LocalDateTime

/** The broadcasts after which a wall-clock duration computed from stored timestamps has moved. */
internal object ClockChange {
    fun rebases(action: String?): Boolean = action == Intent.ACTION_TIMEZONE_CHANGED || action == Intent.ACTION_TIME_CHANGED

    /** The chronometer base: the epoch clock minus the elapsed the stored timestamps give. */
    fun chronometerBase(startTime: LocalDateTime, now: LocalDateTime, nowEpochMillis: Long): Long = nowEpochMillis - Duration.between(startTime, now).toMillis().coerceAtLeast(0L)
}
