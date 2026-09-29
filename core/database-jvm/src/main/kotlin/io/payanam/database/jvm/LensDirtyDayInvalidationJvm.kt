//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import io.payanam.database.repository.DAILY_INSIGHT_MODULE_LENS_DIRTY_DAY
import io.payanam.database.repository.LensDirtyDayMetadata
import io.payanam.database.repository.buildLensDirtyDayMetadata
import io.payanam.database.repository.decodeLensDirtyDayMetadata
import io.payanam.database.repository.encodeLensDirtyDayMetadata
import io.payanam.database.repository.lensDirtyDayId
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.UnifiedLogger
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// The desktop's half of the lens dirty-day marker: the JDBC read and write, nothing else.
//
// The metadata shape, its encoding and the merge rule are the SHARED ones in the model module
// (LensDirtyDayMetadata and its helpers). They are a persisted format written into
// `daily_insights.summary_json` in the same encrypted file Android writes, so re-deriving the encoding
// here would let one platform write a payload the other cannot read.
//
// The marker carries no cron of its own: nothing on the desktop schedules a lens refresh yet, so these
// functions exist to keep the DATA correct when a shared ViewModel mutates a task, time entry or day plan
// on the desktop. A stale marker is harmless; a missing one silently serves a stale snapshot.

/** The columns a marker row is written with, in the order [UPSERT_SQL] binds them. */
private const val MARKER_COLUMNS =
    "(id, day_key, module, dimension_id, planned_minutes, actual_minutes, focused_minutes, " +
        "completed_count, total_count, summary_json, generated_at)"

private const val UPSERT_SQL =
    "INSERT OR REPLACE INTO daily_insights $MARKER_COLUMNS VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

/** Reads the raw `summary_json` of one day's marker, or null when the day is clean. */
private const val READ_MARKER_JSON_SQL =
    "SELECT summary_json FROM daily_insights " +
        "WHERE day_key = ? AND module = ? AND dimension_id IS NULL LIMIT 1"

private const val DELETE_MARKER_SQL =
    "DELETE FROM daily_insights WHERE day_key = ? AND module = ?"

/**
 * Marks [dayKey] as dirty for [changedModules], merging into any existing marker.
 *
 * Merging is the reason this reads before it writes: a second mutation on the same day must add its module
 * to the marker rather than replace it, or the first module's invalidation is lost and the lens snapshot
 * keeps serving it stale.
 */
suspend fun markLensDayDirty(
    session: PayanamDatabaseSession,
    dayKey: String,
    changedModules: Set<String>,
    reason: String,
    logger: UnifiedLogger = AppLogger.current,
) = JdbcFlows.onIo { markLensDayDirtySync(session, dayKey, changedModules, reason, logger) }

/**
 * [markLensDayDirty] without the dispatcher switch, for a caller that is ALREADY inside [JdbcFlows.onIo].
 *
 * Both forms exist because a repository method wraps its whole body in one `onIo` block: calling the
 * suspend version from inside it is a suspend-inside-non-suspend-lambda compile error, and re-entering the
 * dispatcher per statement would also lose the "one block, one thread" property the repositories rely on.
 */
internal fun markLensDayDirtySync(
    session: PayanamDatabaseSession,
    dayKey: String,
    changedModules: Set<String>,
    reason: String,
    logger: UnifiedLogger = AppLogger.current,
) {
    // The marker is read through the synchronous helper, not through loadLensDirtyDayMetadata: this block
    // is already inside onIo, and that function would wrap a second suspension around a read that is
    // already on the IO dispatcher.
    val existing =
        readMarkerJson(session, dayKey)?.let { decodeLensDirtyDayMetadata(dayKey, it) }
    val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    val metadata =
        buildLensDirtyDayMetadata(
            dayKey = dayKey,
            existingChangedModules = existing?.changedModules.orEmpty(),
            changedModules = changedModules,
            reason = reason,
            invalidatedAt = now,
        )
    JdbcQueries.write(
        session,
        UPSERT_SQL,
        arrayOf<String?>(
            lensDirtyDayId(dayKey),
            dayKey,
            DAILY_INSIGHT_MODULE_LENS_DIRTY_DAY,
            null,
            null,
            null,
            null,
            null,
            null,
            encodeLensDirtyDayMetadata(metadata),
            now,
        ),
    )
    logger.d(
        "LensDirtyDayInvalidationJvm.markLensDayDirty",
        "Marked day as dirty for lens snapshot",
        mapOf(
            "dayKey" to dayKey,
            "changedModules" to metadata.changedModules.joinToString(","),
            "reason" to metadata.reason,
        ),
    )
}

/**
 * Clears the dirty marker for [dayKey] (the lens snapshot for that day is now up to date).
 */
suspend fun clearLensDayDirty(
    session: PayanamDatabaseSession,
    dayKey: String,
    logger: UnifiedLogger = AppLogger.current,
) = JdbcFlows.onIo { clearLensDayDirtySync(session, dayKey, logger) }

/** [clearLensDayDirty] without the dispatcher switch — see [markLensDayDirtySync]. */
internal fun clearLensDayDirtySync(
    session: PayanamDatabaseSession,
    dayKey: String,
    logger: UnifiedLogger = AppLogger.current,
) {
    JdbcQueries.write(session, DELETE_MARKER_SQL, arrayOf(dayKey, DAILY_INSIGHT_MODULE_LENS_DIRTY_DAY))
    logger.d(
        "LensDirtyDayInvalidationJvm.clearLensDayDirty",
        "Cleared dirty marker for day",
        mapOf("dayKey" to dayKey),
    )
}

/**
 * Reads the dirty-day metadata for [dayKey], or null when the day is clean.
 *
 * A row that exists but carries no payload counts as clean: there is nothing to decode, and reporting
 * metadata with an empty module set would look like a usable marker to the caller.
 */
suspend fun loadLensDirtyDayMetadata(
    session: PayanamDatabaseSession,
    dayKey: String,
): LensDirtyDayMetadata? = JdbcFlows.onIo { loadLensDirtyDayMetadataSync(session, dayKey) }

/** [loadLensDirtyDayMetadata] without the dispatcher switch — see [markLensDayDirtySync]. */
internal fun loadLensDirtyDayMetadataSync(
    session: PayanamDatabaseSession,
    dayKey: String,
): LensDirtyDayMetadata? = readMarkerJson(session, dayKey)?.let { decodeLensDirtyDayMetadata(dayKey, it) }

/**
 * Reads the stored payload for [dayKey] with no dispatcher switch, so a caller already inside
 * [JdbcFlows.onIo] can merge a marker without nesting a second suspension.
 */
private fun readMarkerJson(
    session: PayanamDatabaseSession,
    dayKey: String,
): String? =
    JdbcQueries.readScalar(
        session,
        READ_MARKER_JSON_SQL,
        arrayOf(dayKey, DAILY_INSIGHT_MODULE_LENS_DIRTY_DAY),
    )

/**
 * Returns the subset of [dayKeys] that currently carry a dirty marker.
 *
 * One query for the whole set rather than one per day: the caller asks this for a visible date range, and a
 * per-day loop would be one round trip per column of the calendar.
 */
suspend fun getLensDirtyDayKeys(
    session: PayanamDatabaseSession,
    dayKeys: Set<String>,
): Set<String> = JdbcFlows.onIo { getLensDirtyDayKeysSync(session, dayKeys) }

/** [getLensDirtyDayKeys] without the dispatcher switch — see [markLensDayDirtySync]. */
internal fun getLensDirtyDayKeysSync(
    session: PayanamDatabaseSession,
    dayKeys: Set<String>,
): Set<String> {
    if (dayKeys.isEmpty()) {
        return emptySet()
    }
    return run {
        val placeholders = dayKeys.joinToString(separator = ",") { "?" }
        JdbcQueries
            .read(
                session,
                "SELECT day_key FROM daily_insights WHERE module = ? AND dimension_id IS NULL " +
                    "AND day_key IN ($placeholders)",
                arrayOf(DAILY_INSIGHT_MODULE_LENS_DIRTY_DAY, *dayKeys.toTypedArray()),
            ) { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.getString("day_key"))
                    }
                }
            }.toSet()
    }
}
