//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

@file:Suppress("MagicNumber")

package io.payanam.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.payanam.common.logging.UnifiedLogger

/**
 * Migration from version 14 to 15.
 * - Adds journal_notes table for consolidated freeform notes.
 * - Copies existing notes rows into journal_notes.
 */
val MIGRATION_14_15 =
    object : Migration(14, 15) {
        private val logger = UnifiedLogger.getInstance()

        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        override fun migrate(database: SupportSQLiteDatabase) {
            logger.i("Migration.14_15", "Starting migration from version 14 to 15")
            try {
                createJournalNotesTable(database)
                backfillJournalNotesFromLegacyNotes(database)
                logger.i("Migration.14_15", "Migration from version 14 to 15 completed successfully")
            } catch (e: Exception) {
                logger.e("Migration.14_15", "Migration from version 14 to 15 failed", e)
                throw e
            }
        }
    }

private fun createJournalNotesTable(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS journal_notes (
            id TEXT NOT NULL PRIMARY KEY,
            title TEXT NOT NULL,
            details TEXT,
            lifeIntentionCategory TEXT NOT NULL,
            dimension_id TEXT,
            day_key TEXT NOT NULL,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            FOREIGN KEY(dimension_id) REFERENCES life_dimensions(id) ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
    )
    database.execSQL("CREATE INDEX IF NOT EXISTS index_journal_notes_day_key ON journal_notes(day_key)")
    database.execSQL("CREATE INDEX IF NOT EXISTS index_journal_notes_dimension_id ON journal_notes(dimension_id)")
    database.execSQL("CREATE INDEX IF NOT EXISTS index_journal_notes_updated_at ON journal_notes(updated_at)")
}

private fun backfillJournalNotesFromLegacyNotes(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        INSERT OR IGNORE INTO journal_notes (
            id,
            title,
            details,
            lifeIntentionCategory,
            dimension_id,
            day_key,
            created_at,
            updated_at
        )
        SELECT
            id,
            title,
            details,
            lifeIntentionCategory,
            dimension_id,
            COALESCE(day_key, substr(createdAt, 1, 10), strftime('%Y-%m-%d','now')),
            createdAt,
            updatedAt
        FROM notes
        WHERE id IS NOT NULL
        """.trimIndent(),
    )
}
