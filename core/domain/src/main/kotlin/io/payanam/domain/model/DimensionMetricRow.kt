//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

/**
 * L2 score roll-up row for one dimension on one day — the score-matrix row
 * and the dimension detail-page window row.
 */
data class DimensionMetricRow(
    /** Dimension id. */
    val dimensionId: String,
    override val dayKey: String,
    override val score: Double,
    override val runningAvg: Double,
    override val progress: Double,
    override val streakPos: Int,
    override val streakNet: Int,
    override val posContinue: Int,
) : MetricWindowRow {
    override val key: String get() = dimensionId
    override val label: String get() = dimensionId
}
