//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

@file:Suppress("MagicNumber")

package io.payanam.database.migration

import android.database.SQLException
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.payanam.common.logging.UnifiedLogger

/**
 * Migration from version 13 to 14.
 * - Adds day_plan_templates table for reusable day plan presets.
 * - Adds day_plan_template_allocations table for template dimension entries.
 * - Adds day_plan_allocations table for per-day planned minutes per dimension.
 */
val MIGRATION_13_14 =
    object : Migration(13, 14) {
        private val logger = UnifiedLogger.getInstance()

        override fun migrate(database: SupportSQLiteDatabase) {
            logger.i("Migration.13_14", "Starting migration from version 13 to 14")
            try {
                createDayPlanTemplatesTable(database)
                createDayPlanTemplateAllocationsTable(database)
                createDayPlanAllocationsTable(database)
                logger.i("Migration.13_14", "Migration from 13 to 14 completed successfully")
            } catch (e: SQLException) {
                logger.e("Migration.13_14", "Migration from 13 to 14 failed", e)
                throw e
            }
        }
    }

private fun createDayPlanTemplatesTable(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS day_plan_templates (
            id TEXT PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            description TEXT,
            is_active INTEGER NOT NULL DEFAULT 1,
            sort_order INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
        """.trimIndent(),
    )
    database.execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS index_day_plan_templates_name
        ON day_plan_templates(name)
        """.trimIndent(),
    )
    database.execSQL(
        """
        CREATE INDEX IF NOT EXISTS index_day_plan_templates_is_active
        ON day_plan_templates(is_active)
        """.trimIndent(),
    )
}

private fun createDayPlanTemplateAllocationsTable(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS day_plan_template_allocations (
            id TEXT PRIMARY KEY NOT NULL,
            template_id TEXT NOT NULL,
            dimension_id TEXT NOT NULL,
            planned_minutes INTEGER NOT NULL,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            FOREIGN KEY (template_id) REFERENCES day_plan_templates(id) ON DELETE CASCADE,
            FOREIGN KEY (dimension_id) REFERENCES life_dimensions(id) ON DELETE NO ACTION
        )
        """.trimIndent(),
    )
    database.execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS index_day_plan_template_allocations_template_id_dimension_id
        ON day_plan_template_allocations(template_id, dimension_id)
        """.trimIndent(),
    )
    database.execSQL(
        """
        CREATE INDEX IF NOT EXISTS index_day_plan_template_allocations_template_id
        ON day_plan_template_allocations(template_id)
        """.trimIndent(),
    )
    database.execSQL(
        """
        CREATE INDEX IF NOT EXISTS index_day_plan_template_allocations_dimension_id
        ON day_plan_template_allocations(dimension_id)
        """.trimIndent(),
    )
}

private fun createDayPlanAllocationsTable(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS day_plan_allocations (
            id TEXT PRIMARY KEY NOT NULL,
            day_key TEXT NOT NULL,
            dimension_id TEXT NOT NULL,
            planned_minutes INTEGER NOT NULL,
            source TEXT NOT NULL DEFAULT 'manual',
            template_id TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            FOREIGN KEY (dimension_id) REFERENCES life_dimensions(id) ON DELETE NO ACTION,
            FOREIGN KEY (template_id) REFERENCES day_plan_templates(id) ON DELETE SET NULL
        )
        """.trimIndent(),
    )
    database.execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS index_day_plan_allocations_day_key_dimension_id
        ON day_plan_allocations(day_key, dimension_id)
        """.trimIndent(),
    )
    database.execSQL(
        """
        CREATE INDEX IF NOT EXISTS index_day_plan_allocations_day_key
        ON day_plan_allocations(day_key)
        """.trimIndent(),
    )
    database.execSQL(
        """
        CREATE INDEX IF NOT EXISTS index_day_plan_allocations_dimension_id
        ON day_plan_allocations(dimension_id)
        """.trimIndent(),
    )
    database.execSQL(
        """
        CREATE INDEX IF NOT EXISTS index_day_plan_allocations_template_id
        ON day_plan_allocations(template_id)
        """.trimIndent(),
    )
}
