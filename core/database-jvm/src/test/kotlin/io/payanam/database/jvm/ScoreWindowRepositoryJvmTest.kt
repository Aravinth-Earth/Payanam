//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
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
 * Tests for [ScoreWindowRepositoryJvm].
 *
 * The session is constructed directly from an open connection rather than through
 * [PayanamDatabaseOpener.open] — the opener refuses a version-0 database (correctly) and no driver
 * route can write a version, so no generated fixture can pass it. Repository logic does not depend on
 * the version guard. The opener's own guards are covered in [PayanamDatabaseOpenerTest].
 */
class ScoreWindowRepositoryJvmTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "score-window-test-passphrase"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

    @Before
    fun setUp() {
        val path = tempFolder.newFolder().toPath().resolve("score.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection =
            DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE dimension_metrics (
                    dimensionId TEXT NOT NULL,
                    dayKey TEXT NOT NULL,
                    score REAL NOT NULL,
                    runningAvg REAL NOT NULL,
                    progress REAL NOT NULL,
                    streakPos INTEGER NOT NULL,
                    streakNet INTEGER NOT NULL,
                    posContinue INTEGER NOT NULL,
                    PRIMARY KEY (dimensionId, dayKey)
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE day_metrics (
                    dayKey TEXT NOT NULL PRIMARY KEY,
                    dayScore REAL NOT NULL,
                    runningAvg REAL NOT NULL,
                    progress REAL NOT NULL,
                    streakPos INTEGER NOT NULL,
                    streakNet INTEGER NOT NULL,
                    posContinue INTEGER NOT NULL
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

    private fun insertDimension(
        dimensionId: String,
        dayKey: String,
        score: Double,
    ) {
        connection
            .prepareStatement("INSERT INTO dimension_metrics VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
            .use { statement ->
                statement.setString(1, dimensionId)
                statement.setString(2, dayKey)
                statement.setDouble(3, score)
                statement.setDouble(4, 1.5)
                statement.setDouble(5, 0.25)
                statement.setInt(6, 4)
                statement.setInt(7, 3)
                statement.setInt(8, 2)
                statement.executeUpdate()
            }
    }

    private fun insertDay(
        dayKey: String,
        dayScore: Double,
    ) {
        connection
            .prepareStatement("INSERT INTO day_metrics VALUES (?, ?, ?, ?, ?, ?, ?)")
            .use { statement ->
                statement.setString(1, dayKey)
                statement.setDouble(2, dayScore)
                statement.setDouble(3, 9.5)
                statement.setDouble(4, 0.75)
                statement.setInt(5, 6)
                statement.setInt(6, 5)
                statement.setInt(7, 4)
                statement.executeUpdate()
            }
    }

    private fun repository() = ScoreWindowRepositoryJvm(session)

    @Test
    fun windowsAreEmptyOnAnEmptyDatabase() =
        runBlocking {
            assertThat(repository().getDimensionWindow("2026-01-01", "2026-12-31")).isEmpty()
            assertThat(repository().getDayWindow("2026-01-01", "2026-12-31")).isEmpty()
            Unit
        }

    @Test
    fun earliestReadersReturnNullWhenThereAreNoRows() =
        runBlocking {
            // MIN() over an empty table yields one row holding SQL NULL. Returning null here is what
            // the "All" range start depends on; an implementation that treated "no rows" and "null
            // value" differently would break that.
            assertThat(repository().earliestDayKey()).isNull()
            assertThat(repository().earliestDimensionDayKey()).isNull()
            assertThat(repository().earliestDimensionDayKey("dim-1")).isNull()
            Unit
        }

    @Test
    fun dimensionWindowIsInclusiveAscendingAndScopedByDay() =
        runBlocking {
            insertDimension("dim-1", "2026-01-01", 1.0)
            insertDimension("dim-1", "2026-02-15", 2.0)
            insertDimension("dim-1", "2026-03-01", 3.0)
            insertDimension("dim-1", "2026-04-01", 4.0)

            val rows = repository().getDimensionWindow("2026-02-15", "2026-03-01")

            // Both bounds inclusive, chronological order.
            assertThat(rows.map { it.dayKey }).containsExactly("2026-02-15", "2026-03-01").inOrder()
            Unit
        }

    @Test
    fun dimensionWindowCarriesEveryMetricField() =
        runBlocking {
            insertDimension("dim-1", "2026-02-15", 2.5)

            val row = repository().getDimensionWindow("2026-01-01", "2026-12-31").single()

            assertThat(row.key).isEqualTo("dim-1")
            assertThat(row.dayKey).isEqualTo("2026-02-15")
            assertThat(row.score).isEqualTo(2.5)
            assertThat(row.runningAvg).isEqualTo(1.5)
            assertThat(row.progress).isEqualTo(0.25)
            assertThat(row.streakPos).isEqualTo(4)
            assertThat(row.streakNet).isEqualTo(3)
            assertThat(row.posContinue).isEqualTo(2)
            Unit
        }

    @Test
    fun dayWindowMapsTheDayScoreColumnToTheScoreField() =
        runBlocking {
            // The column is `dayScore`; DayMetricRow exposes it as `score`. Reading the wrong column
            // would throw at runtime rather than silently, but reading a same-named column from the
            // wrong table would not — hence asserting the value.
            insertDay("2026-02-15", dayScore = 7.25)

            val row = repository().getDayWindow("2026-01-01", "2026-12-31").single()

            assertThat(row.score).isEqualTo(7.25)
            assertThat(row.dayKey).isEqualTo("2026-02-15")
            assertThat(row.key).isEqualTo("DAY")
            Unit
        }

    @Test
    fun earliestReadersPickTheMinimumDayKey() =
        runBlocking {
            insertDay("2026-03-01", 3.0)
            insertDay("2026-01-01", 1.0)
            insertDay("2026-02-01", 2.0)
            insertDimension("dim-1", "2026-05-01", 5.0)
            insertDimension("dim-1", "2026-01-15", 1.5)
            insertDimension("dim-2", "2026-06-01", 6.0)

            assertThat(repository().earliestDayKey()).isEqualTo("2026-01-01")
            assertThat(repository().earliestDimensionDayKey()).isEqualTo("2026-01-15")
            Unit
        }

    @Test
    fun earliestDimensionDayKeyIsScopedToOneDimension() =
        runBlocking {
            insertDimension("dim-1", "2026-05-01", 5.0)
            insertDimension("dim-2", "2026-01-15", 1.5)

            assertThat(repository().earliestDimensionDayKey("dim-1")).isEqualTo("2026-05-01")
            assertThat(repository().earliestDimensionDayKey("dim-2")).isEqualTo("2026-01-15")
            assertThat(repository().earliestDimensionDayKey("dim-none")).isNull()
            Unit
        }
}
