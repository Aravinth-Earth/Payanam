//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

/**
 * L3 day score roll-up row — the DAY row in the score matrix and the day
 * detail-page window row.
 */
data class DayMetricRow(
    override val dayKey: String,
    override val score: Double,
    override val runningAvg: Double,
    override val progress: Double,
    override val streakPos: Int,
    override val streakNet: Int,
    override val posContinue: Int,
) : MetricWindowRow {
    override val key: String get() = "DAY"
    override val label: String get() = "DAY"
}
