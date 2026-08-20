//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

@file:Suppress("MagicNumber")

package io.payanam.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.payanam.common.logging.UnifiedLogger

/**
 * Migration from version 9 to 10.
 * - Adds day_key columns for per-day query optimization
 * - Adds daily_insights cache table for aggregated summaries
 */
val MIGRATION_9_10 =
    object : Migration(9, 10) {
        private val logger = UnifiedLogger.getInstance()

        override fun migrate(database: SupportSQLiteDatabase) {
            logger.i("Migration.9_10", "Starting migration from version 9 to 10")

            try {
                /** Create daily insights table. */
                createDailyInsightsTable(database)
                /** Add day key columns. */
                addDayKeyColumns(database)
                /** Backfill day key values. */
                backfillDayKeyValues(database)
                /** Create day key indexes. */
                createDayKeyIndexes(database)
                logger.i("Migration.9_10", "Migration from 9 to 10 completed successfully")
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("Migration.9_10", "Migration from 9 to 10 failed", e)
                throw e
            }
        }
    }

private fun createDailyInsightsTable(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS daily_insights (
            id TEXT NOT NULL PRIMARY KEY,
            day_key TEXT NOT NULL,
            module TEXT NOT NULL,
            dimension_id TEXT,
            planned_minutes INTEGER,
            actual_minutes INTEGER,
            focused_minutes INTEGER,
            completed_count INTEGER,
            total_count INTEGER,
            summary_json TEXT,
            generated_at TEXT NOT NULL,
            FOREIGN KEY(dimension_id) REFERENCES life_dimensions(id) ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
    )
}

private fun addDayKeyColumns(database: SupportSQLiteDatabase) {
    database.execSQL("ALTER TABLE tasks ADD COLUMN day_key TEXT")
    database.execSQL("ALTER TABLE time_entries ADD COLUMN day_key TEXT")
    database.execSQL("ALTER TABLE notes ADD COLUMN day_key TEXT")
}

private fun backfillDayKeyValues(database: SupportSQLiteDatabase) {
    // Task day key tracks due day only; tasks without due date keep NULL.
    database.execSQL(
        """
        UPDATE tasks
        SET day_key = substr(dueDate, 1, 10)
        WHERE day_key IS NULL AND dueDate IS NOT NULL
        """.trimIndent(),
    )

    // Time-entry day key is the entry start day.
    database.execSQL(
        """
        UPDATE time_entries
        SET day_key = substr(startedAt, 1, 10)
        WHERE day_key IS NULL
        """.trimIndent(),
    )

    // Notes are queried by the day they were created.
    database.execSQL(
        """
        UPDATE notes
        SET day_key = substr(createdAt, 1, 10)
        WHERE day_key IS NULL
        """.trimIndent(),
    )
}

private fun createDayKeyIndexes(database: SupportSQLiteDatabase) {
    database.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_day_key ON tasks(day_key)")
    database.execSQL("CREATE INDEX IF NOT EXISTS index_time_entries_day_key ON time_entries(day_key)")
    database.execSQL("CREATE INDEX IF NOT EXISTS index_notes_day_key ON notes(day_key)")
    database.execSQL("CREATE INDEX IF NOT EXISTS index_daily_insights_dimension_id ON daily_insights(dimension_id)")
    database.execSQL(
        "CREATE INDEX IF NOT EXISTS index_daily_insights_day_key_module_dimension_id ON daily_insights(day_key, module, dimension_id)",
    )
    database.execSQL("CREATE INDEX IF NOT EXISTS index_daily_insights_generated_at ON daily_insights(generated_at)")
}
