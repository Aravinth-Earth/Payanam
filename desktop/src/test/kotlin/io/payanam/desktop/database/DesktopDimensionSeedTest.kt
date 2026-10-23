//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.database.defaultNewDatabaseDimensionInputs
import io.payanam.core.ui.text.uiStringsFor
import io.payanam.database.jvm.AppSettingsRepositoryJvm
import io.payanam.database.jvm.LifeDimensionCatalogRepositoryJvm
import io.payanam.database.jvm.PayanamDatabaseSession
import io.payanam.domain.model.ConfiguredLifeDimension
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.LifeDimensionCatalogRepository
import kotlinx.coroutines.flow.Flow
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
 * The desktop's first-run dimension seed, over a REAL generated database file: the seed reaches
 * [LifeDimensionCatalogRepository.replaceAllDimensions] exactly once, carrying a non-empty catalog.
 *
 * **THE GAP THIS FILE CLOSES.** Android has `DatabaseInitDimensionSetupTest`, which drives the `:app` seed
 * through `persistNewDatabaseDimensionSetup` and stubs the catalog to capture the call. The desktop had
 * no test naming that symbol at all, so nothing in this module proved its own first-run seed ever reaches
 * the repository the contract names — and the failure that allows is silent: the persister writes nothing,
 * the ViewModel records `database_init_completed = true` and navigates on, and the user gets a brand-new
 * database whose catalog is empty and which therefore scores nothing while reporting success.
 *
 * **THE SEED PATH RUNS; THIS TEST NEVER CALLS THE REPOSITORY ITSELF.** The only call to
 * `replaceAllDimensions` in this file is the one made by
 * [desktopDatabaseDimensionSetupPersister] — the same object `DesktopDatabaseInitViewModel` is handed as
 * `dimensionSetupPersister`. A version that invoked the repository directly and asserted on its own call
 * would pass with the persister deleted, which is the opposite of what the gate claims.
 *
 * **THE REPOSITORY IS REAL; ONLY THE CALL IS RECORDED.** [RecordingCatalog] DELEGATES every method to the
 * production [LifeDimensionCatalogRepositoryJvm] — the very object `DesktopPayanamDataLayer` installs — so
 * the captured rows really land in the database and are read back out of the table below. A pure fake that
 * only remembered its argument would satisfy "the persister called something" without satisfying "a fresh
 * database was seeded", which is half the gate. It is a spy, not a player, and nothing here is stubbed
 * away: the repository does its own transactional delete-then-insert against real SQL.
 *
 * The fixture is `DesktopStartupRecoveryTest`'s: a SQLCipher file in a per-class [TemporaryFolder], the
 * hand-written DDL its steps need (this class needs two tables — the catalog and the settings row the
 * persister writes after it), and `PayanamDatabaseSession.forGeneratedFixture`, which wraps an already-open
 * connection and migrates nothing.
 */
class DesktopDimensionSeedTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * A throwaway key for the GENERATED fixture this test creates in its own temp directory.
     *
     * Not a user passphrase and never one: the file under test is created and destroyed here, and no
     * property of a real database is read, copied or logged anywhere in this class.
     */
    private val fixturePassphrase = "dimension-seed-test"

    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

    @Before
    fun setUp() {
        val path = tempFolder.newFolder().toPath().resolve("dimension-seed.db")
        val properties = Properties().apply { setProperty(KEY_PROPERTY, fixturePassphrase) }
        connection = DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
        // Hand-written DDL, for the reason DesktopStartupRecoveryTest gives: the session helper only WRAPS
        // an open connection, so a database reaches this persister with no tables at all and the seed
        // would fail with "no such table" — which would look like the persister being broken.
        connection.createStatement().use { statement ->
            SEED_DDL.forEach { statement.execute(it) }
        }
        session = PayanamDatabaseSession.forGeneratedFixture(connection, schemaVersion = SCHEMA_VERSION)
    }

    @After
    fun tearDown() {
        if (!session.isClosed) session.close()
    }

    /**
     * The gate: one first-run seed, one atomic catalog replace, and a fresh database holding the rows.
     *
     * The count is asserted as `isEqualTo(1)` rather than "more than nothing", because the two failures
     * are different and only one of them is this gate's subject: zero calls means the desktop does not
     * seed at all, while TWO calls mean the catalog is deleted and rewritten twice — the same
     * non-atomicity the repository's own contract exists to prevent, arriving one layer up.
     */
    @Test
    fun `the first-run dimension seed replaces the catalog exactly once on a fresh database`(): Unit =
        runBlocking {
            val catalog = RecordingCatalog(LifeDimensionCatalogRepositoryJvm(session, pollIntervalMs = POLL_INTERVAL_MS))
            val settings: AppSettingsRepository = AppSettingsRepositoryJvm(session)

            desktopDatabaseDimensionSetupPersister.persist(
                lifeDimensionCatalogRepository = catalog,
                appSettingsRepository = settings,
                dimensionInputs = defaultNewDatabaseDimensionInputs(uiStringsFor("ta")),
            )

            // The claim, counted rather than assumed.
            assertThat(catalog.replaceCalls).isEqualTo(1)

            // The rows captured at the seam, and a fresh database is never seeded with an empty catalog:
            // every row the shared builder produced is handed over in the one replace.
            assertThat(catalog.capturedRows).isNotEmpty()

            // Those captured rows are the rows in the table — captured by sort order, because the catalog
            // is read back ordered by it, and a seed whose rows never landed would leave the table empty.
            assertThat(storedDimensionIds()).isEqualTo(catalog.capturedRows.sortedBy { it.sortOrder }.map { it.id })

            // Context for the same run, not a separate claim: the flag is written AFTER the catalog, so
            // finding it proves the persister ran to completion rather than throwing between the two.
            assertThat(
                settings.isDatabaseInitCompleted(),
            ).isTrue()
        }

    /** The `life_dimensions` ids as the seeded file actually holds them. */
    private fun storedDimensionIds(): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery(SELECT_IDS_SQL).use { rows ->
                buildList {
                    while (rows.next()) add(rows.getString(1))
                }
            }
        }

    /**
     * A spy, not a stub: it counts and captures the one call under test and delegates everything else to
     * the real [LifeDimensionCatalogRepositoryJvm].
     *
     * Delegating rather than returning empties is deliberate for the non-seed methods too — a hand-written
     * no-op there would turn a future persister that started calling `updateDimension*` one row at a time
     * into a silent pass, which is the exact non-atomic rewrite this seam exists to catch.
     */
    private class RecordingCatalog(
        private val delegate: LifeDimensionCatalogRepository,
    ) : LifeDimensionCatalogRepository {
        /** How many times the seed path called the atomic replace. The gate's subject. */
        var replaceCalls = 0
            private set

        /** The rows handed to each replace, in call order. */
        val capturedRows = mutableListOf<ConfiguredLifeDimension>()

        override suspend fun replaceAllDimensions(dimensions: List<ConfiguredLifeDimension>): Int {
            replaceCalls++
            capturedRows.addAll(dimensions)
            return delegate.replaceAllDimensions(dimensions)
        }

        override fun observeAllDimensions(): Flow<List<ConfiguredLifeDimension>> = delegate.observeAllDimensions()

        override suspend fun updateDimensionLabel(
            dimensionId: String,
            label: String,
        ) = delegate.updateDimensionLabel(dimensionId, label)

        override suspend fun updateDimensionColor(
            dimensionId: String,
            colorHex: String,
        ) = delegate.updateDimensionColor(dimensionId, colorHex)

        override suspend fun updateDimensionIcon(
            dimensionId: String,
            iconKey: String,
        ) = delegate.updateDimensionIcon(dimensionId, iconKey)

        override suspend fun updateDimensionActiveState(
            dimensionId: String,
            isActive: Boolean,
        ) = delegate.updateDimensionActiveState(dimensionId, isActive)

        override suspend fun updateDimensionWeight(
            dimensionId: String,
            weight: Double,
        ) = delegate.updateDimensionWeight(dimensionId, weight)
    }

    private companion object {
        /** `PayanamDatabaseOpener`'s SQLCipher key property name, spelled out because it is `internal`. */
        const val KEY_PROPERTY = "key"

        /** Matches the sibling desktop database fixtures; the factory takes it only to bypass the guard. */
        const val SCHEMA_VERSION = 21

        /** Short poll, as in the sibling fixtures. Nothing here subscribes; it only keeps the value honest. */
        const val POLL_INTERVAL_MS = 10L

        const val SELECT_IDS_SQL = "SELECT id FROM life_dimensions ORDER BY sortOrder ASC, id ASC"
    }
}

/**
 * The two tables this seed writes: the catalog it replaces, and the `app_settings` row it writes after.
 *
 * Transcribed from the schema export rather than from memory. `life_dimensions` spells its key column
 * `key` — a SQL keyword, so the DDL must backtick it exactly as `LifeDimensionCatalogRepositoryJvm`'s
 * `INSERT_ALL_SQL` does — and the production table carries a UNIQUE index on it
 * (`LifeDimensionEntity`'s `@Index`), which the replace's plain `INSERT` relies on to fail and roll back.
 */
private val SEED_DDL: List<String> =
    listOf(
        """
        CREATE TABLE life_dimensions (
            id TEXT NOT NULL,
            `key` TEXT NOT NULL,
            label TEXT NOT NULL,
            description TEXT,
            color TEXT NOT NULL,
            icon TEXT,
            sortOrder INTEGER NOT NULL,
            isActive INTEGER NOT NULL DEFAULT 1,
            weight REAL NOT NULL DEFAULT 1.0,
            createdAt TEXT NOT NULL,
            updatedAt TEXT NOT NULL,
            PRIMARY KEY (id)
        )
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX idx_life_dimensions_key ON life_dimensions (`key`)
        """.trimIndent(),
        """
        CREATE TABLE app_settings (
            `key` TEXT NOT NULL,
            value TEXT,
            updatedAt TEXT NOT NULL,
            PRIMARY KEY (`key`)
        )
        """.trimIndent(),
    )
