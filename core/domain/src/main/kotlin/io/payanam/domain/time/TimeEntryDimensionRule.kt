//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.time

import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.shared.logging.AppLogger

/**
 * Turns a time entry's requested dimension into the canonical id that gets persisted.
 *
 * This lived as an `internal` function at the bottom of the Android Room implementation. It is here because
 * the desktop's JDBC implementation needs the same rule, and a second hand-written copy would be a second
 * code path for the same stored column: the two apps could persist different `dimension_id` values for the
 * same user action, and the divergence would only surface as tracked time appearing under the wrong
 * dimension on one platform.
 *
 * **This is deliberately NOT the note/task rule.** They look alike and are not:
 *  - notes and tasks keep a non-canonical id *as-is* (`?: requestedId`) so nothing is lost;
 *  - a time entry *drops* a non-canonical id and falls back to the id already stored on the row.
 *
 * A time entry additionally falls back to the row's existing dimension rather than to null, which is what
 * makes an edit that only changes the category label preserve the dimension the entry was tracked under.
 * Unifying the two rules would silently move tracked time onto a different dimension.
 */
object TimeEntryDimensionRule {
    private const val SOURCE_RESOLVE_ID = "TimeEntryDimensionRule.resolveDimensionId"

    /**
     * The canonical dimension id for a time entry being written.
     *
     * @param dimensionId the caller's requested id; canonicalised when known.
     * @param lifeIntentionCategory a free-text label. Only used to warn, never to infer an id — inferring
     *   one used to move entries onto a dimension the user never picked.
     * @param fallbackDimensionId the id already on the row, returned when neither input yields a canonical
     *   one.
     */
    fun resolveDimensionId(
        dimensionId: String?,
        lifeIntentionCategory: String,
        fallbackDimensionId: String? = null,
    ): String? {
        val normalizedDimensionId = normalizeIdentifier(dimensionId)
        val canonicalDimensionId = normalizedDimensionId?.let { DimensionTaxonomyCatalog.fromCanonicalId(it)?.id }
        if (canonicalDimensionId == null && normalizedDimensionId != null) {
            AppLogger.current.w(
                SOURCE_RESOLVE_ID,
                "Ignoring non-canonical dimension id",
                mapOf("dimensionId" to normalizedDimensionId),
            )
        }
        if (canonicalDimensionId == null && lifeIntentionCategory.isNotBlank()) {
            AppLogger.current.w(
                SOURCE_RESOLVE_ID,
                "Ignoring non-canonical time-entry category label during dimension resolution",
                mapOf("lifeIntentionCategory" to lifeIntentionCategory),
            )
        }
        return canonicalDimensionId ?: fallbackDimensionId?.let { DimensionTaxonomyCatalog.fromCanonicalId(it)?.id }
    }

    /** Trims [value] and treats a blank string as absent, so `""` and `"  "` never become an identifier. */
    fun normalizeIdentifier(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}
