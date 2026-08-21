//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

@file:Suppress("MagicNumber")

package io.payanam.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.payanam.common.logging.UnifiedLogger

/**
 * Migration from version 12 to 13.
 * - Adds lens_reflections table for planning/reality gap detection.
 */
val MIGRATION_12_13 =
    object : Migration(12, 13) {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Performs the migrate.
         */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        override fun migrate(database: SupportSQLiteDatabase) {
            logger.i("Migration.12_13", "Starting migration from version 12 to 13")
            try {
                createLensReflectionsTable(database)
                logger.i("Migration.12_13", "Migration from 12 to 13 completed successfully")
            } catch (e: Exception) {
                logger.e("Migration.12_13", "Migration from 12 to 13 failed", e)
                throw e
            }
        }
    }

private fun createLensReflectionsTable(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS lens_reflections (
            id TEXT PRIMARY KEY NOT NULL,
            day_key TEXT NOT NULL,
            dimension_id TEXT,
            reflection_type TEXT NOT NULL,
            title TEXT NOT NULL,
            description TEXT,
            gap_minutes INTEGER,
            related_entity_id TEXT,
            is_addressed INTEGER NOT NULL DEFAULT 0,
            user_note TEXT,
            created_at TEXT NOT NULL
        )
        """.trimIndent(),
    )
    database.execSQL(
        """
        CREATE INDEX IF NOT EXISTS index_lens_reflections_day_key_dimension_id_reflection_type
        ON lens_reflections(day_key, dimension_id, reflection_type)
        """.trimIndent(),
    )
    database.execSQL(
        """
        CREATE INDEX IF NOT EXISTS index_lens_reflections_created_at
        ON lens_reflections(created_at)
        """.trimIndent(),
    )
}
