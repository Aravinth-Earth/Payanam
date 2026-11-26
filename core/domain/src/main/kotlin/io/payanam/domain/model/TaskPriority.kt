//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

package io.payanam.domain.model

/**
 * Priority bands over a task score on the 0.0-1.0 scale the scoring module writes.
 */
object TaskPriority {
    const val HIGH_SCORE = 0.8
    const val MEDIUM_SCORE = 0.5

    /** The band a score on the 0.0-1.0 scale falls in. */
    fun of(score: Double): String =
        when {
            score >= HIGH_SCORE -> "High"
            score >= MEDIUM_SCORE -> "Medium"
            else -> "Low"
        }
}
