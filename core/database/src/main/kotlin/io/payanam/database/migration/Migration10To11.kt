//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

@file:Suppress("MagicNumber")

package io.payanam.database.migration

import android.database.SQLException
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.payanam.common.logging.UnifiedLogger

/**
 * Migration from version 10 to 11.
 * - Adds task_occurrences.statusReason to persist structured skip/miss reasons.
 */
val MIGRATION_10_11 =
    object : Migration(10, 11) {
        private val logger = UnifiedLogger.getInstance()

        override fun migrate(database: SupportSQLiteDatabase) {
            logger.i("Migration.10_11", "Starting migration from version 10 to 11")

            try {
                database.execSQL("ALTER TABLE task_occurrences ADD COLUMN statusReason TEXT")
                logger.d("Migration.10_11", "Added task_occurrences.statusReason column")
                logger.i("Migration.10_11", "Migration from 10 to 11 completed successfully")
            } catch (e: SQLException) {
                logger.e("Migration.10_11", "Migration from 10 to 11 failed", e)
                throw e
            }
        }
    }
