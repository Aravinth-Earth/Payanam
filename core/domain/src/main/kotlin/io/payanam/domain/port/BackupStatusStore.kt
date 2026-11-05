//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Last-known backup state shown across the platforms: the most recent success timestamp and the
 * latest (dismissible) failure, if any.
 *
 * Moved here from Android's own store when the settings screen's state layer became shared: both
 * platforms render the same snapshot, so the shape lives where both can see it.
 */
data class BackupStatusSnapshot(
    val lastSuccessAtMillis: Long = 0L,
    val lastSuccessDisplay: String? = null,
    val latestFailure: BackupFailureStatus? = null,
)

/**
 * A single backup failure shown to the user: message plus a pre-formatted display timestamp.
 */
data class BackupFailureStatus(
    val message: String,
    val recordedAtDisplay: String?,
)

/**
 * Formats an epoch-millis timestamp for display ("yyyy-MM-dd HH:mm").
 *
 * The ONE implementation of the display format: both platforms render backup timestamps through it,
 * so the two apps cannot drift into two date formats for the same value.
 */
fun formatBackupDisplayTimestamp(millis: Long): String = Instant
    .ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault())
    .toLocalDateTime()
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

/**
 * The last auto-backup outcome, as state a shared screen renders.
 *
 * SUPPORTED on both platforms: Android's store persists its snapshot in SharedPreferences, the
 * desktop holds it in memory until its backup wiring lands. This is a READ port — the writer side
 * (`recordSuccess` / `recordFailure` / `dismissLatestFailure`) stays on each platform, because only
 * that platform's own backup machinery produces outcomes.
 */
interface BackupStatusStore {
    /** The current snapshot; emits on every recorded outcome. */
    val status: StateFlow<BackupStatusSnapshot>

    /** Formats an epoch-millis timestamp for display — see [formatBackupDisplayTimestamp]. */
    fun formatBackupTimestamp(millis: Long): String
}
