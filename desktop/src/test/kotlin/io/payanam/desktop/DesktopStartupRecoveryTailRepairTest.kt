//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.database.jvm.PayanamDatabaseSession
import io.payanam.desktop.database.DesktopPayanamDataLayer
import io.payanam.desktop.platform.DesktopStartupRecovery
import io.payanam.presentation.flags.PlatformFeatureFlags
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.UnifiedLogger
import io.payanam.shared.platform.StartupRecoveryStep
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Properties

/**
 * Proves the desktop OPEN PATH repairs a lagging habit tail: [DesktopStartupRecovery] — the recovery the
 * desktop runs at startup — must invoke [io.payanam.database.jvm.backfill.ScoreRollupBackfillJdbc.catchUpTailRepair]
 * so a habit whose stored [habit_metrics] stop before yesterday is extended through yesterday on open.
 *
 * **WHY THIS FILE EXISTS.** The backfill half of the recovery is proven by [DesktopStartupRecoveryTest];
 * the repair half is proven by `core/database-jvm`'s `CatchUpTailRepairOnOpenTest` (five cases over the
 * compute and the persistence). Neither proves the WIRING: that the desktop recovery actually CALLS the
 * repair on open. Delete `backfill.catchUpTailRepair()` from [DesktopStartupRecovery] and both existing
 * test files stay green — the step is still appended to the returned list, and the backfill still builds
 * its own rows. This file is the one that goes red.
 *
 * **The guard is set first, so the backfill skips and the repair is the only writer.** The backfill builds
 * L1 rows from the first occurrence through yesterday, which would make the habit current and the repair
 * a no-op — the assertions below would then pass because of the backfill, not the repair. Setting the
 * done-guard before the recovery runs isolates the repair: any row beyond the seeded lagging tail was
 * written by `catchUpTailRepair`, not by the backfill.
 *
 * **The seed is shaped to keep the recurrence steps quiet.** The task is seeded `pending` with a NULL
 * `dueDate`, so `repairStuckRecurringTasks` and `autoAdvanceRecurringTasks` both skip it — the same
 * idiom [DesktopStartupRecoveryTest] uses. The `daily_insights` table is created so that if either step
 * ever stops skipping, the case still runs instead of failing on a missing table.
 *
 * **Today is not scored, so the expected days are derived rather than hard-coded.** The repair stops at
 * yesterday (today stays pending), so the assertions derive their expected day from `LocalDate.now()`
 * rather than hard-coding a date that would make this case fail the morning after it was written.
 */
class DesktopStartupRecoveryTailRepairTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "startup-recovery-tail-repair-test"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession
    private lateinit var layer: DesktopPayanamDataLayer

    private val captured = mutableListOf<String>()

    @Before
    fun setUp() {
        io.payanam.desktop.UnifiedLogger
            .resetForTests()
        io.payanam.desktop.UnifiedLogger.initialize(
            logsDirectory =
                java.nio.file.Files
                    .createTempDirectory("payanam-desktop-test-logs"),
        )
        val path = tempFolder.newFolder().toPath().resolve("payanam.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection = DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            DDL.forEach { statement.execute(it) }
        }
        session = PayanamDatabaseSession.forGeneratedFixture(connection, schemaVersion = 21)
        layer = DesktopPayanamDataLayer(session)
        PlatformFeatureFlags.resetForTests()
        AppLogger.install(
            object : UnifiedLogger {
                override fun d(
                    source: String,
                    message: String,
                    data: Map<String, Any?>,
                ) = Unit

                override fun i(
                    source: String,
                    message: String,
                    data: Map<String, Any?>,
                ) {
                    captured += "I $source :: $message"
                }

                override fun w(
                    source: String,
                    message: String,
                    data: Map<String, Any?>,
                ) {
                    captured += "W $source :: $message"
                }

                override fun e(
                    source: String,
                    message: String,
                    error: Throwable?,
                    data: Map<String, Any?>,
                ) {
                    captured += "E $source :: $message :: ${error?.javaClass?.name}: ${error?.message}"
                }

                override fun eSync(
                    source: String,
                    message: String,
                    error: Throwable?,
                    data: Map<String, Any?>,
                ) {
                    captured += "E(sync) $source :: $message"
                }
            },
        )
        captured.clear()
    }

    @After
    fun tearDown() {
        PlatformFeatureFlags.resetForTests()
        AppLogger.resetForTests()
        if (!session.isClosed) session.close()
    }

    /**
     * THE WIRING TEST. A habit whose stored [habit_metrics] stop 8 days ago must come out of the
     * post-unlock recovery path EXTENDED through yesterday — by the repair, not by the backfill.
     *
     * The guard is set first so the backfill skips; the repair is therefore the only writer of rows
     * beyond the seeded lagging tail. The assertions read [habit_metrics] directly rather than the
     * step list, because the step list would be identical whether or not the repair ran.
     */
    @Test
    fun `open path repairs a lagging habit tail`(): Unit =
        runBlocking {
            val today = LocalDate.now()
            val yesterday = today.minusDays(1)
            // The seeded lagging tail stops here: 7 days before yesterday.
            val tailStop = today.minusDays(8)
            val timestamp = LocalDateTime.now().toString()

            connection.createStatement().use { statement ->
                // The dimension is seeded because L3 reads life_dimensions.weight; without it the day
                // metrics still build (unknown dimensions fall back to weight 1.0), so its absence
                // would quietly weaken the case rather than break it.
                statement.executeUpdate(
                    """
                    INSERT INTO life_dimensions
                    (id, key, label, color, sortOrder, isActive, weight, createdAt, updatedAt)
                    VALUES ('dim-health', 'health', 'Health', '#4caf50', 1, 1, 1.0, '$timestamp', '$timestamp')
                    """.trimIndent(),
                )
                // '1/1' is the legacy uHabits num/den form. The task is seeded pending with a NULL
                // dueDate so both recurrence steps skip it — the same idiom DesktopStartupRecoveryTest
                // uses to keep those steps quiet.
                statement.executeUpdate(
                    """
                    INSERT INTO tasks
                    (id, title, status, createdAt, updatedAt, durationMinutes, impactLevel, goalAlignment,
                     energyLevel, controlLevel, lifeIntentionCategory, dimension_id, day_key,
                     recurrenceEnabled, recurrenceRule, dayBoundaryHour)
                    VALUES ('habit-1', 'Morning walk', 'pending', '$timestamp', '$timestamp', 30, 'medium',
                     'low', 'medium', 'high', 'health', 'dim-health', NULL, 1, '1/1', 0)
                    """.trimIndent(),
                )
                // Occurrences on alternating days: the gap days (no occurrence) must be filled with
                // score 0.0 by the repair, which is the assertion that distinguishes a repair from a
                // backfill that only writes due days with occurrences.
                val occurrenceDays =
                    listOf(
                        tailStop,
                        today.minusDays(6),
                        today.minusDays(4),
                        today.minusDays(2),
                    )
                occurrenceDays.forEachIndexed { index, day ->
                    statement.executeUpdate(
                        """
                        INSERT INTO task_occurrences (id, taskId, dueDate, status, createdAt)
                        VALUES ('occ-$index', 'habit-1', '$day', 'completed', '$timestamp')
                        """.trimIndent(),
                    )
                }
                // The seeded lagging tail: one row at tailStop, so the habit is 7 days behind yesterday.
                statement.executeUpdate(
                    """
                    INSERT INTO habit_metrics
                    (habitId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue)
                    VALUES ('habit-1', '$tailStop', 1.0, 1.0, 1.0, 1, 1, 1)
                    """.trimIndent(),
                )
                // Set the done-guard so the backfill skips and the repair is the only writer.
                statement.executeUpdate(
                    """
                    INSERT INTO app_settings (key, value, updatedAt)
                    VALUES ('score_rollup_backfill_v1_done', '1', '$timestamp')
                    """.trimIndent(),
                )
            }

            val steps = DesktopStartupRecovery(layer).runStartupRecovery()

            assertThat(steps).contains(StartupRecoveryStep.BACKFILL_SCORE_ROLLUPS)
            // No step failed: a repair that threw would be dropped from the list and logged.
            assertThat(captured.filter { it.startsWith("E ") }).isEmpty()

            // The repair extended the tail through yesterday.
            val maxDay =
                scalarOf(
                    "SELECT MAX(dayKey) FROM habit_metrics WHERE habitId = 'habit-1'",
                ) as String
            assertThat(maxDay).isEqualTo(yesterday.toString())

            // At least one gap day (no occurrence) was filled with score 0.0.
            val gapDayCount =
                scalarOf(
                    "SELECT COUNT(*) FROM habit_metrics WHERE habitId = 'habit-1' AND score = 0.0",
                ) as Long
            assertThat(gapDayCount).isGreaterThan(0L)

            // No row for today: the repair stops at yesterday.
            assertThat(
                dayKeysOf("SELECT dayKey FROM habit_metrics WHERE habitId = 'habit-1' AND dayKey = '$today'"),
            ).isEmpty()

            // The guard was already set before the recovery ran, so the backfill skipped and the
            // repair was the only writer. The guard still reads '1'.
            assertThat(
                scalarOf("SELECT value FROM app_settings WHERE key = 'score_rollup_backfill_v1_done'"),
            ).isEqualTo("1")
        }

    private fun scalarOf(sql: String): Any? =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                if (rows.next()) rows.getObject(1) else null
            }
        }

    private fun dayKeysOf(sql: String): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                buildList {
                    while (rows.next()) add(rows.getString(1))
                }
            }
        }
}

/**
 * The tables the recovery steps touch, transcribed from the schema export
 * (core/database/schemas/io.payanam.database.PayanamDatabase/22.json) — the same DDL
 * [DesktopStartupRecoveryTest] uses, copied here because the original is private to that file.
 */
private val DDL: List<String> =
    listOf(
        """
            CREATE TABLE life_dimensions (
            "id" TEXT NOT NULL,
            "key" TEXT NOT NULL,
            "label" TEXT NOT NULL,
            "description" TEXT,
            "color" TEXT NOT NULL,
            "icon" TEXT,
            "sortOrder" INTEGER NOT NULL,
            "isActive" INTEGER NOT NULL,
            "weight" REAL NOT NULL,
            "createdAt" TEXT NOT NULL,
            "updatedAt" TEXT NOT NULL,
            PRIMARY KEY ("id")
        )
        """.trimIndent(),
        """
            CREATE TABLE tasks (
            "id" TEXT NOT NULL,
            "title" TEXT NOT NULL,
            "description" TEXT,
            "status" TEXT NOT NULL,
            "dueDate" TEXT,
            "createdAt" TEXT NOT NULL,
            "updatedAt" TEXT NOT NULL,
            "completedAt" TEXT,
            "archivedAt" TEXT,
            "recurrenceEnabled" INTEGER NOT NULL,
            "recurrenceRule" TEXT,
            "durationMinutes" INTEGER NOT NULL,
            "impactLevel" TEXT NOT NULL,
            "goalAlignment" TEXT NOT NULL,
            "energyLevel" TEXT NOT NULL,
            "controlLevel" TEXT NOT NULL,
            "lifeIntentionCategory" TEXT NOT NULL,
            "dimension_id" TEXT,
            "day_key" TEXT,
            "explicitUrgency" REAL,
            "focusRequired" REAL,
            "recurrenceStrategy" TEXT,
            "blockedReason" TEXT,
            "completionRate" REAL,
            "externalDependency" TEXT,
            "notificationMode" TEXT,
            "customNotificationMinutes" INTEGER,
            "taskScore" REAL,
            "lastOccurrenceDate" TEXT,
            "dayBoundaryHour" INTEGER NOT NULL,
            "import_source" TEXT,
            "import_id" TEXT,
            "imported_at" TEXT,
            "import_batch_id" TEXT,
            PRIMARY KEY ("id")
        )
        """.trimIndent(),
        """
            CREATE TABLE task_occurrences (
            "id" TEXT NOT NULL,
            "taskId" TEXT NOT NULL,
            "dueDate" TEXT NOT NULL,
            "completedAt" TEXT,
            "actualCompletedAt" TEXT,
            "actualDurationMinutes" INTEGER,
            "status" TEXT NOT NULL,
            "statusReason" TEXT,
            "createdAt" TEXT NOT NULL,
            "completionRate" REAL,
            "note" TEXT,
            PRIMARY KEY ("id")
        )
        """.trimIndent(),
        """
            CREATE TABLE app_settings (
            "key" TEXT NOT NULL,
            "value" TEXT,
            "updatedAt" TEXT NOT NULL,
            PRIMARY KEY ("key")
        )
        """.trimIndent(),
        """
            CREATE TABLE habit_metrics (
            "habitId" TEXT NOT NULL,
            "dayKey" TEXT NOT NULL,
            "score" REAL NOT NULL,
            "runningAvg" REAL NOT NULL,
            "progress" REAL NOT NULL,
            "streakPos" INTEGER NOT NULL,
            "streakNet" INTEGER NOT NULL,
            "posContinue" INTEGER NOT NULL,
            PRIMARY KEY ("habitId", "dayKey")
        )
        """.trimIndent(),
        """
            CREATE TABLE dimension_metrics (
            "dimensionId" TEXT NOT NULL,
            "dayKey" TEXT NOT NULL,
            "score" REAL NOT NULL,
            "runningAvg" REAL NOT NULL,
            "progress" REAL NOT NULL,
            "streakPos" INTEGER NOT NULL,
            "streakNet" INTEGER NOT NULL,
            "posContinue" INTEGER NOT NULL,
            PRIMARY KEY ("dimensionId", "dayKey")
        )
        """.trimIndent(),
        """
            CREATE TABLE day_metrics (
            "dayKey" TEXT NOT NULL,
            "dayScore" REAL NOT NULL,
            "runningAvg" REAL NOT NULL,
            "progress" REAL NOT NULL,
            "streakPos" INTEGER NOT NULL,
            "streakNet" INTEGER NOT NULL,
            "posContinue" INTEGER NOT NULL,
            PRIMARY KEY ("dayKey")
        )
        """.trimIndent(),
        """
            CREATE TABLE daily_insights (
            "id" TEXT NOT NULL,
            "day_key" TEXT NOT NULL,
            "module" TEXT NOT NULL,
            "dimension_id" TEXT,
            "planned_minutes" INTEGER,
            "actual_minutes" INTEGER,
            "focused_minutes" INTEGER,
            "completed_count" INTEGER,
            "total_count" INTEGER,
            "summary_json" TEXT,
            "generated_at" TEXT NOT NULL,
            PRIMARY KEY ("id")
        )
        """.trimIndent(),
    )
