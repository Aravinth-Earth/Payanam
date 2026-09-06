//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.repository

import io.payanam.domain.model.MetricWindowRow

/**
 * Read access to L2/L3 score windows for the Lenses score matrix and the
 * day/dimension detail pages.
 *
 * Dimension rows and the DAY row share the same 6 self-gov metrics, so they
 * surface through the shared [MetricWindowRow] contract — one detail layout
 * renders any of them.
 */
interface ScoreWindowRepository {

    /** Rows per dimension for every day in [start]..[end] (inclusive). */
    suspend fun getDimensionWindow(start: String, end: String): List<MetricWindowRow>

    /** DAY rows (one per day) for [start]..[end] (inclusive). */
    suspend fun getDayWindow(start: String, end: String): List<MetricWindowRow>

    /** Earliest logged day across ALL habits (DAY layer, "All" range start). */
    suspend fun earliestDayKey(): String?

    /** Earliest logged day for one dimension's mapped habits (dimension layer). */
    suspend fun earliestDimensionDayKey(dimensionId: String): String?

    /** Earliest logged day across ALL dimensions (global dimension layer start). */
    suspend fun earliestDimensionDayKey(): String?
}
