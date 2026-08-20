//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

@file:Suppress("MagicNumber")

package io.payanam.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.payanam.common.logging.UnifiedLogger

/**
 * Migration from version 11 to 12.
 * - Adds tag tables and mapping tables (tasks/notes/time entries).
 * - Adds time_goals and time_rules baseline tables.
 */
val MIGRATION_11_12 =
    object : Migration(11, 12) {
        private val logger = UnifiedLogger.getInstance()

        override fun migrate(database: SupportSQLiteDatabase) {
            logger.i("Migration.11_12", "Starting migration from version 11 to 12")
            try {
                /** Create tags table. */
                createTagsTable(database)
                /** Create task tags table. */
                createTaskTagsTable(database)
                /** Create note tags table. */
                createNoteTagsTable(database)
                /** Create time entry tags table. */
                createTimeEntryTagsTable(database)
                /** Create time goals table. */
                createTimeGoalsTable(database)
                /** Create time rules table. */
                createTimeRulesTable(database)
                logger.i("Migration.11_12", "Migration from 11 to 12 completed successfully")
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("Migration.11_12", "Migration from 11 to 12 failed", e)
                throw e
            }
        }
    }

private fun createTagsTable(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS tags (
            id TEXT NOT NULL PRIMARY KEY,
            name TEXT NOT NULL,
            normalized_name TEXT NOT NULL,
            usage_count INTEGER NOT NULL DEFAULT 0,
            last_used_at TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
        """.trimIndent(),
    )
    database.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS index_tags_normalized_name ON tags(normalized_name)",
    )
    database.execSQL(
        "CREATE INDEX IF NOT EXISTS index_tags_name ON tags(name)",
    )
    database.execSQL(
        "CREATE INDEX IF NOT EXISTS index_tags_last_used_at ON tags(last_used_at)",
    )
}

private fun createTaskTagsTable(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS task_tags (
            task_id TEXT NOT NULL,
            tag_id TEXT NOT NULL,
            created_at TEXT NOT NULL,
            PRIMARY KEY(task_id, tag_id),
            FOREIGN KEY(task_id) REFERENCES tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(tag_id) REFERENCES tags(id) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    database.execSQL("CREATE INDEX IF NOT EXISTS index_task_tags_task_id ON task_tags(task_id)")
    database.execSQL("CREATE INDEX IF NOT EXISTS index_task_tags_tag_id ON task_tags(tag_id)")
}

private fun createNoteTagsTable(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS note_tags (
            note_id TEXT NOT NULL,
            tag_id TEXT NOT NULL,
            created_at TEXT NOT NULL,
            PRIMARY KEY(note_id, tag_id),
            FOREIGN KEY(note_id) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(tag_id) REFERENCES tags(id) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    database.execSQL("CREATE INDEX IF NOT EXISTS index_note_tags_note_id ON note_tags(note_id)")
    database.execSQL("CREATE INDEX IF NOT EXISTS index_note_tags_tag_id ON note_tags(tag_id)")
}

private fun createTimeEntryTagsTable(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS time_entry_tags (
            time_entry_id TEXT NOT NULL,
            tag_id TEXT NOT NULL,
            created_at TEXT NOT NULL,
            PRIMARY KEY(time_entry_id, tag_id),
            FOREIGN KEY(time_entry_id) REFERENCES time_entries(id) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(tag_id) REFERENCES tags(id) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    database.execSQL(
        "CREATE INDEX IF NOT EXISTS index_time_entry_tags_time_entry_id ON time_entry_tags(time_entry_id)",
    )
    database.execSQL("CREATE INDEX IF NOT EXISTS index_time_entry_tags_tag_id ON time_entry_tags(tag_id)")
}

private fun createTimeGoalsTable(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS time_goals (
            id TEXT NOT NULL PRIMARY KEY,
            name TEXT NOT NULL,
            dimension_id TEXT,
            target_minutes INTEGER NOT NULL,
            period TEXT NOT NULL,
            is_active INTEGER NOT NULL DEFAULT 1,
            notes TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            FOREIGN KEY(dimension_id) REFERENCES life_dimensions(id) ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
    )
    database.execSQL("CREATE INDEX IF NOT EXISTS index_time_goals_dimension_id ON time_goals(dimension_id)")
    database.execSQL("CREATE INDEX IF NOT EXISTS index_time_goals_period ON time_goals(period)")
    database.execSQL("CREATE INDEX IF NOT EXISTS index_time_goals_is_active ON time_goals(is_active)")
}

private fun createTimeRulesTable(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS time_rules (
            id TEXT NOT NULL PRIMARY KEY,
            name TEXT NOT NULL,
            rule_type TEXT NOT NULL,
            config_json TEXT,
            is_active INTEGER NOT NULL DEFAULT 1,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
        """.trimIndent(),
    )
    database.execSQL("CREATE INDEX IF NOT EXISTS index_time_rules_rule_type ON time_rules(rule_type)")
    database.execSQL("CREATE INDEX IF NOT EXISTS index_time_rules_is_active ON time_rules(is_active)")
}
