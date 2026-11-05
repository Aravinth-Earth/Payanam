//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.migration

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.payanam.database.PayanamDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that [MIGRATION_21_22] leaves no legacy life-dimension id anywhere, and that the
 * canonical catalog the rest of the app resolves against actually exists afterwards.
 *
 * The whole point of the migration is to unblock `PRAGMA foreign_keys = ON` on the desktop, so a
 * migration that rewrote most carriers but left one behind would still leave the database full of
 * references the code cannot resolve — and `DimensionTaxonomyCatalog.fromAnyId` would log a
 * warning per row rather than fail. That is why the assertion is per-column and exhaustive rather
 * than "the tasks table is clean".
 */
@RunWith(AndroidJUnit4::class)
class Migration21To22Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            PayanamDatabase::class.java,
        )

    private val legacyIds =
        listOf(
            "dim_career_work",
            "dim_health_wellness",
            "dim_relationships",
            "dim_personal_growth",
            "dim_financial",
            "dim_spiritual",
            "dim_recreation",
            "dim_learning",
            "dim_contribution",
        )

    private fun legacyIdList() = legacyIds.joinToString(", ") { "'$it'" }

    private fun SupportSQLiteDatabase.idsIn(
        table: String,
        column: String,
    ): List<String> =
        query("SELECT DISTINCT $column FROM $table WHERE $column IN (${legacyIdList()})").use { cursor ->
            val found = mutableListOf<String>()
            while (cursor.moveToNext()) found.add(cursor.getString(0))
            found
        }

    private fun SupportSQLiteDatabase.countRows(
        table: String,
        where: String,
    ): Long =
        query("SELECT COUNT(*) FROM $table WHERE $where").use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    private fun SupportSQLiteDatabase.dimensionExists(id: String): Boolean =
        countRows("life_dimensions", "id = '$id'") > 0L

    @Test
    fun legacy_dimension_ids_are_rewritten_in_every_dimension_column() {
        val db = helper.createDatabase(TEST_DB, 21)
        seedLegacyDimensions(db)
        seedLegacyCarriers(db)

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 22, true, MIGRATION_21_22)
        helper.closeWhenFinished(migrated)

        // Every column that can hold a dimension id, asserted one by one. A column that silently
        // kept its legacy values would still satisfy a "did the tasks table change" assertion.
        val carriers =
            listOf(
                "tasks" to "dimension_id",
                "time_entries" to "dimension_id",
                "notes" to "dimension_id",
                "day_journal_responses" to "dimension_id",
                "day_journal_responses" to "dimensionKey",
                "journal_notes" to "dimension_id",
                "daily_insights" to "dimension_id",
                "time_goals" to "dimension_id",
                "day_plan_template_allocations" to "dimension_id",
                "day_plan_allocations" to "dimension_id",
                "lens_reflections" to "dimension_id",
                "dimension_metrics" to "dimensionId",
            )
        carriers.forEach { (table, column) ->
            assertThat(migrated.idsIn(table, column)).isEmpty()
        }

        // The catalog the app resolves against must now be real rows, not just renamed references.
        assertThat(migrated.dimensionExists("dim_work_livelihood")).isTrue()
        assertThat(migrated.dimensionExists("dim_learning_growth")).isTrue()
        assertThat(migrated.dimensionExists("dim_mental_health")).isTrue()
        assertThat(migrated.dimensionExists("dim_community_service")).isTrue()

        // And the legacy catalog rows are gone, so a reader cannot resolve a legacy id at all.
        legacyIds.forEach { legacy ->
            assertThat(migrated.dimensionExists(legacy)).isFalse()
        }
    }

    @Test
    fun merged_planned_minutes_are_summed_not_dropped() {
        val db = helper.createDatabase(TEST_DB, 21)
        seedLegacyDimensions(db)
        seedLegacyCarriers(db)

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 22, true, MIGRATION_21_22)
        helper.closeWhenFinished(migrated)

        // dim_personal_growth (30) and dim_learning (60) both become dim_learning_growth. The
        // collapse must ADD them, not keep one and discard the other: a silent 30-minute loss
        // reads as "the plan was always 60", which the user cannot detect.
        val minutes =
            migrated.query(
                "SELECT planned_minutes FROM day_plan_allocations WHERE day_key = '2026-01-01' " +
                    "AND dimension_id = 'dim_learning_growth'",
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else -1
            }
        assertThat(minutes).isEqualTo(90)

        // The template collapse is asserted the same way (30 + 20).
        val templateMinutes =
            migrated.query(
                "SELECT planned_minutes FROM day_plan_template_allocations WHERE template_id = 'tpl-1' " +
                    "AND dimension_id = 'dim_learning_growth'",
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else -1
            }
        assertThat(templateMinutes).isEqualTo(50)
    }

    @Test
    fun per_dimension_auto_track_setting_is_renamed_and_kept() {
        val db = helper.createDatabase(TEST_DB, 21)
        seedLegacyDimensions(db)
        seedLegacyCarriers(db)

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 22, true, MIGRATION_21_22)
        helper.closeWhenFinished(migrated)

        // app_settings.key is the PRIMARY KEY, so the row had to be re-inserted under the new
        // key. If the DELETE ran before the INSERT the setting would simply vanish and the
        // dimension would silently fall back to the global auto-track flag.
        val value =
            migrated.query(
                "SELECT value FROM app_settings WHERE `key` = 'auto_track_dimension_dim_mental_health'",
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        assertThat(value).isEqualTo("true")
        assertThat(
            migrated.countRows("app_settings", "`key` LIKE 'auto_track_dimension_dim_spiritual'"),
        ).isEqualTo(0L)
    }

    @Test
    fun running_the_migration_twice_changes_nothing() {
        val db = helper.createDatabase(TEST_DB, 21)
        seedLegacyDimensions(db)
        seedLegacyCarriers(db)

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 22, true, MIGRATION_21_22)
        helper.closeWhenFinished(migrated)

        val before = tableFingerprint(migrated)
        MIGRATION_21_22.migrate(migrated)
        assertThat(tableFingerprint(migrated)).isEqualTo(before)
    }

    /**
     * A whole-database content fingerprint, so the idempotence assertion compares actual row
     * contents rather than "the migration did not throw".
     */
    private fun tableFingerprint(db: SupportSQLiteDatabase): Map<String, List<String>> =
        listOf(
            "tasks" to "id",
            "notes" to "id",
            "dimension_metrics" to "dimensionId || '|' || dayKey",
            "day_plan_allocations" to "day_key || '|' || dimension_id",
            "day_plan_template_allocations" to "template_id || '|' || dimension_id",
            "day_journal_responses" to "id",
            "app_settings" to "`key`",
            "life_dimensions" to "id",
        ).associate { (table, key) ->
            table to
                db.query("SELECT * FROM $table ORDER BY $key").use { cursor ->
                    val rows = mutableListOf<String>()
                    val count = cursor.count
                    while (cursor.moveToNext()) {
                        rows.add(
                            (0 until count).joinToString("|") { cursor.getString(it) ?: "" },
                        )
                    }
                    rows
                }
        }

    /** The pre-taxonomy catalog, exactly as `seedDefaultLifeDimensions` wrote it. */
    private fun seedLegacyDimensions(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT OR IGNORE INTO life_dimensions
                (id, `key`, label, description, color, icon, sortOrder, isActive, createdAt, updatedAt)
            VALUES
                ('dim_career_work', 'career_work', 'Career & Work', 'Professional development', '#3F51B5', 'work', 10, 1, NOW, NOW),
                ('dim_health_wellness', 'health_wellness', 'Health & Wellness', 'Physical and mental health', '#4CAF50', 'favorite', 20, 1, NOW, NOW),
                ('dim_relationships', 'relationships', 'Relationships', 'Family and friends', '#E91E63', 'people', 30, 1, NOW, NOW),
                ('dim_personal_growth', 'personal_growth', 'Personal Growth', 'Self-improvement', '#009688', 'trending_up', 40, 1, NOW, NOW),
                ('dim_financial', 'financial', 'Financial', 'Financial planning', '#FF9800', 'account_balance_wallet', 50, 1, NOW, NOW),
                ('dim_spiritual', 'spiritual', 'Spiritual', 'Spiritual practices', '#9C27B0', 'self_improvement', 60, 1, NOW, NOW),
                ('dim_recreation', 'recreation', 'Recreation', 'Hobbies and leisure', '#00BCD4', 'sports_esports', 70, 1, NOW, NOW),
                ('dim_learning', 'learning', 'Learning', 'Education and skills', '#673AB7', 'menu_book', 80, 1, NOW, NOW),
                ('dim_contribution', 'contribution', 'Contribution', 'Giving back', '#8BC34A', 'volunteer_activism', 90, 1, NOW, NOW),
                ('dim_unassigned', 'unassigned', 'Unassigned', 'System fallback', '#9E9E9E', 'help_outline', 9999, 1, NOW, NOW)
            """.trimIndent().replace("NOW", "'2026-01-01T00:00:00'"),
        )
    }

    /** One row per carrier, all on legacy ids, chosen to exercise every collapse path. */
    private fun seedLegacyCarriers(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO day_journal_entries (id, entryDate, createdAt, updatedAt) " +
                "VALUES ('entry-1', '2026-01-01', '2026-01-01T00:00:00', '2026-01-01T00:00:00')",
        )
        db.execSQL(
            "INSERT INTO day_plan_templates (id, name, is_active, sort_order, created_at, updated_at) " +
                "VALUES ('tpl-1', 'Morning', 1, 0, '2026-01-01T00:00:00', '2026-01-01T00:00:00')",
        )
        db.execSQL(
            "INSERT INTO tasks (id, title, status, createdAt, updatedAt, recurrenceEnabled, " +
                "durationMinutes, impactLevel, goalAlignment, energyLevel, controlLevel, " +
                "lifeIntentionCategory, dimension_id, dayBoundaryHour) " +
                "VALUES ('task-1', 'Ship it', 'PENDING', '2026-01-01T00:00:00', '2026-01-01T00:00:00', 0, " +
                "30, 'High', 'Strong', 'High', 'Full', 'Spiritual', 'dim_spiritual', 4)",
        )
        db.execSQL(
            "INSERT INTO notes (id, title, lifeIntentionCategory, dimension_id, createdAt, updatedAt) " +
                "VALUES ('note-1', 'Note', 'Career & Work', 'dim_career_work', " +
                "'2026-01-01T00:00:00', '2026-01-01T00:00:00')",
        )
        // personal_growth + learning on the SAME day/template: the merge that collides on both
        // unique constraints, so a regression that reverted to a plain UPDATE fails here.
        db.execSQL(
            "INSERT INTO dimension_metrics VALUES ('dim_personal_growth', '2026-01-01', 0.2, 0.2, 0.2, 1, 0, 0)",
        )
        db.execSQL(
            "INSERT INTO dimension_metrics VALUES ('dim_learning', '2026-01-01', 0.5, 0.5, 0.5, 2, 0, 0)",
        )
        db.execSQL(
            "INSERT INTO day_plan_allocations (id, day_key, dimension_id, planned_minutes, source, " +
                "template_id, created_at, updated_at) VALUES " +
                "('alloc-1', '2026-01-01', 'dim_personal_growth', 30, 'template', 'tpl-1', " +
                "'2026-01-01T00:00:00', '2026-01-01T10:00:00'), " +
                "('alloc-2', '2026-01-01', 'dim_learning', 60, 'manual', NULL, " +
                "'2026-01-01T00:00:00', '2026-01-02T10:00:00')",
        )
        db.execSQL(
            "INSERT INTO day_plan_template_allocations (id, template_id, dimension_id, " +
                "planned_minutes, created_at, updated_at) VALUES " +
                "('ta-1', 'tpl-1', 'dim_personal_growth', 30, '2026-01-01T00:00:00', '2026-01-01T00:00:00'), " +
                "('ta-2', 'tpl-1', 'dim_learning', 20, '2026-01-01T00:00:00', '2026-01-01T00:00:00')",
        )
        // Two responses collapsing onto one (entryId, scope, dimensionKey, promptKey).
        db.execSQL(
            "INSERT INTO day_journal_responses (id, entryId, scope, dimensionKey, dimension_id, " +
                "promptKey, responseText, createdAt, updatedAt) VALUES " +
                "('resp-1', 'entry-1', 'dimension', 'dim_personal_growth', 'dim_personal_growth', 'p1', " +
                "'growth answer', '2026-01-01T00:00:00', '2026-01-01T00:00:00'), " +
                "('resp-2', 'entry-1', 'dimension', 'dim_learning', 'dim_learning', 'p1', " +
                "'learning answer', '2026-01-01T00:00:00', '2026-01-02T00:00:00')",
        )
        db.execSQL(
            "INSERT INTO app_settings (`key`, value, updatedAt) VALUES " +
                "('auto_track_dimension_dim_spiritual', 'true', '2026-01-01T00:00:00'), " +
                "('auto_track_habit_time_global', 'true', '2026-01-01T00:00:00')",
        )
    }

    private companion object {
        const val TEST_DB = "migration-21-22-test"
    }
}
