//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.model.LifeDimension
import io.payanam.domain.model.ScoringConfig
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
 * Tests for [ScoringConfigRepositoryJvm].
 *
 * The singleton is identified by a literal `id = 1` in every statement, so "no row" and "one row" are the
 * only two states the table can be in — asserted directly, because a port that inserted a defaults row would
 * erase the difference between "never configured" and "configured to the defaults".
 *
 * A generated SQLCipher fixture in a per-class temp directory, never a real export.
 */
class ScoringConfigRepositoryJvmTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "scoring-config-test-passphrase"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

    @Before
    fun setUp() {
        val path = tempFolder.newFolder().toPath().resolve("scoring.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection =
            DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE scoring_config (
                    id INTEGER PRIMARY KEY NOT NULL,
                    dimensionWeight REAL NOT NULL,
                    impactWeight REAL NOT NULL,
                    alignmentWeight REAL NOT NULL,
                    energyWeight REAL NOT NULL,
                    controlWeight REAL NOT NULL,
                    durationWeight REAL NOT NULL,
                    impactCritical REAL NOT NULL,
                    impactHigh REAL NOT NULL,
                    impactModerate REAL NOT NULL,
                    impactLow REAL NOT NULL,
                    impactMinimal REAL NOT NULL,
                    alignmentPerfect REAL NOT NULL,
                    alignmentStrong REAL NOT NULL,
                    alignmentModerate REAL NOT NULL,
                    alignmentWeak REAL NOT NULL,
                    alignmentNone REAL NOT NULL,
                    energyHigh REAL NOT NULL,
                    energyModerate REAL NOT NULL,
                    energyLow REAL NOT NULL,
                    controlFull REAL NOT NULL,
                    controlMostly REAL NOT NULL,
                    controlOffice REAL NOT NULL,
                    controlExternal REAL NOT NULL,
                    controlNone REAL NOT NULL,
                    dimensionCareerWork REAL NOT NULL,
                    dimensionHealthWellness REAL NOT NULL,
                    dimensionRelationships REAL NOT NULL,
                    dimensionPersonalGrowth REAL NOT NULL,
                    dimensionFinancial REAL NOT NULL,
                    dimensionSpiritual REAL NOT NULL,
                    dimensionRecreation REAL NOT NULL,
                    dimensionLearning REAL NOT NULL,
                    dimensionContribution REAL NOT NULL,
                    updatedAt TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }
        session = PayanamDatabaseSession(connection, 21, DatabaseOpenMode.READ_WRITE)
    }

    @After
    fun tearDown() {
        session.close()
    }

    private fun repository() = ScoringConfigRepositoryJvm(session, pollIntervalMs = 10L)

    private fun rowCount(): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM scoring_config").use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }

    private fun rawDouble(column: String): Double? =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT $column FROM scoring_config WHERE id = 1").use { resultSet ->
                if (resultSet.next()) resultSet.getDouble(1) else null
            }
        }

    @Test
    fun `an unsaved config reads as the defaults and writes no row`(): Unit =
        runBlocking {
            val config = repository().getConfig()

            assertThat(config).isEqualTo(ScoringConfig.defaults())
            assertThat(rowCount()).isEqualTo(0)
        }

    @Test
    fun `a saved config round-trips`(): Unit =
        runBlocking {
            val defaults = ScoringConfig.defaults()
            repository().saveConfig(defaults.copy(dimensionWeight = 3.25))

            val loaded = repository().getConfig()

            assertThat(loaded.dimensionWeight).isEqualTo(3.25)
            assertThat(rawDouble("dimensionWeight")).isEqualTo(3.25)
            assertThat(rowCount()).isEqualTo(1)
        }

    @Test
    fun `every column is written - values at both ends of the row survive`(): Unit =
        runBlocking {
            val defaults = ScoringConfig.defaults()

            repository().saveConfig(
                defaults.copy(
                    // The FIRST weight column after the id, and the LAST one before updatedAt.
                    impactLevelWeights = defaults.impactLevelWeights + ("Critical Impact" to 0.99),
                    dimensionWeightsById =
                        defaults.dimensionWeightsById + (LifeDimension.CONTRIBUTION.id to 3.0),
                ),
            )

            val loaded = repository().getConfig()

            // A column left out of the statement would keep its previous value while the UI showed the new one.
            assertThat(loaded.impactLevelWeights["Critical Impact"]).isEqualTo(0.99)
            assertThat(loaded.dimensionWeightsById[LifeDimension.CONTRIBUTION.id]).isEqualTo(3.0)
            assertThat(rawDouble("impactCritical")).isEqualTo(0.99)
            assertThat(rawDouble("dimensionContribution")).isEqualTo(3.0)
        }

    @Test
    fun `saving twice keeps one row`(): Unit =
        runBlocking {
            val defaults = ScoringConfig.defaults()
            repository().saveConfig(defaults)
            repository().saveConfig(defaults.copy(dimensionWeight = 1.0))

            assertThat(rowCount()).isEqualTo(1)
            assertThat(repository().getConfig().dimensionWeight).isEqualTo(1.0)
        }

    @Test
    fun `resetToDefaults deletes the row so the next read is the defaults again`(): Unit =
        runBlocking {
            repository().saveConfig(ScoringConfig.defaults().copy(dimensionWeight = 9.0))

            repository().resetToDefaults()

            assertThat(rowCount()).isEqualTo(0)
            assertThat(repository().getConfig()).isEqualTo(ScoringConfig.defaults())
        }

    @Test
    fun `observeConfig emits the defaults while unsaved and the saved config afterwards`(): Unit =
        runBlocking {
            val flow = repository().observeConfig()
            assertThat(flow.first()).isEqualTo(ScoringConfig.defaults())

            repository().saveConfig(ScoringConfig.defaults().copy(impactWeight = 4.0))

            val emitted =
                kotlinx.coroutines.withTimeout(5_000) {
                    flow.first { it.impactWeight == 4.0 }
                }
            assertThat(emitted.impactWeight).isEqualTo(4.0)
        }

    @Test
    fun `observing after a reset goes back to the defaults`(): Unit =
        runBlocking {
            repository().saveConfig(ScoringConfig.defaults().copy(energyWeight = 5.0))
            val flow = repository().observeConfig()
            assertThat(flow.first().energyWeight).isEqualTo(5.0)

            repository().resetToDefaults()

            assertThat(kotlinx.coroutines.withTimeout(5_000) { flow.first { it.energyWeight != 5.0 } })
                .isEqualTo(ScoringConfig.defaults())
        }
}
