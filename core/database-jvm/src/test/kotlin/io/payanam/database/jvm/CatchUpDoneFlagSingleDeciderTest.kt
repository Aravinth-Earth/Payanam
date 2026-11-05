//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.database.jvm.backfill.ScoreRollupBackfillJdbc
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime
import java.util.Properties

/**
 * Tests that the score roll-up backfill done-flag is ONE shared decision, not two platform-local ones.
 *
 * The guard row in `app_settings` (key = 'score_rollup_backfill_v1_done', value = '1') is the single
 * source of truth for whether the backfill has run. Android writes it (ScoreRollupBackfillService),
 * desktop reads it (ScoreRollupBackfillJdbc.isGuardSet) and writes it (writeGuardRow). This test
 * proves that a row written by one platform is honored by the other — there is no per-platform
 * "done" flag.
 */
class CatchUpDoneFlagSingleDeciderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val guardKey = "score_rollup_backfill_v1_done"
    private val guardValue = "1"
    private val passphrase = "catch-up-done-flag-test"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession
    private lateinit var backfill: ScoreRollupBackfillJdbc

    @Before
    fun setUp() {
        val path = tempFolder.newFolder().toPath().resolve("payanam.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection = DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)

        connection.createStatement().use { statement ->
            DDL.forEach { statement.execute(it) }
        }

        session = PayanamDatabaseSession(connection, 21, DatabaseOpenMode.READ_WRITE)
        backfill = ScoreRollupBackfillJdbc(session)
    }

    @After
    fun tearDown() {
        session.close()
    }

    @Test
    fun `android written flag causes desktop skip`(): Unit = runBlocking {
        val timestamp = LocalDateTime.now().toString()
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "INSERT INTO app_settings (key, value, updatedAt) VALUES ('$guardKey', '$guardValue', '$timestamp')"
            )
        }

        val result = backfill.runIfNeeded()

        assertThat(result.guardAlreadySet).isTrue()
        assertThat(result.ran).isFalse()
        assertThat(result.habitMetricRows).isEqualTo(0)
        assertThat(result.dimensionMetricRows).isEqualTo(0)
        assertThat(result.dayMetricRows).isEqualTo(0)

        assertThat(countRows("habit_metrics")).isEqualTo(0)
        assertThat(countRows("dimension_metrics")).isEqualTo(0)
        assertThat(countRows("day_metrics")).isEqualTo(0)
    }

    @Test
    fun `no flag causes desktop to run and write guard`(): Unit = runBlocking {
        val result = backfill.runIfNeeded()

        assertThat(result.freshInstallFastPath).isTrue()
        assertThat(result.ran).isFalse()
        assertThat(result.guardAlreadySet).isFalse()

        val value = scalarOf("SELECT value FROM app_settings WHERE key = '$guardKey'")
        assertThat(value).isEqualTo(guardValue)
    }

    @Test
    fun `guard row matches android shape`(): Unit = runBlocking {
        backfill.runIfNeeded()

        // Android's ScoreRollupBackfillService.BACKFILL_DONE_KEY = "score_rollup_backfill_v1_done"
        // Android writes value = "1" (ScoreRollupBackfillService.kt:113, :144)
        val key = scalarOf("SELECT key FROM app_settings WHERE key = '$guardKey'")
        val value = scalarOf("SELECT value FROM app_settings WHERE key = '$guardKey'")

        assertThat(key).isEqualTo("score_rollup_backfill_v1_done")
        assertThat(value).isEqualTo("1")
    }

    private fun scalarOf(sql: String): Any? =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                if (rows.next()) rows.getObject(1) else null
            }
        }

    private fun countRows(table: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows ->
                if (rows.next()) rows.getInt(1) else 0
            }
        }
}

private val DDL: List<String> = listOf(
    """
        CREATE TABLE app_settings (
            key TEXT PRIMARY KEY NOT NULL,
            value TEXT,
            updatedAt TEXT NOT NULL
        )
    """.trimIndent(),
    """
        CREATE TABLE tasks (
            id TEXT PRIMARY KEY NOT NULL,
            title TEXT NOT NULL,
            description TEXT,
            status TEXT NOT NULL DEFAULT 'pending',
            dueDate TEXT,
            createdAt TEXT NOT NULL,
            updatedAt TEXT NOT NULL,
            completedAt TEXT,
            archivedAt TEXT,
            recurrenceEnabled INTEGER NOT NULL DEFAULT 0,
            recurrenceRule TEXT,
            durationMinutes INTEGER NOT NULL DEFAULT 60,
            impactLevel TEXT NOT NULL DEFAULT 'Moderate Impact',
            goalAlignment TEXT NOT NULL DEFAULT 'Moderate Alignment',
            energyLevel TEXT NOT NULL DEFAULT 'Moderate',
            controlLevel TEXT NOT NULL DEFAULT 'Office/Colleagues Dependent',
            lifeIntentionCategory TEXT NOT NULL DEFAULT 'Career & Work',
            dimension_id TEXT,
            day_key TEXT,
            explicitUrgency REAL,
            focusRequired REAL,
            recurrenceStrategy TEXT,
            blockedReason TEXT,
            completionRate REAL,
            externalDependency TEXT,
            notificationMode TEXT,
            customNotificationMinutes INTEGER,
            taskScore REAL,
            lastOccurrenceDate TEXT,
            dayBoundaryHour INTEGER NOT NULL DEFAULT 0,
            import_source TEXT,
            import_id TEXT,
            imported_at TEXT,
            import_batch_id TEXT
        )
    """.trimIndent(),
    """
        CREATE TABLE task_occurrences (
            id TEXT PRIMARY KEY NOT NULL,
            taskId TEXT NOT NULL,
            dueDate TEXT NOT NULL,
            completedAt TEXT,
            actualCompletedAt TEXT,
            actualDurationMinutes INTEGER,
            status TEXT NOT NULL,
            statusReason TEXT,
            createdAt TEXT NOT NULL,
            completionRate REAL,
            note TEXT
        )
    """.trimIndent(),
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
    """
        CREATE TABLE day_metrics (
            dayKey TEXT PRIMARY KEY NOT NULL,
            dayScore REAL NOT NULL,
            runningAvg REAL NOT NULL,
            progress REAL NOT NULL,
            streakPos INTEGER NOT NULL,
            streakNet INTEGER NOT NULL,
            posContinue INTEGER NOT NULL
        )
    """.trimIndent(),
    """
        CREATE TABLE life_dimensions (
            id TEXT PRIMARY KEY NOT NULL,
            key TEXT NOT NULL,
            label TEXT NOT NULL,
            description TEXT,
            color TEXT NOT NULL,
            icon TEXT,
            sortOrder INTEGER NOT NULL,
            isActive INTEGER NOT NULL,
            weight REAL NOT NULL,
            createdAt TEXT NOT NULL,
            updatedAt TEXT NOT NULL
        )
    """.trimIndent(),
)
