//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

@file:Suppress("MagicNumber")

package io.payanam.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.payanam.common.logging.UnifiedLogger

/**
 * Migration from version 8 to 9.
 *
 * Adds external-import metadata baseline:
 * - import_batches table
 * - import_source/import_id/imported_at/import_batch_id columns on tasks/time_entries
 */
val MIGRATION_8_9 =
    object : Migration(8, 9) {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Performs the migrate.
         */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        override fun migrate(database: SupportSQLiteDatabase) {
            logger.i("Migration.8_9", "Starting migration from version 8 to 9")

            try {
                createImportBatchesTable(database)
                addImportMetadataColumns(database)
                createImportMetadataIndexes(database)

                logger.i("Migration.8_9", "Migration from 8 to 9 completed successfully")
            } catch (e: Exception) {
                logger.e(
                    "Migration.8_9",
                    "Migration failed",
                    e,
                    mapOf(
                        "error" to (e.message ?: "Unknown error"),
                    ),
                )
                throw e
            }
        }
    }

private fun createImportBatchesTable(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS import_batches (
            id TEXT PRIMARY KEY NOT NULL,
            source TEXT NOT NULL,
            importedAt TEXT NOT NULL,
            version TEXT,
            fileHash TEXT,
            notes TEXT
        )
        """.trimIndent(),
    )
    database.execSQL(
        """
        CREATE INDEX IF NOT EXISTS index_import_batches_source
        ON import_batches(source)
        """.trimIndent(),
    )
    database.execSQL(
        """
        CREATE INDEX IF NOT EXISTS index_import_batches_importedAt
        ON import_batches(importedAt)
        """.trimIndent(),
    )
}

private fun addImportMetadataColumns(database: SupportSQLiteDatabase) {
    database.execSQL("ALTER TABLE tasks ADD COLUMN import_source TEXT")
    database.execSQL("ALTER TABLE tasks ADD COLUMN import_id TEXT")
    database.execSQL("ALTER TABLE tasks ADD COLUMN imported_at TEXT")
    database.execSQL("ALTER TABLE tasks ADD COLUMN import_batch_id TEXT REFERENCES import_batches(id)")

    database.execSQL("ALTER TABLE time_entries ADD COLUMN import_source TEXT")
    database.execSQL("ALTER TABLE time_entries ADD COLUMN import_id TEXT")
    database.execSQL("ALTER TABLE time_entries ADD COLUMN imported_at TEXT")
    database.execSQL("ALTER TABLE time_entries ADD COLUMN import_batch_id TEXT REFERENCES import_batches(id)")
}

private fun createImportMetadataIndexes(database: SupportSQLiteDatabase) {
    database.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_import_batch_id ON tasks(import_batch_id)")
    database.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_import_source_import_id ON tasks(import_source, import_id)")
    database.execSQL("CREATE INDEX IF NOT EXISTS index_time_entries_import_batch_id ON time_entries(import_batch_id)")
    database.execSQL("CREATE INDEX IF NOT EXISTS index_time_entries_import_source_import_id ON time_entries(import_source, import_id)")
}
