//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.repository

import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.dao.DailyInsightDao
import io.payanam.database.entity.DailyInsightEntity
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Android's half of the lens dirty-day marker: the Room read and write, nothing else.
//
// The metadata shape, its encoding and the merge rule live in the shared model module
// (LensDirtyDayMetadata, buildLensDirtyDayMetadata, encodeLensDirtyDayMetadata,
// decodeLensDirtyDayMetadata). They are a PERSISTED FORMAT: both platforms write it into
// `daily_insights.summary_json` in the same encrypted file, so a second copy of the encoding would let
// one platform write a payload the other cannot read. Only the storage call is platform-specific.

/**
 * Marks [dayKey] as dirty for [changedModules], merging into any existing marker and logging the reason.
 * Stored as a daily_insights row keyed by `lens_dirty_<dayKey>`.
 */
suspend fun markLensDayDirty(
    dailyInsightDao: DailyInsightDao,
    logger: UnifiedLogger,
    dayKey: String,
    changedModules: Set<String>,
    reason: String,
) {
    val existing = loadLensDirtyDayMetadata(dailyInsightDao, dayKey)
    val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    val metadata =
        buildLensDirtyDayMetadata(
            dayKey = dayKey,
            existingChangedModules = existing?.changedModules.orEmpty(),
            changedModules = changedModules,
            reason = reason,
            invalidatedAt = now,
        )
    dailyInsightDao.upsert(
        DailyInsightEntity(
            id = lensDirtyDayId(dayKey),
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
        mapOf(
            "dayKey" to dayKey,
            "changedModules" to metadata.changedModules.joinToString(","),
            "reason" to metadata.reason,
        ),
    )
}

/**
 * Clears the dirty marker for [dayKey] (lens snapshot is now up to date).
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
 * Reads the dirty-day metadata for [dayKey], or null if the day is clean.
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
 * Returns the subset of [dayKeys] that currently have a dirty marker.
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
