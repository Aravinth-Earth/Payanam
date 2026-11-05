//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.repository

/**
 * Tracks which days have stale lens snapshots and which modules changed, so the next lens refresh only
 * recomputes what is needed.
 *
 * This lives in the shared model module because it is a **persisted format**: the values are written into
 * `daily_insights.summary_json` and read back by whichever platform wrote them. Android and the desktop
 * both mutate the same file, so a second copy of the encoding would let one platform write a payload the
 * other cannot read — the class of divergence this migration exists to remove.
 */
data class LensDirtyDayMetadata(
    val dayKey: String,
    val changedModules: Set<String>,
    val invalidatedAt: String,
    val reason: String,
)

/** The `daily_insights.module` value under which a day's unified lens snapshot is cached. */
const val DAILY_INSIGHT_MODULE_UNIFIED_SNAPSHOT = "lens_unified_snapshot"

/** The `daily_insights.module` value under which a day's dirty marker is stored. */
const val DAILY_INSIGHT_MODULE_LENS_DIRTY_DAY = "lens_dirty_day"

/**
 * The primary key a day's dirty marker is stored under.
 *
 * Derived rather than passed so the marker for a day can always be found by its day, on either platform.
 */
fun lensDirtyDayId(dayKey: String): String = "lens_dirty_$dayKey"

/**
 * Merges [existingChangedModules] (what the stored marker already lists) with [changedModules] (what just
 * changed) and normalises both.
 *
 * Merging rather than replacing is the point of the marker: two mutations on the same day must leave both
 * modules recorded, or the second write silently discards the first one's invalidation and the lens
 * snapshot keeps using a stale module. Blank module names are dropped and the result is sorted, so the
 * encoded payload is stable and a re-write of the same state produces the same string.
 */
fun buildLensDirtyDayMetadata(
    dayKey: String,
    existingChangedModules: Collection<String>,
    changedModules: Collection<String>,
    reason: String,
    invalidatedAt: String,
): LensDirtyDayMetadata {
    val normalizedExisting = existingChangedModules.map { it.trim() }.filter { it.isNotEmpty() }
    val normalizedIncoming = changedModules.map { it.trim() }.filter { it.isNotEmpty() }
    return LensDirtyDayMetadata(
        dayKey = dayKey,
        changedModules = (normalizedExisting + normalizedIncoming).toSortedSet(),
        invalidatedAt = invalidatedAt,
        reason = reason.trim().ifEmpty { "unspecified" },
    )
}

/**
 * Encodes [metadata] into the `summary_json` payload.
 *
 * The field separator `|` and the `key=value` shape are part of the on-disk format, so [reason] has any
 * `|` and newline replaced before it is written — an unescaped separator in the free-text field would
 * shift every following field and corrupt the parse.
 */
fun encodeLensDirtyDayMetadata(metadata: LensDirtyDayMetadata): String {
    val modules = metadata.changedModules.joinToString(",")
    val safeReason =
        metadata.reason
            .replace("|", "/")
            .replace("\n", " ")
            .trim()
    return "dayKey=${metadata.dayKey}|changedModules=$modules|" +
        "invalidatedAt=${metadata.invalidatedAt}|reason=$safeReason"
}

/**
 * Decodes a `summary_json` payload written by [encodeLensDirtyDayMetadata].
 *
 * [dayKey] is the fallback for the payload's own `dayKey` field: a stored marker is always read by its day,
 * so a payload that lost the field still resolves to the right day instead of an empty one.
 */
fun decodeLensDirtyDayMetadata(
    dayKey: String,
    encoded: String,
): LensDirtyDayMetadata {
    val fields =
        encoded
            .split("|")
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index) to part.substring(index + 1)
            }.toMap()
    val modules =
        fields["changedModules"]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
    return LensDirtyDayMetadata(
        dayKey = fields["dayKey"] ?: dayKey,
        changedModules = modules,
        invalidatedAt = fields["invalidatedAt"] ?: "",
        reason = fields["reason"] ?: "unspecified",
    )
}
