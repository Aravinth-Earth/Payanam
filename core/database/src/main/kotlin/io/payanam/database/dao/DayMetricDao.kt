//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.payanam.database.entity.DayMetricEntity
import kotlinx.coroutines.flow.Flow

@Dao
/**
 * Room DAO for the `day_metrics` time-series table: one [DayMetricEntity] per
 * day aggregating that day's tracked metrics. Rows are keyed by `dayKey`.
 */
interface DayMetricDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Inserts or replaces a batch of daily metric rows.
     */
    suspend fun upsertAll(rows: List<DayMetricEntity>)

    @Query("DELETE FROM day_metrics WHERE dayKey >= :fromDay")
    /**
     * Deletes every metric row whose `dayKey` is [fromDay] or later. Used to
     * drop a corrupted/partial tail before recomputing.
     */
    suspend fun deleteFrom(fromDay: String)

    @Query("SELECT * FROM day_metrics ORDER BY dayKey ASC")
    /**
     * Emits all daily metrics ordered chronologically as a [Flow].
     */
    fun observeAll(): Flow<List<DayMetricEntity>>

    @Query("SELECT * FROM day_metrics ORDER BY dayKey ASC")
    /**
     * Returns all daily metrics ordered chronologically.
     */
    suspend fun getAll(): List<DayMetricEntity>

    @Query("SELECT MIN(dayKey) FROM day_metrics")
    /**
     * Returns the earliest `dayKey` present, or null when the table is empty.
     */
    suspend fun earliestDayKey(): String?

    @Query("SELECT * FROM day_metrics WHERE dayKey = :dayKey")
    /**
     * Returns the metric row for [dayKey], or null.
     */
    suspend fun forDay(dayKey: String): DayMetricEntity?

    @Query("SELECT * FROM day_metrics WHERE dayKey BETWEEN :start AND :end ORDER BY dayKey ASC")
    /**
     * Returns metric rows whose `dayKey` falls within the inclusive
     * [start]..[end] window, ordered chronologically.
     */
    suspend fun getForWindow(start: String, end: String): List<DayMetricEntity>

    @Query("SELECT * FROM day_metrics WHERE dayKey < :dayKey ORDER BY dayKey DESC LIMIT 1")
    /**
     * Returns the most recent metric row strictly before [dayKey], or null when
     * there is none. Used to compute deltas against the prior day.
     */
    suspend fun latestBefore(dayKey: String): DayMetricEntity?

    @Query("SELECT COUNT(*) FROM day_metrics")
    /**
     * Total number of L3 daily-metric rows persisted (every calendar day is
     * dense, so this equals the number of scored days).
     */
    suspend fun count(): Int
}
