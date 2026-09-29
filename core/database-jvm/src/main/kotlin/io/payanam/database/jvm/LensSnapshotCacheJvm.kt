//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

//  The lens snapshot cache, over JDBC.
//
//  The ENCODING is shared — [encodeUnifiedLensSnapshot] and [decodeUnifiedLensSnapshot] live in
//  `:core:domain` — and only the storage differs here: Android writes it through Room, the desktop writes the
//  same `daily_insights` row through JDBC. That split is deliberate. If each platform had its own codec, the
//  same day would decode differently on each, and the desktop would be reading a payload it cannot fully
//  interpret rather than one it wrote.
//
//  Statements are copied from `DailyInsightDao` and `LensReflectionDao`.

import io.payanam.database.entity.DailyInsightEntity
import io.payanam.database.entity.LensReflectionEntity
import io.payanam.database.repository.DAILY_INSIGHT_MODULE_UNIFIED_SNAPSHOT
import io.payanam.domain.lens.decodeUnifiedLensSnapshot
import io.payanam.domain.lens.encodeUnifiedLensSnapshot
import io.payanam.domain.repository.LensReflectionRecord
import io.payanam.domain.repository.UnifiedLensSnapshot
import io.payanam.shared.logging.AppLogger
import java.sql.ResultSet
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Reads the cached snapshot for [dayKey], or null when there is none, it is undecodable, or it predates
 * the split-minute fields. */
internal fun loadSnapshotFromPersistentCache(
    session: PayanamDatabaseSession,
    dayKey: String,
): UnifiedLensSnapshot? {
    val summary =
        JdbcQueries.readScalar(
            session,
            SUMMARY_FOR_DAY_SQL,
            arrayOf(dayKey, DAILY_INSIGHT_MODULE_UNIFIED_SNAPSHOT),
        ) ?: return null
    val decoded = decodeUnifiedLensSnapshot(dayKey, summary) ?: return null
    val splitTotal =
        decoded.reality.actualTimeOnlyMinutes +
            decoded.reality.actualTaskMinutes +
            decoded.reality.actualHabitMinutes
    // Backward-compat guard, mirrored from Android: a payload written before the split fields existed decodes
    // those to zero, which would show the day's spent time as an unexplained lump. Recompute instead.
    if (decoded.reality.totalActualMinutes > 0 && splitTotal <= 0) {
        return null
    }
    return decoded
}

/** Writes [snapshot] into the `daily_insights` cache row for [dayKey]. */
internal fun persistSnapshotToDailyInsightCache(
    session: PayanamDatabaseSession,
    dayKey: String,
    snapshot: UnifiedLensSnapshot,
) {
    val entity =
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
            summaryJson = encodeUnifiedLensSnapshot(snapshot),
            generatedAt = LocalDateTime.now().format(ISO_FORMATTER),
        )
    JdbcQueries.write(session, UPSERT_INSIGHT_SQL, entity.toBindings())
    AppLogger.current.d(
        "LensRepositoryJvm.persistSnapshotToDailyInsightCache",
        "Persisted unified snapshot to daily insights cache",
        mapOf("dayKey" to dayKey),
    )
}

/** Emits the reflection cards stored for [dayKey], newest first. */
internal fun readReflectionsForDay(
    session: PayanamDatabaseSession,
    dayKey: String,
): List<LensReflectionEntity> =
    JdbcQueries.read(session, REFLECTIONS_FOR_DAY_SQL, arrayOf(dayKey)) { resultSet ->
        buildList {
            while (resultSet.next()) add(resultSet.readReflection())
        }
    }

internal fun ResultSet.readReflection(): LensReflectionEntity =
    LensReflectionEntity(
        id = getString("id"),
        dayKey = getString("day_key"),
        dimensionId = getString("dimension_id"),
        reflectionType = getString("reflection_type"),
        title = getString("title"),
        description = getString("description"),
        gapMinutes = getNullableInt("gap_minutes"),
        relatedEntityId = getString("related_entity_id"),
        isAddressed = getInt("is_addressed"),
        userNote = getString("user_note"),
        createdAt = getString("created_at"),
    )

internal fun LensReflectionEntity.toReflectionBindings(): Array<String?> =
    arrayOf(
        id,
        dayKey,
        dimensionId,
        reflectionType,
        title,
        description,
        gapMinutes?.toString(),
        relatedEntityId,
        isAddressed.toString(),
        userNote,
        createdAt,
    )

internal fun LensReflectionEntity.toRecord(): LensReflectionRecord =
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

private fun DailyInsightEntity.toBindings(): Array<String?> =
    arrayOf(
        id,
        dayKey,
        module,
        dimensionId,
        plannedMinutes?.toString(),
        actualMinutes?.toString(),
        focusedMinutes?.toString(),
        completedCount?.toString(),
        totalCount?.toString(),
        summaryJson,
        generatedAt,
    )

/** `getInt` returns 0 for SQL NULL, which would invent a gap of zero rather than an absent one. */
private fun ResultSet.getNullableInt(column: String): Int? {
    val value = getInt(column)
    return if (wasNull()) null else value
}

private val ISO_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

/** Verbatim from `DailyInsightDao.getSummaryForDay`, minus the columns the caller does not use. */
private const val SUMMARY_FOR_DAY_SQL =
    "SELECT summary_json FROM daily_insights WHERE day_key = ? AND module = ? AND dimension_id IS NULL LIMIT 1"

/** `DailyInsightDao.upsert` is `@Insert(REPLACE)`; the cache row's id is derived from the day. */
private const val UPSERT_INSIGHT_SQL = """
    INSERT OR REPLACE INTO daily_insights
    (id, day_key, module, dimension_id, planned_minutes, actual_minutes, focused_minutes, completed_count, total_count, summary_json, generated_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
"""

/** Verbatim from `LensReflectionDao.observeReflectionsForDay` / `getReflectionsForDaySync`. */
internal const val REFLECTIONS_FOR_DAY_SQL =
    "SELECT * FROM lens_reflections WHERE day_key = ? ORDER BY created_at DESC"

/** Verbatim from `LensReflectionDao.deleteReflectionsForDay`. */
internal const val DELETE_REFLECTIONS_FOR_DAY_SQL = "DELETE FROM lens_reflections WHERE day_key = ?"

/** `LensReflectionDao.insertReflections` is `@Insert(REPLACE)`. */
internal const val INSERT_REFLECTION_SQL = """
    INSERT OR REPLACE INTO lens_reflections
    (id, day_key, dimension_id, reflection_type, title, description, gap_minutes, related_entity_id, is_addressed, user_note, created_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
"""

/** Verbatim from `LensReflectionDao.markReflectionAddressed` — sets the flag and the note together. */
internal const val MARK_REFLECTION_ADDRESSED_SQL =
    "UPDATE lens_reflections SET is_addressed = 1, user_note = ? WHERE id = ?"
