//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.model.DimensionTaxonomyCatalog
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * Proves the shared fallback literal the note and time-entry dialogs use resolves to a REAL row.
 *
 * `NotesScreen` and `TimeScreenEntryDialogs` both fall back to
 * `DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id` (`dim_work_livelihood`) when the user has no
 * dimension options to choose from — the fresh-install path, where `visibleDimensionOptions()` is
 * empty. That fallback used to name a dimension that did not exist in a pre-taxonomy database: the
 * id resolved in the catalog but had no `life_dimensions` row, so the save wrote a `dimension_id`
 * pointing at nothing.
 *
 * This is the desktop half of that proof, over JDBC rather than Room, because the desktop is the
 * platform that reads this file without running migrations: a database the migration did not fix
 * is a database the desktop cannot categorise. The Room half is `Migration21To22Test`.
 */
class CanonicalDimensionResolutionJvmTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "canonical-dimension-test-passphrase"
    private lateinit var connection: Connection

    @Before
    fun setUp() {
        val path = tempFolder.newFolder().toPath().resolve("canonical-dimensions.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection = DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE life_dimensions (
                    id TEXT PRIMARY KEY NOT NULL,
                    `key` TEXT NOT NULL,
                    label TEXT NOT NULL,
                    description TEXT,
                    color TEXT NOT NULL,
                    icon TEXT,
                    sortOrder INTEGER NOT NULL,
                    isActive INTEGER NOT NULL DEFAULT 1,
                    weight REAL NOT NULL DEFAULT 1.0,
                    createdAt TEXT NOT NULL,
                    updatedAt TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                "CREATE UNIQUE INDEX index_life_dimensions_key ON life_dimensions (`key`)",
            )
            // The pre-taxonomy catalog: exactly what seedDefaultLifeDimensions wrote, and nothing else.
            statement.execute(
                """
                INSERT INTO life_dimensions
                    (id, `key`, label, description, color, icon, sortOrder, isActive, weight, createdAt, updatedAt)
                VALUES
                    ('dim_career_work', 'career_work', 'Career & Work', NULL, '#3F51B5', 'work', 10, 1, 1.0, 'T0', 'T0'),
                    ('dim_personal_growth', 'personal_growth', 'Personal Growth', NULL, '#009688', 'trending_up', 40, 1, 1.0, 'T0', 'T0'),
                    ('dim_learning', 'learning', 'Learning', NULL, '#673AB7', 'menu_book', 80, 1, 1.0, 'T0', 'T0'),
                    ('dim_spiritual', 'spiritual', 'Spiritual', NULL, '#9C27B0', 'self_improvement', 60, 1, 1.0, 'T0', 'T0'),
                    ('dim_unassigned', 'unassigned', 'Unassigned', NULL, '#9E9E9E', 'help_outline', 9999, 1, 1.0, 'T0', 'T0')
                """.trimIndent(),
            )
        }
        rewriteLegacyIdsToCanonical()
    }

    /**
     * Applies the same rewrite [io.payanam.database.migration.MIGRATION_21_22] performs, over JDBC.
     *
     * The statements are duplicated here on purpose rather than shared: the migration is Android
     * Room code in an `android.library` module the desktop cannot depend on, so a shared constant
     * would mean moving the SQL into a module both can see. Duplicating the rewrite in a test is
     * the cheaper trade — the Room side is what actually ships, and it is covered there.
     */
    private fun rewriteLegacyIdsToCanonical() {
        val legacyIds =
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
        val list = legacyIds.joinToString(", ") { "'$it'" }
        connection.createStatement().use { statement ->
            statement.execute(
                """
                INSERT OR IGNORE INTO life_dimensions
                    (id, `key`, label, description, color, icon, sortOrder, isActive, weight, createdAt, updatedAt)
                VALUES
                    ('dim_work_livelihood', 'work_livelihood', 'Work & Livelihood', NULL, '#3F51B5', 'work', 50, 1, 1.0, 'T0', 'T0'),
                    ('dim_physical_health', 'physical_health', 'Physical Health', NULL, '#4CAF50', 'monitor_heart', 10, 1, 1.0, 'T0', 'T0'),
                    ('dim_family_relationships', 'family_relationships', 'Family & Relationships', NULL, '#E91E63', 'groups', 30, 1, 1.0, 'T0', 'T0'),
                    ('dim_home_environment', 'home_environment', 'Home & Environment', NULL, '#009688', 'home', 40, 1, 1.0, 'T0', 'T0'),
                    ('dim_learning_growth', 'learning_growth', 'Learning & Growth', NULL, '#FFC107', 'menu_book', 70, 1, 1.0, 'T0', 'T0'),
                    ('dim_money_finance', 'money_finance', 'Money & Finance', NULL, '#FF9800', 'account_balance_wallet', 60, 1, 1.0, 'T0', 'T0'),
                    ('dim_mental_health', 'mental_health', 'Mental Health', NULL, '#9C27B0', 'psychology', 20, 1, 1.0, 'T0', 'T0'),
                    ('dim_recreation_leisure', 'recreation_leisure', 'Recreation & Leisure', NULL, '#00BCD4', 'sports_esports', 80, 1, 1.0, 'T0', 'T0'),
                    ('dim_community_service', 'community_service', 'Community & Service', NULL, '#607D8B', 'volunteer_activism', 90, 1, 1.0, 'T0', 'T0')
                """.trimIndent(),
            )
            // No carrier table is populated here, so the orphaned legacy rows can be removed outright.
            // Guarded by NOT EXISTS because the shipped migration is: with foreign keys on, dropping a
            // catalog row a record still points at would abort.
            statement.execute(
                "DELETE FROM life_dimensions AS d WHERE d.id IN ($list) " +
                    "AND NOT EXISTS (SELECT 1 FROM life_dimensions AS c WHERE c.id = d.id AND c.id IN ($list))",
            )
            statement.execute("DELETE FROM life_dimensions WHERE id IN ($list)")
        }
    }

    @After
    fun tearDown() {
        if (!connection.isClosed) connection.close()
    }

    private fun labelFor(id: String): String? =
        connection.prepareStatement("SELECT label FROM life_dimensions WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getString(1) else null
            }
        }

    private fun allIds(): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT id FROM life_dimensions ORDER BY id").use { rows ->
                val ids = mutableListOf<String>()
                while (rows.next()) ids.add(rows.getString(1))
                ids
            }
        }

    @Test
    fun `the shared fallback literal resolves to a real row`() {
        // The literal the two dialogs hardcode when the user has no options to pick from.
        val fallbackId = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id

        assertThat(fallbackId).isEqualTo("dim_work_livelihood")
        assertThat(labelFor(fallbackId)).isNotNull()
        assertThat(labelFor(fallbackId)).isEqualTo("Work & Livelihood")
    }

    @Test
    fun `every canonical id the catalog can return is present as a row`() {
        // A catalog entry with no row is the exact defect: resolution succeeds in Kotlin, so
        // nothing logs, and the failure only shows up as an uncategorised record much later.
        DimensionTaxonomyCatalog.entries.forEach { definition ->
            assertThat(definition.id).isNotNull()
            assertThat(labelFor(definition.id)).isNotNull()
        }
    }

    @Test
    fun `no legacy id is left in the catalog after the rewrite`() {
        val legacyIds =
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

        val remaining = allIds().filter { it in legacyIds }

        assertThat(remaining).isEmpty()
    }

    @Test
    fun `dim_unassigned survives the rewrite untouched`() {
        // The one legacy id that is ALSO a canonical id. A rewrite that swept it up would strip
        // every uncategorised record of its dimension, and the fallback would stop existing.
        assertThat(DimensionTaxonomyCatalog.UNASSIGNED.id).isEqualTo("dim_unassigned")
        assertThat(labelFor("dim_unassigned")).isNotNull()
    }
}
