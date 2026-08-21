//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.repository

import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.dao.DailyInsightDao
import io.payanam.database.entity.DailyInsightEntity
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
const val DAILY_INSIGHT_MODULE_UNIFIED_SNAPSHOT = "lens_unified_snapshot"
const val DAILY_INSIGHT_MODULE_LENS_DIRTY_DAY = "lens_dirty_day"

/**
 * LensDirtyDayMetadata.
 */
data class LensDirtyDayMetadata(
    val dayKey: String,
    val changedModules: Set<String>,
    val invalidatedAt: String,
    val reason: String,
)

/**
 * Mark lens day dirty.
 */
suspend fun markLensDayDirty(
    dailyInsightDao: DailyInsightDao,
    logger: UnifiedLogger,
    dayKey: String,
    changedModules: Set<String>,
    reason: String,
) {
    val normalizedModules = changedModules.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val existing = loadLensDirtyDayMetadata(dailyInsightDao, dayKey)
    val mergedModules = (existing?.changedModules.orEmpty() + normalizedModules).toSortedSet()
    val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    val metadata =
        LensDirtyDayMetadata(
            dayKey = dayKey,
            changedModules = mergedModules,
            invalidatedAt = now,
            reason = reason.trim().ifEmpty { "unspecified" },
        )
    dailyInsightDao.upsert(
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
        mapOf("dayKey" to dayKey, "changedModules" to mergedModules.joinToString(","), "reason" to metadata.reason),
    )
}

/**
 * Clear lens day dirty.
 */
suspend fun clearLensDayDirty(
    dailyInsightDao: DailyInsightDao,
    logger: UnifiedLogger,
    dayKey: String,
) {
    dailyInsightDao.deleteSummaryForDay(dayKey, DAILY_INSIGHT_MODULE_LENS_DIRTY_DAY)
    logger.d(
        "LensDirtyDayInvalidation.clearLensDayDirty",
        "Cleared dirty marker for day",
        mapOf("dayKey" to dayKey),
    )
}

/**
 * Load lens dirty day metadata.
 */
suspend fun loadLensDirtyDayMetadata(
    dailyInsightDao: DailyInsightDao,
    dayKey: String,
): LensDirtyDayMetadata? {
    val entity = dailyInsightDao.getSummaryForDay(dayKey, DAILY_INSIGHT_MODULE_LENS_DIRTY_DAY) ?: return null
    val summary = entity.summaryJson ?: return null
    return decodeLensDirtyDayMetadata(dayKey, summary)
}

/**
 * Get lens dirty day keys.
 */
suspend fun getLensDirtyDayKeys(
    dailyInsightDao: DailyInsightDao,
    dayKeys: Set<String>,
): Set<String> {
    if (dayKeys.isEmpty()) return emptySet()
    return dailyInsightDao
        .getSummariesForDays(dayKeys.toList(), DAILY_INSIGHT_MODULE_LENS_DIRTY_DAY)
        .map { it.dayKey }
        .toSet()
}

private fun encodeLensDirtyDayMetadata(metadata: LensDirtyDayMetadata): String {
    val modules = metadata.changedModules.joinToString(",")
    val safeReason =
        metadata.reason
            .replace("|", "/")
            .replace("\n", " ")
            .trim()
    return "dayKey=${metadata.dayKey}|changedModules=$modules|invalidatedAt=${metadata.invalidatedAt}|reason=$safeReason"
}

private fun decodeLensDirtyDayMetadata(
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
