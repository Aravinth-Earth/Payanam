//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:max-line-length", "LongMethod", "MagicNumber", "UseCheckOrError")

package io.payanam.database.migration

import android.database.SQLException
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.payanam.common.logging.UnifiedLogger

/**
 * The ten life-dimension ids `seedDefaultLifeDimensions` (Migrations.kt) wrote on a database
 * created before the canonical taxonomy existed, each mapped to the canonical id from
 * `DimensionTaxonomyCatalog` that replaced it.
 *
 * `dim_unassigned` is deliberately absent: the canonical catalog defines the same id for it, so
 * there is nothing to rewrite and no row to delete.
 */
private val LEGACY_TO_CANONICAL: List<Pair<String, String>> =
    listOf(
        "dim_career_work" to "dim_work_livelihood",
        "dim_health_wellness" to "dim_physical_health",
        "dim_relationships" to "dim_family_relationships",
        "dim_personal_growth" to "dim_learning_growth",
        "dim_financial" to "dim_money_finance",
        "dim_spiritual" to "dim_mental_health",
        "dim_recreation" to "dim_recreation_leisure",
        "dim_learning" to "dim_learning_growth",
        "dim_contribution" to "dim_community_service",
    )

/** The `IN (...)` list every guarded statement filters on. */
private val LEGACY_ID_SQL: String = LEGACY_TO_CANONICAL.joinToString(", ") { "'${it.first}'" }

/** Prefix of the per-dimension auto-track setting key (`AppPreferenceKeys.KEY_AUTO_TRACK_DIMENSION_PREFIX`). */
private const val AUTO_TRACK_PREFIX = "auto_track_dimension_"

/**
 * Builds the searched CASE that maps [column]'s legacy values to their canonical ids.
 *
 * The COLUMN form (`CASE col WHEN …`) is required, not stylistic: the searched form
 * (`CASE WHEN 'literal' THEN …`) compares the literal to itself, is always false, and silently
 * rewrites zero rows. This is the exact shape `backfillDimensionIds` already uses above.
 */
private fun canonicalCase(column: String): String =
    "CASE $column " +
        LEGACY_TO_CANONICAL.joinToString(" ") { "WHEN '${it.first}' THEN '${it.second}'" } +
        " ELSE $column END"

/**
 * Rewrites the seeded legacy life-dimension ids to their canonical `DimensionTaxonomyCatalog`
 * equivalents, so a pre-taxonomy database can finally turn `PRAGMA foreign_keys` on.
 *
 * ## What this destroys, and why it is still done
 *
 * `dim_personal_growth` and `dim_learning` BOTH become `dim_learning_growth`. That merge is
 * destructive and irreversible: the user chose to track those as two separate dimensions, and
 * after this migration their tasks, time entries, day-plan allocations and journal answers are
 * indistinguishable. Nothing in the app reports that loss — no prompt, no backup, no log the user
 * reads. It happens silently, once, on upgrade.
 *
 * `dim_spiritual` becomes `dim_mental_health`, which is a RELABEL: prayer, meditation and
 * mindfulness entries stop being counted as spiritual practice and start being counted as mental
 * health. The user's intent survives; the label they chose to see does not.
 *
 * That is the cost of the canonical taxonomy, and it is written here rather than in a changelog
 * because the code is what a future reader will consult when asking why two dimensions became one.
 * The alternative — leaving legacy ids in place — is what makes `foreign_keys=ON` impossible, and
 * an app that cannot enforce its own foreign keys is a worse trade than a relabelled dimension.
 *
 * ## Why the order below is load-bearing
 *
 * A plain `UPDATE` on these tables aborts with a UNIQUE constraint failure, because two legacy ids
 * can map to the same canonical one and collide on a key that includes the dimension:
 * `dimension_metrics` PK(dimensionId, dayKey), `day_plan_allocations` UNIQUE(day_key, dimension_id),
 * `day_plan_template_allocations` UNIQUE(template_id, dimension_id), and `day_journal_responses`
 * UNIQUE(entryId, scope, dimensionKey, promptKey). Each of those is therefore collapsed with
 * INSERT-OR-IGNORE-then-DELETE, never a blind UPDATE.
 *
 * The canonical rows are seeded FIRST, before any rewrite. Room runs migrations with foreign key
 * enforcement on, and every carrier column REFERENCES life_dimensions(id) — rewriting a row to a
 * canonical id that has no row yet is an immediate `FOREIGN KEY constraint failed`.
 */
val MIGRATION_21_22 =
    object : Migration(21, 22) {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Rewrites every legacy dimension reference to its canonical id, collapsing the rows whose
         * target ids collide, and drops the legacy catalog rows that nothing points at any more.
         */
        override fun migrate(database: SupportSQLiteDatabase) {
            logger.i("Migration.21_22", "Rewriting legacy life-dimension ids to canonical ids")

            try {
                seedCanonicalDimensionRows(database)
                rewriteUnconstrainedCarriers(database)
                rewriteJournalResponses(database)
                collapseDimensionMetrics(database)
                collapseDayPlanAllocations(database)
                collapseDayPlanTemplateAllocations(database)
                rewriteAutoTrackSettingKeys(database)
                deleteOrphanedLegacyDimensionRows(database)
                verifyNoLegacyIdsRemain(database)

                logger.i("Migration.21_22", "Legacy life-dimension ids rewritten to canonical ids")
            } catch (e: SQLException) {
                logger.e(
                    "Migration.21_22",
                    "Migration failed",
                    e,
                    mapOf("error" to (e.message ?: "unknown")),
                )
                throw e
            } catch (e: IllegalStateException) {
                logger.e(
                    "Migration.21_22",
                    "Migration verification failed",
                    e,
                    mapOf("error" to (e.message ?: "unknown")),
                )
                throw e
            }
        }

        /**
         * Inserts the canonical catalog rows the rewrite is about to point at.
         *
         * INSERT OR IGNORE: a database that already ran the first-run dimension setup (or a
         * half-migrated file) has these rows with the user's own labels, colors and weights, and
         * overwriting them with the fallback values would discard that.
         */
        private fun seedCanonicalDimensionRows(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                INSERT OR IGNORE INTO life_dimensions
                (id, `key`, label, description, color, icon, sortOrder, isActive, weight, createdAt, updatedAt)
                VALUES
                    ('dim_physical_health', 'physical_health', 'Physical Health', 'Body health, exercise, rest, recovery and nutrition.', '#4CAF50', 'monitor_heart', 10, 1, 1.0, strftime('%Y-%m-%dT%H:%M:%f','now'), strftime('%Y-%m-%dT%H:%M:%f','now')),
                    ('dim_mental_health', 'mental_health', 'Mental Health', 'Emotional balance, mental wellness, reflection and calmness.', '#9C27B0', 'psychology', 20, 1, 1.0, strftime('%Y-%m-%dT%H:%M:%f','now'), strftime('%Y-%m-%dT%H:%M:%f','now')),
                    ('dim_family_relationships', 'family_relationships', 'Family & Relationships', 'Family bonds, close relationships, care and connection.', '#E91E63', 'groups', 30, 1, 1.0, strftime('%Y-%m-%dT%H:%M:%f','now'), strftime('%Y-%m-%dT%H:%M:%f','now')),
                    ('dim_home_environment', 'home_environment', 'Home & Environment', 'Home care, organization, maintenance, comfort and living-space improvement.', '#009688', 'home', 40, 1, 1.0, strftime('%Y-%m-%dT%H:%M:%f','now'), strftime('%Y-%m-%dT%H:%M:%f','now')),
                    ('dim_work_livelihood', 'work_livelihood', 'Work & Livelihood', 'Work, profession, responsibilities and daily stability.', '#3F51B5', 'work', 50, 1, 1.0, strftime('%Y-%m-%dT%H:%M:%f','now'), strftime('%Y-%m-%dT%H:%M:%f','now')),
                    ('dim_money_finance', 'money_finance', 'Money & Finance', 'Budgeting, saving, spending, investing and bills.', '#FF9800', 'account_balance_wallet', 60, 1, 1.0, strftime('%Y-%m-%dT%H:%M:%f','now'), strftime('%Y-%m-%dT%H:%M:%f','now')),
                    ('dim_learning_growth', 'learning_growth', 'Learning & Growth', 'Learning, skill-building, self-improvement and growth.', '#FFC107', 'menu_book', 70, 1, 1.0, strftime('%Y-%m-%dT%H:%M:%f','now'), strftime('%Y-%m-%dT%H:%M:%f','now')),
                    ('dim_recreation_leisure', 'recreation_leisure', 'Recreation & Leisure', 'Enjoyment, rest, hobbies, fun and renewal.', '#00BCD4', 'sports_esports', 80, 1, 1.0, strftime('%Y-%m-%dT%H:%M:%f','now'), strftime('%Y-%m-%dT%H:%M:%f','now')),
                    ('dim_community_service', 'community_service', 'Community & Service', 'Helping others, community participation and value beyond self.', '#607D8B', 'volunteer_activism', 90, 1, 1.0, strftime('%Y-%m-%dT%H:%M:%f','now'), strftime('%Y-%m-%dT%H:%M:%f','now'))
                """.trimIndent(),
            )
        }

        /**
         * Rewrites the carriers whose dimension column carries no uniqueness constraint, so a plain
         * UPDATE cannot collide.
         */
        private fun rewriteUnconstrainedCarriers(database: SupportSQLiteDatabase) {
            val case = canonicalCase("dimension_id")
            listOf(
                "tasks",
                "time_entries",
                "notes",
                "journal_notes",
                "daily_insights",
                "time_goals",
                "lens_reflections",
            ).forEach { table ->
                database.execSQL(
                    """
                    UPDATE $table
                    SET dimension_id = $case
                    WHERE dimension_id IN ($LEGACY_ID_SQL)
                    """.trimIndent(),
                )
            }
        }

        /**
         * Rewrites both the FK column and the non-FK `dimensionKey` carrier on journal responses.
         *
         * `dimensionKey` is part of UNIQUE(entryId, scope, dimensionKey, promptKey), so the rows
         * that would collide once the keys are rewritten are collapsed FIRST — keeping the most
         * recently updated one, which is the answer the user last gave. The survivor is a real row
         * with real text: no answer is synthesised or concatenated.
         */
        private fun rewriteJournalResponses(database: SupportSQLiteDatabase) {
            val ownKeyCase = canonicalCase("dimensionKey")
            val otherKeyCase = canonicalCase("r2.dimensionKey")
            database.execSQL(
                """
                DELETE FROM day_journal_responses
                WHERE dimensionKey IN ($LEGACY_ID_SQL)
                  AND EXISTS (
                      SELECT 1 FROM day_journal_responses r2
                      WHERE r2.entryId = day_journal_responses.entryId
                        AND r2.scope = day_journal_responses.scope
                        AND r2.promptKey = day_journal_responses.promptKey
                        AND r2.dimensionKey IN ($LEGACY_ID_SQL)
                        AND ($otherKeyCase) = ($ownKeyCase)
                        AND (
                            r2.updatedAt > day_journal_responses.updatedAt
                            OR (
                                r2.updatedAt = day_journal_responses.updatedAt
                                AND r2.id > day_journal_responses.id
                            )
                        )
                  )
                """.trimIndent(),
            )
            database.execSQL(
                """
                UPDATE day_journal_responses
                SET dimensionKey = $ownKeyCase
                WHERE dimensionKey IN ($LEGACY_ID_SQL)
                """.trimIndent(),
            )
            val case = canonicalCase("dimension_id")
            database.execSQL(
                """
                UPDATE day_journal_responses
                SET dimension_id = $case
                WHERE dimension_id IN ($LEGACY_ID_SQL)
                """.trimIndent(),
            )
        }

        /**
         * Collapses PK(dimensionId, dayKey): one canonical row per (canonical id, day), then the
         * legacy rows go. A pre-existing canonical row wins — INSERT OR IGNORE skips it — so a day
         * the user already had a canonical metric for keeps its value.
         */
        private fun collapseDimensionMetrics(database: SupportSQLiteDatabase) {
            val case = canonicalCase("dimensionId")
            database.execSQL(
                """
                INSERT OR IGNORE INTO dimension_metrics
                    (dimensionId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue)
                SELECT $case, dayKey, MAX(score), MAX(runningAvg), MAX(progress),
                       MAX(streakPos), MAX(streakNet), MAX(posContinue)
                FROM dimension_metrics
                WHERE dimensionId IN ($LEGACY_ID_SQL)
                GROUP BY $case, dayKey
                """.trimIndent(),
            )
            database.execSQL("DELETE FROM dimension_metrics WHERE dimensionId IN ($LEGACY_ID_SQL)")
        }

        /**
         * Collapses UNIQUE(day_key, dimension_id), summing planned minutes across the legacy rows
         * that share a canonical target.
         *
         * The id is SYNTHESIZED (`migrated_21_22_…`) rather than reused from a contributing row:
         * that row is still present at INSERT time, so reusing its primary key would be ignored by
         * INSERT OR IGNORE and its minutes would be silently dropped. Grouping is on
         * (canonical, day_key) — never on day_key alone, or two legacy ids on one day would be
         * summed into a dimension the user never allocated time to.
         */
        private fun collapseDayPlanAllocations(database: SupportSQLiteDatabase) {
            val case = canonicalCase("dimension_id")
            val otherCase = canonicalCase("y.dimension_id")
            database.execSQL(
                """
                INSERT OR IGNORE INTO day_plan_allocations
                    (id, day_key, dimension_id, planned_minutes, source, template_id, created_at, updated_at)
                SELECT 'migrated_21_22_' || x.day_key || '_' || ($case),
                       x.day_key,
                       $case,
                       SUM(x.planned_minutes),
                       (
                           SELECT y.source FROM day_plan_allocations y
                           WHERE y.day_key = x.day_key AND ($otherCase) = ($case)
                           ORDER BY y.updated_at DESC, y.id DESC LIMIT 1
                       ),
                       (
                           SELECT y.template_id FROM day_plan_allocations y
                           WHERE y.day_key = x.day_key AND ($otherCase) = ($case)
                           ORDER BY y.updated_at DESC, y.id DESC LIMIT 1
                       ),
                       MIN(x.created_at),
                       MAX(x.updated_at)
                FROM day_plan_allocations x
                WHERE x.dimension_id IN ($LEGACY_ID_SQL)
                GROUP BY x.day_key, $case
                """.trimIndent(),
            )
            database.execSQL("DELETE FROM day_plan_allocations WHERE dimension_id IN ($LEGACY_ID_SQL)")
        }

        /**
         * Collapses UNIQUE(template_id, dimension_id) the same way, summing planned minutes.
         */
        private fun collapseDayPlanTemplateAllocations(database: SupportSQLiteDatabase) {
            val case = canonicalCase("dimension_id")
            database.execSQL(
                """
                INSERT OR IGNORE INTO day_plan_template_allocations
                    (id, template_id, dimension_id, planned_minutes, created_at, updated_at)
                SELECT 'migrated_21_22_' || x.template_id || '_' || ($case),
                       x.template_id,
                       $case,
                       SUM(x.planned_minutes),
                       MIN(x.created_at),
                       MAX(x.updated_at)
                FROM day_plan_template_allocations x
                WHERE x.dimension_id IN ($LEGACY_ID_SQL)
                GROUP BY x.template_id, $case
                """.trimIndent(),
            )
            database.execSQL("DELETE FROM day_plan_template_allocations WHERE dimension_id IN ($LEGACY_ID_SQL)")
        }

        /**
         * Renames the per-dimension auto-track setting keys.
         *
         * `app_settings.key` is the PRIMARY KEY, so it cannot be UPDATEd — the row has to be
         * re-inserted under the new key. The INSERT therefore runs BEFORE the DELETE: deleting
         * first would destroy the very rows the INSERT is selecting from, losing the user's
         * per-dimension auto-tracking choices. Where two legacy keys collide on one canonical key
         * INSERT OR IGNORE keeps the first and the second is dropped, which is the same
         * last-writer-wins problem every other carrier has.
         */
        private fun rewriteAutoTrackSettingKeys(database: SupportSQLiteDatabase) {
            val suffixStart = AUTO_TRACK_PREFIX.length + 1
            val case =
                "CASE substr(a.`key`, $suffixStart) " +
                    LEGACY_TO_CANONICAL.joinToString(" ") { "WHEN '${it.first}' THEN '${it.second}'" } +
                    " ELSE substr(a.`key`, $suffixStart) END"
            database.execSQL(
                """
                INSERT OR IGNORE INTO app_settings (`key`, value, updatedAt)
                SELECT '$AUTO_TRACK_PREFIX' || ($case), a.value, a.updatedAt
                FROM app_settings a
                WHERE a.`key` LIKE '$AUTO_TRACK_PREFIX%'
                  AND replace(a.`key`, '$AUTO_TRACK_PREFIX', '') IN ($LEGACY_ID_SQL)
                """.trimIndent(),
            )
            database.execSQL(
                """
                DELETE FROM app_settings
                WHERE `key` LIKE '$AUTO_TRACK_PREFIX%'
                  AND replace(`key`, '$AUTO_TRACK_PREFIX', '') IN ($LEGACY_ID_SQL)
                """.trimIndent(),
            )
        }

        /**
         * Deletes the legacy catalog rows that nothing references any more.
         *
         * Guarded by NOT EXISTS across every referencing table so a row the user still has data
         * under is never removed — with `foreign_keys` on, deleting a parent row that children
         * point at would abort the whole migration.
         */
        private fun deleteOrphanedLegacyDimensionRows(database: SupportSQLiteDatabase) {
            val unreferenced =
                listOf(
                    "tasks" to "dimension_id",
                    "time_entries" to "dimension_id",
                    "notes" to "dimension_id",
                    "day_journal_responses" to "dimension_id",
                    "journal_notes" to "dimension_id",
                    "daily_insights" to "dimension_id",
                    "time_goals" to "dimension_id",
                    "day_plan_template_allocations" to "dimension_id",
                    "day_plan_allocations" to "dimension_id",
                    "lens_reflections" to "dimension_id",
                    "dimension_metrics" to "dimensionId",
                ).joinToString(" AND ") { (table, column) ->
                    "NOT EXISTS (SELECT 1 FROM $table WHERE $column = d.id)"
                }
            database.execSQL(
                """
                DELETE FROM life_dimensions AS d
                WHERE d.id IN ($LEGACY_ID_SQL)
                  AND $unreferenced
                """.trimIndent(),
            )
        }

        /**
         * Fails the migration if any legacy id survived in any rewritten column.
         *
         * A silent partial rewrite is the failure this guards: the app would go on treating
         * unrecognised ids as "no dimension", and the user's data would look uncategorised with
         * nothing in a log to explain it.
         */
        private fun verifyNoLegacyIdsRemain(database: SupportSQLiteDatabase) {
            val offenders =
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
                ).mapNotNull { (table, column) ->
                    val remaining =
                        database.query(
                            "SELECT COUNT(*) FROM $table WHERE $column IN ($LEGACY_ID_SQL)",
                        ).use { cursor ->
                            var count = 0L
                            if (cursor.moveToFirst()) count = cursor.getLong(0)
                            count
                        }
                    if (remaining > 0L) "$table.$column=$remaining" else null
                }
            if (offenders.isNotEmpty()) {
                throw IllegalStateException(
                    "Migration 21→22 left legacy dimension ids behind: ${offenders.joinToString()}",
                )
            }
        }
    }
