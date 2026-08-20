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

        override fun migrate(database: SupportSQLiteDatabase) {
            logger.i("Migration.14_15", "Starting migration from version 14 to 15")
            try {
                /** Create journal notes table. */
                createJournalNotesTable(database)
                /** Backfill journal notes from legacy notes. */
                backfillJournalNotesFromLegacyNotes(database)
                logger.i("Migration.14_15", "Migration from version 14 to 15 completed successfully")
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
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
            /** Id. */
            id,
            /** Title. */
            title,
            /** Details. */
            details,
            /** Life intention category. */
            lifeIntentionCategory,
            /** Dimension id. */
            dimension_id,
            /** Day key. */
            day_key,
            /** Created at. */
            created_at,
            /** Updated at. */
            updated_at
        )
        /** Select. */
        SELECT
            /** Id. */
            id,
            /** Title. */
            title,
            /** Details. */
            details,
            /** Life intention category. */
            lifeIntentionCategory,
            /** Dimension id. */
            dimension_id,
            /** Coalesce. */
            COALESCE(day_key, substr(createdAt, 1, 10), strftime('%Y-%m-%d','now')),
            /** Created at. */
            createdAt,
            /** Updated at. */
            updatedAt
        FROM notes
        WHERE id IS NOT NULL
        """.trimIndent(),
    )
}
