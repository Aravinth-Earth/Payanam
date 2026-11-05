//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.model.ConfiguredLifeDimension
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * Tests for [LifeDimensionCatalogRepositoryJvm].
 *
 * Each update is asserted at the COLUMN level as well as through the repository: every one of them writes
 * `updatedAt` alongside its own field, and a port that set only the field would leave the row's modification
 * time claiming nothing changed.
 *
 * A generated SQLCipher fixture in a per-class temp directory, never a real export.
 */
class LifeDimensionCatalogRepositoryJvmTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "life-dimension-test-passphrase"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

    @Before
    fun setUp() {
        val path = tempFolder.newFolder().toPath().resolve("dimensions.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection =
            DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
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
            // The production table declares `key` UNIQUE (LifeDimensionEntity's @Index). The fixture did
            // not, which left no way to make an insert fail - and an insert that cannot fail proves
            // nothing about whether a failed replace rolls back.
            statement.execute("CREATE UNIQUE INDEX idx_life_dimensions_key ON life_dimensions (`key`)")
            statement.execute(
                """
                INSERT INTO life_dimensions
                    (id, `key`, label, description, color, icon, sortOrder, isActive, weight, createdAt, updatedAt)
                VALUES
                    ('dim_work_livelihood', 'work', 'Work & Livelihood', NULL, '#111111', 'work', 2, 1, 2.0, 'T0', 'T0'),
                    ('dim_physical_health', 'health', 'Physical Health', NULL, '#222222', 'health', 1, 1, 1.0, 'T0', 'T0')
                """.trimIndent(),
            )
        }
        session = PayanamDatabaseSession(connection, 21, DatabaseOpenMode.READ_WRITE)
    }

    @After
    fun tearDown() {
        session.close()
    }

    private fun repository() = LifeDimensionCatalogRepositoryJvm(session, pollIntervalMs = 10L)

    private fun raw(
        id: String,
        column: String,
    ): Any? =
        connection.prepareStatement("SELECT $column FROM life_dimensions WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.getObject(1) else null
            }
        }

    @Test
    fun `dimensions are ordered by sort order then id`(): Unit =
        runBlocking {
            val dimensions = repository().observeAllDimensions().first()

            assertThat(dimensions.map { it.id })
                .containsExactly("dim_physical_health", "dim_work_livelihood")
                .inOrder()
        }

    @Test
    fun `the mapper exposes color, icon, active flag and weight under their domain names`(): Unit =
        runBlocking {
            val work = repository().observeAllDimensions().first().first { it.id == "dim_work_livelihood" }

            assertThat(work.colorHex).isEqualTo("#111111")
            assertThat(work.iconKey).isEqualTo("work")
            assertThat(work.isActive).isTrue()
            assertThat(work.weight).isEqualTo(2.0)
            assertThat(work.sortOrder).isEqualTo(2)
        }

    @Test
    fun `every update writes its column AND the updated timestamp`(): Unit =
        runBlocking {
            repository().updateDimensionLabel("dim_work_livelihood", "Career")
            assertThat(raw("dim_work_livelihood", "label")).isEqualTo("Career")
            assertThat(raw("dim_work_livelihood", "updatedAt")).isNotEqualTo("T0")

            repository().updateDimensionColor("dim_work_livelihood", "#abcdef")
            assertThat(raw("dim_work_livelihood", "color")).isEqualTo("#abcdef")

            repository().updateDimensionIcon("dim_work_livelihood", "briefcase")
            assertThat(raw("dim_work_livelihood", "icon")).isEqualTo("briefcase")

            repository().updateDimensionActiveState("dim_work_livelihood", isActive = false)
            assertThat(raw("dim_work_livelihood", "isActive")).isEqualTo(0)

            repository().updateDimensionWeight("dim_work_livelihood", 3.5)
            assertThat(raw("dim_work_livelihood", "weight")).isEqualTo(3.5)

            assertThat(raw("dim_work_livelihood", "updatedAt")).isNotEqualTo("T0")
        }

    @Test
    fun `an update does not disturb the other columns`(): Unit =
        runBlocking {
            repository().updateDimensionLabel("dim_work_livelihood", "Career")

            // A read-copy-write would be a risk here: these fields are edited from separate controls.
            assertThat(raw("dim_work_livelihood", "color")).isEqualTo("#111111")
            assertThat(raw("dim_work_livelihood", "weight")).isEqualTo(2.0)
            assertThat(raw("dim_work_livelihood", "sortOrder")).isEqualTo(2)
        }

    @Test
    fun `reactivating a dimension flips the flag back`(): Unit =
        runBlocking {
            repository().updateDimensionActiveState("dim_work_livelihood", isActive = false)
            repository().updateDimensionActiveState("dim_work_livelihood", isActive = true)

            assertThat(raw("dim_work_livelihood", "isActive")).isEqualTo(1)
            assertThat(
                repository()
                    .observeAllDimensions()
                    .first()
                    .first { it.id == "dim_work_livelihood" }
                    .isActive,
            ).isTrue()
        }

    @Test
    fun `an update for an unknown dimension changes nothing and does not throw`(): Unit =
        runBlocking {
            repository().updateDimensionLabel("dim_absent", "Nope")

            assertThat(repository().observeAllDimensions().first()).hasSize(2)
        }

    @Test
    fun `the catalog flow re-emits after an update`(): Unit =
        runBlocking {
            val flow = repository().observeAllDimensions()
            val before = flow.first().first { it.id == "dim_work_livelihood" }.label

            repository().updateDimensionLabel("dim_work_livelihood", "Career")

            val after =
                kotlinx.coroutines.withTimeout(5_000) {
                    flow.first { it.any { dimension -> dimension.label == "Career" } }
                }
            assertThat(after.first { it.id == "dim_work_livelihood" }.label).isEqualTo("Career")
            assertThat(before).isEqualTo("Work & Livelihood")
        }

    // ---- replaceAllDimensions: the atomic catalog swap -------------------------------------------------
    //
    // The guarantee under test is ATOMICITY, not just the final contents. A delete followed by a loop of
    // inserts leaves the table empty in between, and this repository's own flow polls — so a subscriber
    // would read an empty catalog and scoring would weigh the day by nothing. These assert both the result
    // and that a failure leaves the previous catalog intact.

    @Test
    fun `replaceAllDimensions swaps the whole catalog and reports the count`(): Unit =
        runBlocking {
            val written =
                repository().replaceAllDimensions(
                    listOf(
                        dimension("dim_health_vitality", "Health", sortOrder = 0, isActive = true),
                        dimension("dim_work_livelihood", "Work", sortOrder = 1, isActive = false),
                        dimension("dim_relations", "Relations", sortOrder = 2, isActive = true),
                    ),
                )

            assertThat(written).isEqualTo(3)
            val stored = repository().observeAllDimensions().first()
            assertThat(stored.map { it.id })
                .containsExactly("dim_health_vitality", "dim_work_livelihood", "dim_relations")
            // A dimension the user REMOVED must actually be gone: this is a replace, not an upsert, or a
            // stale row would keep scoring a dimension the user no longer has.
            assertThat(stored.first { it.id == "dim_work_livelihood" }.isActive).isFalse()
        }

    @Test
    fun `replaceAllDimensions writes every column, and one timestamp for the whole swap`(): Unit =
        runBlocking {
            repository().replaceAllDimensions(
                listOf(dimension("dim_health_vitality", "Wellbeing", key = "health_vitality", weight = 2.5)),
            )

            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM life_dimensions").use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getString("id")).isEqualTo("dim_health_vitality")
                    // `key` is a SQL keyword; a statement that dropped the backticks would not compile,
                    // so reaching this assertion at all proves the column was written.
                    assertThat(rs.getString("key")).isEqualTo("health_vitality")
                    assertThat(rs.getString("label")).isEqualTo("Wellbeing")
                    assertThat(rs.getString("description")).isEqualTo("Body and energy")
                    assertThat(rs.getString("color")).isEqualTo("#4CAF50")
                    assertThat(rs.getString("icon")).isEqualTo("heart")
                    assertThat(rs.getInt("sortOrder")).isEqualTo(0)
                    assertThat(rs.getInt("isActive")).isEqualTo(1)
                    assertThat(rs.getDouble("weight")).isEqualTo(2.5)
                    val createdAt = rs.getString("createdAt")
                    assertThat(createdAt).isNotEmpty()
                    // Same instant for createdAt and updatedAt: the repository takes the clock once, so a
                    // seeded row cannot claim it was modified before it existed.
                    assertThat(rs.getString("updatedAt")).isEqualTo(createdAt)
                }
            }
        }

    @Test
    fun `replaceAllDimensions with an empty list clears the catalog rather than leaving stale rows`(): Unit =
        runBlocking {
            val written = repository().replaceAllDimensions(emptyList())

            assertThat(written).isEqualTo(0)
            assertThat(repository().observeAllDimensions().first()).isEmpty()
        }

    @Test
    fun `a failed replace leaves the previous catalog intact`(): Unit =
        runBlocking {
            val before = repository().observeAllDimensions().first().map { it.id }

            // A row whose NOT NULL `label` is null, so the INSERT batch fails AFTER the DELETE has already
            // run. Without the transaction the catalog would be left empty — which is precisely the data
            // loss this method exists to prevent.
            //
            // Two other ways of forcing a failure were tried first and did NOT fail, which is worth
            // recording: a duplicate primary key is swallowed by `INSERT OR REPLACE` (that is what REPLACE
            // means), and a duplicate `key` is not constrained by this fixture. So the constraint used here
            // is one the table actually declares.
            val failure =
                runCatching {
                    repository().replaceAllDimensions(
                        listOf(
                            dimension("dim_a", "A", sortOrder = 0),
                            // `key` is UNIQUE in the production table. The fixture declares no such index,
                            // so it is added here rather than weakening the assertion: without it there is
                            // no way to make an insert fail, and an insert that cannot fail proves nothing
                            // about the rollback.
                            dimension("dim_b", "B", sortOrder = 1, key = "dup"),
                            dimension("dim_c", "C", sortOrder = 2, key = "dup"),
                        ),
                    )
                }.exceptionOrNull()

            assertThat(failure).isNotNull()
            val after = repository().observeAllDimensions().first().map { it.id }
            assertThat(after).isEqualTo(before)
        }

    private fun dimension(
        id: String,
        label: String,
        // Unique per row by default, because `key` carries a UNIQUE index in the production table: a helper
        // that stamped every row with one key would make an ordinary multi-row replace fail.
        key: String = id,
        sortOrder: Int = 0,
        isActive: Boolean = true,
        weight: Double = 1.0,
    ) = ConfiguredLifeDimension(
        id = id,
        key = key,
        label = label,
        description = "Body and energy",
        colorHex = "#4CAF50",
        iconKey = "heart",
        sortOrder = sortOrder,
        isActive = isActive,
        weight = weight,
    )
}
