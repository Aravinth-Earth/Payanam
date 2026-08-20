//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.repository

import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.dao.DailyInsightDao
import io.payanam.database.entity.DailyInsightEntity
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Daily insight module unified snapshot. */
const val DAILY_INSIGHT_MODULE_UNIFIED_SNAPSHOT = "lens_unified_snapshot"
/** Daily insight module lens dirty day. */
const val DAILY_INSIGHT_MODULE_LENS_DIRTY_DAY = "lens_dirty_day"

/**
 * LensDirtyDayMetadata.
 */
data class LensDirtyDayMetadata(
    /** Day key. */
    val dayKey: String,
    /** Changed modules. */
    val changedModules: Set<String>,
    /** Invalidated at. */
    val invalidatedAt: String,
    /** Reason. */
    val reason: String,
)

/**
 * Mark lens day dirty.
 */
suspend fun markLensDayDirty(
    /** Daily insight dao. */
    dailyInsightDao: DailyInsightDao,
    /** Logger. */
    logger: UnifiedLogger,
    /** Day key. */
    dayKey: String,
    changedModules: Set<String>,
    /** Reason. */
    reason: String,
) {
    /** Normalized modules. */
    val normalizedModules = changedModules.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    /** Existing. */
    val existing = loadLensDirtyDayMetadata(dailyInsightDao, dayKey)
    /** Merged modules. */
    val mergedModules = (existing?.changedModules.orEmpty() + normalizedModules).toSortedSet()
    /** Now. */
    val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    /** Metadata. */
    val metadata =
        /** Lens dirty day metadata. */
        LensDirtyDayMetadata(
            dayKey = dayKey,
            changedModules = mergedModules,
            invalidatedAt = now,
            reason = reason.trim().ifEmpty { "unspecified" },
        )
    dailyInsightDao.upsert(
        /** Daily insight entity. */
        DailyInsightEntity(
            id = "lens_dirty_$dayKey",
            dayKey = dayKey,
            module = DAILY_INSIGHT_MODULE_LENS_DIRTY_DAY,
            dimensionId = null,
            plannedMinutes = null,
            actualMinutes = null,
            focusedMinutes = null,
            completedCount = null,
            totalCount = null,
            summaryJson = encodeLensDirtyDayMetadata(metadata),
            generatedAt = now,
        ),
    )
    logger.d(
        "LensDirtyDayInvalidation.markLensDayDirty",
        "Marked day as dirty for lens snapshot",
        /** Map of. */
        mapOf("dayKey" to dayKey, "changedModules" to mergedModules.joinToString(","), "reason" to metadata.reason),
    )
}

/**
 * Clear lens day dirty.
 */
suspend fun clearLensDayDirty(
    /** Daily insight dao. */
    dailyInsightDao: DailyInsightDao,
    /** Logger. */
    logger: UnifiedLogger,
    /** Day key. */
    dayKey: String,
) {
    dailyInsightDao.deleteSummaryForDay(dayKey, DAILY_INSIGHT_MODULE_LENS_DIRTY_DAY)
    logger.d(
        "LensDirtyDayInvalidation.clearLensDayDirty",
        "Cleared dirty marker for day",
        /** Map of. */
        mapOf("dayKey" to dayKey),
    )
}

/**
 * Load lens dirty day metadata.
 */
suspend fun loadLensDirtyDayMetadata(
    /** Daily insight dao. */
    dailyInsightDao: DailyInsightDao,
    /** Day key. */
    dayKey: String,
): LensDirtyDayMetadata? {
    /** Entity. */
    val entity = dailyInsightDao.getSummaryForDay(dayKey, DAILY_INSIGHT_MODULE_LENS_DIRTY_DAY) ?: return null
    /** Summary. */
    val summary = entity.summaryJson ?: return null
    return decodeLensDirtyDayMetadata(dayKey, summary)
}

/**
 * Get lens dirty day keys.
 */
suspend fun getLensDirtyDayKeys(
    /** Daily insight dao. */
    dailyInsightDao: DailyInsightDao,
    dayKeys: Set<String>,
): Set<String> {
    /** If. */
    if (dayKeys.isEmpty()) return emptySet()
    return dailyInsightDao
        .getSummariesForDays(dayKeys.toList(), DAILY_INSIGHT_MODULE_LENS_DIRTY_DAY)
        .map { it.dayKey }
        .toSet()
}

private fun encodeLensDirtyDayMetadata(metadata: LensDirtyDayMetadata): String {
    /** Modules. */
    val modules = metadata.changedModules.joinToString(",")
    /** Safe reason. */
    val safeReason =
        metadata.reason
            .replace("|", "/")
            .replace("\n", " ")
            .trim()
    return "dayKey=${metadata.dayKey}|changedModules=$modules|invalidatedAt=${metadata.invalidatedAt}|reason=$safeReason"
}

private fun decodeLensDirtyDayMetadata(
    /** Day key. */
    dayKey: String,
    /** Encoded. */
    encoded: String,
): LensDirtyDayMetadata {
    /** Fields. */
    val fields =
        /** Encoded. */
        encoded
            .split("|")
            .mapNotNull { part ->
                /** Index. */
                val index = part.indexOf('=')
                /** If. */
                if (index <= 0) null else part.substring(0, index) to part.substring(index + 1)
            }.toMap()
    /** Modules. */
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
