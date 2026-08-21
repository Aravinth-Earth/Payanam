//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

@file:Suppress("MagicNumber")

package io.payanam.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.payanam.common.logging.UnifiedLogger

/**
 * Migration from version 15 to 16.
 * - Adds day_plan_policies table for per-day mode/template/starred policy.
 * - Adds day_type_template_preferences table for auto-template mapping by day type.
 * - Backfills both tables from legacy app_settings keys.
 */
val MIGRATION_15_16 =
    object : Migration(15, 16) {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Performs the migrate.
         */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        override fun migrate(database: SupportSQLiteDatabase) {
            logger.i("Migration.15_16", "Starting migration from version 15 to 16")
            try {
                createDayPlanPoliciesTable(database)
                createDayTypeTemplatePreferencesTable(database)
                backfillDayPlanPoliciesFromLegacySettings(database)
                backfillDayTypeTemplatePreferencesFromLegacySettings(database)
                logger.i("Migration.15_16", "Migration from version 15 to 16 completed successfully")
            } catch (e: Exception) {
                logger.e("Migration.15_16", "Migration from version 15 to 16 failed", e)
                throw e
            }
        }
    }

private fun createDayPlanPoliciesTable(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS day_plan_policies (
            day_key TEXT NOT NULL PRIMARY KEY,
            mode TEXT NOT NULL DEFAULT 'auto' CHECK(mode IN ('auto','template','custom')),
            template_id TEXT,
            is_starred INTEGER NOT NULL DEFAULT 0 CHECK(is_starred IN (0,1)),
            updated_at TEXT NOT NULL,
            FOREIGN KEY(template_id) REFERENCES day_plan_templates(id) ON DELETE SET NULL
        )
        """.trimIndent(),
    )
    database.execSQL("CREATE INDEX IF NOT EXISTS index_day_plan_policies_template_id ON day_plan_policies(template_id)")
    database.execSQL("CREATE INDEX IF NOT EXISTS index_day_plan_policies_mode ON day_plan_policies(mode)")
    database.execSQL("CREATE INDEX IF NOT EXISTS index_day_plan_policies_is_starred ON day_plan_policies(is_starred)")
}

private fun createDayTypeTemplatePreferencesTable(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS day_type_template_preferences (
            day_type TEXT NOT NULL PRIMARY KEY CHECK(day_type IN ('weekday','weekend','starred')),
            template_id TEXT,
            updated_at TEXT NOT NULL,
            FOREIGN KEY(template_id) REFERENCES day_plan_templates(id) ON DELETE SET NULL
        )
        """.trimIndent(),
    )
    database.execSQL(
        "CREATE INDEX IF NOT EXISTS index_day_type_template_preferences_template_id ON day_type_template_preferences(template_id)",
    )
}

private fun backfillDayPlanPoliciesFromLegacySettings(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        INSERT OR REPLACE INTO day_plan_policies (
            day_key,
            mode,
            template_id,
            is_starred,
            updated_at
        )
        SELECT
            day_keys.day_key AS day_key,
            CASE lower(COALESCE(mode_setting.value, 'auto'))
                WHEN 'template' THEN 'template'
                WHEN 'custom' THEN 'custom'
                ELSE 'auto'
            END AS mode,
            template_setting.value AS template_id,
            CASE
                WHEN starred_setting.value = '1' THEN 1
                ELSE 0
            END AS is_starred,
            COALESCE(
                mode_setting.updatedAt,
                template_setting.updatedAt,
                starred_setting.updatedAt,
                strftime('%Y-%m-%dT%H:%M:%f','now')
            ) AS updated_at
        FROM (
            SELECT substr(`key`, 15) AS day_key
            FROM app_settings
            WHERE `key` LIKE 'day_plan_mode_%'
            UNION
            SELECT substr(`key`, 19) AS day_key
            FROM app_settings
            WHERE `key` LIKE 'day_plan_template_%'
            UNION
            SELECT substr(`key`, 18) AS day_key
            FROM app_settings
            WHERE `key` LIKE 'day_plan_starred_%'
        ) AS day_keys
        LEFT JOIN app_settings AS mode_setting
            ON mode_setting.`key` = ('day_plan_mode_' || day_keys.day_key)
        LEFT JOIN app_settings AS template_setting
            ON template_setting.`key` = ('day_plan_template_' || day_keys.day_key)
        LEFT JOIN app_settings AS starred_setting
            ON starred_setting.`key` = ('day_plan_starred_' || day_keys.day_key)
        """.trimIndent(),
    )
}

private fun backfillDayTypeTemplatePreferencesFromLegacySettings(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        INSERT OR REPLACE INTO day_type_template_preferences (
            day_type,
            template_id,
            updated_at
        )
        SELECT
            substr(`key`, 24) AS day_type,
            value AS template_id,
            COALESCE(updatedAt, strftime('%Y-%m-%dT%H:%M:%f','now')) AS updated_at
        FROM app_settings
        WHERE `key` LIKE 'day_plan_auto_template_%'
          AND substr(`key`, 24) IN ('weekday', 'weekend', 'starred')
        """.trimIndent(),
    )
}
