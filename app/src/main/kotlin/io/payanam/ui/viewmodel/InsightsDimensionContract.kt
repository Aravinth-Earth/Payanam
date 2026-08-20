//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.Note
import io.payanam.domain.model.Task
import io.payanam.domain.model.TimeEntry

internal object InsightsDimensionContract {
    private val logger: UnifiedLogger? by lazy { runCatching { UnifiedLogger.getInstance() }.getOrNull() }

    /**
     * Task dimension id.
     */
    fun taskDimensionId(task: Task): String = task.dimensionId
        ?.let { DimensionTaxonomyCatalog.fromCanonicalId(it)?.id }
        ?: DimensionTaxonomyCatalog.LEARNING_GROWTH.id

    /**
     * Note dimension id.
     */
    fun noteDimensionId(note: Note): String = note.dimensionId
        ?.let { DimensionTaxonomyCatalog.fromCanonicalId(it)?.id }
        ?: DimensionTaxonomyCatalog.LEARNING_GROWTH.id

    /**
     * Phase 9A time-visual dimension contract.
     *
     * Canonical fields:
     * - time_entries.startedAt / endedAt for day-bounded timeline intervals
     * - time_entries.day_key for date-scoped repository query
     * - time_entries.focusRating for weighted focus rollups
     * - time_entries.dimension_id as first-class source of truth
     *
     * Dimension resolution precedence:
     * 1) explicit time_entries.dimension_id
     * 2) linked task.dimension_id
     * 3) canonical default if no explicit dimension is present
     */
    fun timeEntryDimensionId(
        /** Entry. */
        entry: TimeEntry,
        taskById: Map<String, Task>,
    ): String {
        /** Explicit dimension. */
        val explicitDimension = entry.dimensionId
        /** If. */
        if (!explicitDimension.isNullOrBlank()) {
            return explicitDimension
        }
        /** Task dimension. */
        val taskDimension = entry.taskId?.let { taskId ->
            taskById[taskId]?.let(::taskDimensionId)
        }
        /** If. */
        if (!taskDimension.isNullOrBlank()) {
            return taskDimension
        }
        logger?.w(
            "InsightsDimensionContract.timeEntryDimensionId",
            "Missing canonical time-entry dimension; using default",
            /** Map of. */
            mapOf(
                "entryId" to entry.id,
                "lifeIntentionCategory" to (entry.lifeIntentionCategory ?: "none"),
            ),
        )
        return DimensionTaxonomyCatalog.LEARNING_GROWTH.id
    }

    /**
     * Dimension label.
     */
    fun dimensionLabel(dimensionId: String): String = DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.fallbackLabel ?: dimensionId
}
