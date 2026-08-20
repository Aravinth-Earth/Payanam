//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.repository

import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.dao.DailyInsightDao
import io.payanam.database.entity.DailyInsightEntity
import io.payanam.database.entity.LensReflectionEntity
import io.payanam.domain.repository.LensReflectionRecord
import io.payanam.domain.repository.UnifiedLensSnapshot
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal suspend fun loadSnapshotFromPersistentCache(
    /** Daily insight dao. */
    dailyInsightDao: DailyInsightDao,
    /** Day key. */
    dayKey: String,
): UnifiedLensSnapshot? {
    /** Cached. */
    val cached =
        dailyInsightDao.getSummaryForDay(
            dayKey = dayKey,
            module = DAILY_INSIGHT_MODULE_UNIFIED_SNAPSHOT,
        ) ?: return null
    /** Summary. */
    val summary = cached.summaryJson ?: return null
    /** Decoded. */
    val decoded = decodeUnifiedLensSnapshot(dayKey, summary) ?: return null
    /** Split total. */
    val splitTotal =
        decoded.reality.actualTimeOnlyMinutes +
            decoded.reality.actualTaskMinutes +
            decoded.reality.actualHabitMinutes
    // Backward-compat guard: older cached payloads did not include split fields.
    // In that case decode defaults to zero and would show incorrect Lens spent split.
    /** If. */
    if (decoded.reality.totalActualMinutes > 0 && splitTotal <= 0) {
        return null
    }
    return decoded
}

internal suspend fun persistSnapshotToDailyInsightCache(
    /** Daily insight dao. */
    dailyInsightDao: DailyInsightDao,
    /** Logger. */
    logger: UnifiedLogger,
    /** Day key. */
    dayKey: String,
    /** Snapshot. */
    snapshot: UnifiedLensSnapshot,
) {
    /** Now. */
    val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    /** Encoded snapshot. */
    val encodedSnapshot = encodeUnifiedLensSnapshot(snapshot)
    /** Cache entity. */
    val cacheEntity =
        /** Daily insight entity. */
        DailyInsightEntity(
            id = "lens_snapshot_$dayKey",
            dayKey = dayKey,
            module = DAILY_INSIGHT_MODULE_UNIFIED_SNAPSHOT,
            dimensionId = null,
            plannedMinutes = snapshot.planning.totalPlannedMinutes,
            actualMinutes = snapshot.reality.totalActualMinutes,
            focusedMinutes = null,
            completedCount = snapshot.reality.completedTasks.count { it.status == "completed" },
            totalCount = snapshot.planning.plannedTasks.size,
            summaryJson = encodedSnapshot,
            generatedAt = now,
        )
    dailyInsightDao.upsert(cacheEntity)
    logger.d(
        "LensRepository.persistSnapshotToDailyInsightCache",
        "Persisted unified snapshot to daily insights cache",
        /** Map of. */
        mapOf("dayKey" to dayKey),
    )
}

internal fun LensReflectionEntity.toRecord(): LensReflectionRecord =
    /** Lens reflection record. */
    LensReflectionRecord(
        id = id,
        dayKey = dayKey,
        dimensionId = dimensionId,
        reflectionType = reflectionType,
        title = title,
        description = description,
        gapMinutes = gapMinutes,
        relatedEntityId = relatedEntityId,
        isAddressed = isAddressed == 1,
        userNote = userNote,
        createdAt = createdAt,
    )
