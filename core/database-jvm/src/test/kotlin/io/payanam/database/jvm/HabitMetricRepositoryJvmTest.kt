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
 * Tests for [HabitMetricRepositoryJvm].
 *
 * The session is built DIRECTLY rather than through [PayanamDatabaseOpener.open], which is legitimate
 * here and avoids a fixture limitation: the opener refuses a version-0 database (correctly — it is below
 * the migratable floor), and no driver route can write a version, so a generated fixture can never be
 * opened through it. Repository logic does not depend on the version guard, so the test constructs the
 * session from an already-open connection instead. The opener's own guards are covered in
 * [PayanamDatabaseOpenerTest].
 *
 * The fixture is generated in a per-class temp directory and never committed: a real export holds the
 * user's personal data.
 */
class HabitMetricRepositoryJvmTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "habit-metric-test-passphrase"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

    @Before
    fun setUp() {
        val path = tempFolder.newFolder().toPath().resolve("habit.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection =
            DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE habit_metrics (
                    habitId TEXT NOT NULL,
                    dayKey TEXT NOT NULL,
                    score REAL NOT NULL,
                    runningAvg REAL NOT NULL,
                    progress REAL NOT NULL,
                    streakPos INTEGER NOT NULL,
                    streakNet INTEGER NOT NULL,
                    posContinue INTEGER NOT NULL,
                    PRIMARY KEY (habitId, dayKey)
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

    private fun insert(
        habitId: String,
        dayKey: String,
        score: Double,
        runningAvg: Double,
    ) {
        connection
            .prepareStatement(
                "INSERT INTO habit_metrics VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, habitId)
                statement.setString(2, dayKey)
                statement.setDouble(3, score)
                statement.setDouble(4, runningAvg)
                statement.setDouble(5, 0.5)
                statement.setInt(6, 3)
                statement.setInt(7, 2)
                statement.setInt(8, 1)
                statement.executeUpdate()
            }
    }

    private fun repository() = HabitMetricRepositoryJvm(session)

    @Test
    fun returnsEmptyWhenThereAreNoRows() =
        runBlocking {
            assertThat(repository().getLatestPerHabit()).isEmpty()
            assertThat(repository().getLatestForHabit("habit-1")).isNull()
            assertThat(repository().getForHabitRange("habit-1", "2026-01-01", "2026-12-31")).isEmpty()
        }

    @Test
    fun latestPerHabitPicksTheMaximumDayKeyNotAnArbitraryRow() =
        runBlocking {
            // Inserted OUT of order, so a bare GROUP BY that happened to pick the last inserted row
            // would fail this. The MAX(dayKey) join is the point of the query.
            insert("habit-1", "2026-03-05", score = 30.0, runningAvg = 3.0)
            insert("habit-1", "2026-01-10", score = 10.0, runningAvg = 1.0)
            insert("habit-1", "2026-02-20", score = 20.0, runningAvg = 2.0)

            val latest = repository().getLatestPerHabit()

            assertThat(latest).containsKey("habit-1")
            assertThat(latest.getValue("habit-1").dayKey).isEqualTo("2026-03-05")
            assertThat(latest.getValue("habit-1").score).isEqualTo(30.0)
        }

    @Test
    fun latestPerHabitIsIndependentPerHabit() =
        runBlocking {
            insert("habit-1", "2026-01-10", score = 10.0, runningAvg = 1.0)
            insert("habit-1", "2026-03-05", score = 30.0, runningAvg = 3.0)
            insert("habit-2", "2026-02-01", score = 99.0, runningAvg = 9.9)

            val latest = repository().getLatestPerHabit()

            assertThat(latest.keys).containsExactly("habit-1", "habit-2")
            assertThat(latest.getValue("habit-1").dayKey).isEqualTo("2026-03-05")
            assertThat(latest.getValue("habit-2").dayKey).isEqualTo("2026-02-01")
            assertThat(latest.getValue("habit-2").score).isEqualTo(99.0)
        }

    @Test
    fun latestForHabitIsNullForAnUnknownHabit() =
        runBlocking {
            insert("habit-1", "2026-03-05", score = 30.0, runningAvg = 3.0)

            assertThat(repository().getLatestForHabit("habit-unknown")).isNull()
            assertThat(repository().getLatestForHabit("habit-1")?.score).isEqualTo(30.0)
        }

    @Test
    fun rangeIsInclusiveAtBothBoundsAndAscending() =
        runBlocking {
            insert("habit-1", "2026-01-01", score = 1.0, runningAvg = 1.0)
            insert("habit-1", "2026-02-01", score = 2.0, runningAvg = 2.0)
            insert("habit-1", "2026-03-01", score = 3.0, runningAvg = 3.0)
            insert("habit-1", "2026-04-01", score = 4.0, runningAvg = 4.0)

            val rows = repository().getForHabitRange("habit-1", "2026-02-01", "2026-03-01")

            // Both bounds inclusive: an exclusive comparison would drop one of these.
            assertThat(rows.map { it.dayKey }).containsExactly("2026-02-01", "2026-03-01").inOrder()
            // JUnit 4 requires a void test method; containsExactly returns a value, so the block is
            // closed explicitly rather than letting the function infer that return type.
            Unit
        }

    @Test
    fun rangeIsScopedToOneHabit() =
        runBlocking {
            insert("habit-1", "2026-02-01", score = 2.0, runningAvg = 2.0)
            insert("habit-2", "2026-02-01", score = 7.0, runningAvg = 7.0)

            val rows = repository().getForHabitRange("habit-1", "2026-01-01", "2026-12-31")

            assertThat(rows.map { it.habitId }).containsExactly("habit-1")
            Unit
        }

    @Test
    fun mappingCarriesEveryField() =
        runBlocking {
            insert("habit-1", "2026-03-05", score = 30.5, runningAvg = 3.25)

            val summary = repository().getLatestForHabit("habit-1")!!

            assertThat(summary.habitId).isEqualTo("habit-1")
            assertThat(summary.dayKey).isEqualTo("2026-03-05")
            assertThat(summary.score).isEqualTo(30.5)
            assertThat(summary.runningAvg).isEqualTo(3.25)
            assertThat(summary.progress).isEqualTo(0.5)
            assertThat(summary.streakPos).isEqualTo(3)
            assertThat(summary.streakNet).isEqualTo(2)
            assertThat(summary.posContinue).isEqualTo(1)
        }
}
