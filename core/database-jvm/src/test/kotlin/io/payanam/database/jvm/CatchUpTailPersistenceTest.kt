//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.database.entity.HabitMetricEntity
import io.payanam.database.entity.TaskEntity
import io.payanam.database.entity.TaskOccurrenceEntity
import io.payanam.scoring.CatchUpTailCompute
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.util.Properties

/**
 * Proves that [ScoreRollupStoreJdbc.upsertHabitMetrics] PERSISTS the rows returned by
 * [CatchUpTailCompute.extendHabitTail] — the Phase-1 catch-up tail compute.
 *
 * The test builds a lagging habit (DB rows through day(3), occurrences through day(3)),
 * computes the tail extension through yesterday, upserts the returned rows, and reads
 * them back to prove the persisted dayKey/score set matches the compute output exactly.
 */
class CatchUpTailPersistenceTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "catch-up-tail-persistence-test-passphrase"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession
    private lateinit var file: java.nio.file.Path

    private val today: LocalDate = LocalDate.now()

    @Before
    fun setUp() {
        file = tempFolder.newFolder().toPath().resolve("catchup.db")
        val path = file
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

    private fun store() = ScoreRollupStoreJdbc(session)

    private fun day(daysAgo: Int): LocalDate = today.minusDays(daysAgo.toLong())

    private fun task(
        id: String = "h1",
        recurrenceEnabled: Int = 1,
        recurrenceRule: String? = "1/1",
    ) = TaskEntity(
        id = id,
        title = "Habit",
        createdAt = day(7).toString(),
        updatedAt = day(7).toString(),
        recurrenceEnabled = recurrenceEnabled,
        recurrenceRule = recurrenceRule,
    )

    private fun occurrence(
        taskId: String = "h1",
        dueDate: String,
        status: String,
    ) = TaskOccurrenceEntity(
        id = "$taskId-$dueDate-$status",
        taskId = taskId,
        dueDate = dueDate,
        status = status,
        createdAt = dueDate,
    )

    private fun habitRow(
        dayKey: String,
        score: Double,
        runningAvg: Double,
        progress: Double,
        streakPos: Int,
        streakNet: Int,
        posContinue: Int,
    ) = HabitMetricEntity(
        habitId = "h1",
        dayKey = dayKey,
        score = score,
        runningAvg = runningAvg,
        progress = progress,
        streakPos = streakPos,
        streakNet = streakNet,
        posContinue = posContinue,
    )

    @Test
    fun `catchUpTail rows are persisted with correct dayKey and score`(): Unit =
        runBlocking {
            // Habit lagging 3 days: DB has rows through day(3), occurrences through day(3).
            val existingRows =
                listOf(
                    habitRow(day(5).toString(), 1.0, 1.0, 1.0, 1, 1, 1),
                    habitRow(day(4).toString(), 1.0, 1.0, 0.0, 2, 2, 2),
                    habitRow(day(3).toString(), 1.0, 1.0, 0.0, 3, 3, 3),
                )
            val occurrences =
                listOf(
                    occurrence(dueDate = day(5).toString(), status = "completed"),
                    occurrence(dueDate = day(4).toString(), status = "completed"),
                    occurrence(dueDate = day(3).toString(), status = "completed"),
                )

            // Compute the tail extension through yesterday.
            val computedRows =
                CatchUpTailCompute.extendHabitTail(
                    task = task(),
                    occurrences = occurrences,
                    existingRows = existingRows,
                    today = today,
                )

            // The compute should produce rows for day(2) and day(1) — both gap days.
            assertThat(computedRows).hasSize(2)
            assertThat(computedRows[0].dayKey).isEqualTo(day(2).toString())
            assertThat(computedRows[0].score).isEqualTo(0.0)
            assertThat(computedRows[1].dayKey).isEqualTo(day(1).toString())
            assertThat(computedRows[1].score).isEqualTo(0.0)

            // Persist the computed rows through the JDBC store.
            store().upsertHabitMetrics(computedRows)

            // Read back and assert the persisted set matches the compute output.
            val persisted = store().getHabitMetricsForHabit("h1")
            assertThat(persisted).hasSize(2)
            assertThat(persisted.map { it.dayKey })
                .containsExactly(day(2).toString(), day(1).toString())
                .inOrder()
            assertThat(persisted.map { it.score }).containsExactly(0.0, 0.0).inOrder()

            // No row for today — the tail extends through yesterday only.
            assertThat(persisted.map { it.dayKey }).doesNotContain(today.toString())
        }
}
