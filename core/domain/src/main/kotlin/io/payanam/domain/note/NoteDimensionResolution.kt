//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.note

import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.shared.logging.AppLogger

/**
 * Turns a note's requested dimension into the canonical `(id, label)` pair that gets persisted.
 *
 * This lived as two private methods inside the Android Room implementation. It is here because the
 * desktop's JDBC implementation needs exactly the same rules, and a second hand-written copy would be a
 * second code path for the same stored data: the Android app and the Linux app could then persist
 * different `dimension_id` values for the same user action, and the divergence would only show up as
 * notes appearing under the wrong dimension on one platform.
 *
 * The rules, unchanged from the Room implementation:
 *  - an explicit dimension id is normalised through [DimensionTaxonomyCatalog] so a legacy or alias id
 *    lands on its canonical form; an unknown id is kept as-is rather than dropped, so no data is lost;
 *  - a bare category label is NOT used to guess an id. It used to be, and that silently moved notes onto
 *    a canonical dimension the user never picked, which is why it now only warns.
 */
object NoteDimensionResolution {
    private const val SOURCE_RESOLVE_ID = "NoteDimensionResolution.resolveDimensionId"

    /**
     * The canonical dimension id for a note being saved, or null when neither input can produce one.
     *
     * @param explicitDimensionId a dimension id the caller already resolved (wins when non-blank).
     * @param categoryLabel a free-text life-intention label; only used to warn, never to infer an id.
     */
    fun resolveDimensionId(
        explicitDimensionId: String?,
        categoryLabel: String?,
    ): String? =
        explicitDimensionId?.trim()?.takeIf { it.isNotEmpty() }?.let { requestedId ->
            DimensionTaxonomyCatalog.fromCanonicalId(requestedId)?.id ?: requestedId
        } ?: categoryLabel?.trim()?.takeIf { it.isNotEmpty() }?.let { label ->
            AppLogger.current.w(
                SOURCE_RESOLVE_ID,
                "Ignoring non-canonical note category label during dimension resolution",
                mapOf("categoryLabel" to label),
            )
            null
        }

    /**
     * The label to persist alongside [resolvedDimensionId]: the caller's explicit label when it has one,
     * otherwise the canonical dimension's fallback label, otherwise the catch-all "Work & Livelihood".
     */
    fun resolveDimensionLabel(
        explicitLabel: String?,
        resolvedDimensionId: String?,
    ): String =
        explicitLabel?.trim()?.takeIf { it.isNotEmpty() }
            ?: DimensionTaxonomyCatalog.fromCanonicalId(resolvedDimensionId)?.fallbackLabel
            ?: DimensionTaxonomyCatalog.WORK_LIVELIHOOD.fallbackLabel
}
