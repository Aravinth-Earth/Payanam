//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.platform

import com.google.common.truth.Truth.assertThat
import io.payanam.database.jvm.PayanamDatabaseSession
import io.payanam.desktop.database.DesktopPayanamDataLayer
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.flags.FeatureFlagsState
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
 * `DesktopStartupRecovery` over a real session: the returned step list, and the minimal-build early return.
 *
 * **WHY THIS FILE EXISTS.** The point of the port is that the desktop startup sequence is the SAME sequence
 * Android runs, described by ONE shared enumeration ([StartupRecoveryStep]) rather than by two lists that
 * drift. Drift is the failure mode nobody sees: adding a fourth step to the enum, wiring it on Android,
 * and forgetting the desktop compiles cleanly, passes every existing test, and shows up weeks later as a
 * desktop build that silently skips a repair pass. [runsEveryStepInTheSharedStepList] is the test that
 * catches it — it fails the moment a constant exists in the enum that the desktop does not run, which is
 * the whole reason this class exists.
 *
 * **A SET, not a list.** The contract is "every step the platform can name is run", not "in this order".
 * `runCatching` per step also means a throwing step is deliberately dropped from the returned list, so a
 * set comparison is what keeps a reordering (harmless) from looking like a missing step (not harmless)
 * while still failing on either an added-but-unrun or a run-but-undeclared constant.
 *
 * **The flag is a global, so it is restored.** `PlatformFeatureFlags` is a process-wide `object`: the
 * minimal-build case flips it for real and would otherwise leak `minimalModeEnabled = true` into whatever
 * runs next in the same JVM and fail a case that has nothing to do with this file. Hence the `finally`,
 * and the `resetForTests()` in `tearDown` as the backstop for a case that dies before its own restore.
 */
class DesktopStartupRecoveryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * The guard key the backfill writes LAST, and the one value both platforms share: its presence in
     * `app_settings` is what stops a desktop database from being backfilled a second time on the phone.
     * Spelled here rather than imported because it is `private` to the backfill, and a test that reproduced
     * the constant would be testing its own copy rather than the key the production code uses.
     */
    private val backfillGuardKey = "score_rollup_backfill_v1_done"

    private val passphrase = "startup-recovery-test"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession
    private lateinit var layer: DesktopPayanamDataLayer

    /** What the installed logger recorded during a case, so a step failure is observable rather than silent. */
    private val captured = mutableListOf<String>()

    @Before
    fun setUp() {
        // The desktop logger is process-wide, and this class constructs collaborators that reach for it:
        // initialize it HERE (fully qualified, because several siblings import the SHARED UnifiedLogger under
        // the same name) rather than relying on a sibling class's leak - the shared-JVM class order is not a
        // contract, and a solo run of this class must work. The directory outlives the class on purpose.
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
        // Hand-written DDL, because the session helper below only WRAPS an already-open connection: it migrates
        // nothing, so a database reaches these steps with no tables at all and every step throws with "no such
        // table: tasks". The tables are the ones the three steps touch, transcribed from the schema export
        // (core/database/schemas/io.payanam.database.PayanamDatabase/22.json) rather than from memory - and note
        // that `tasks` spells its dimension column `dimension_id` while the metric tables spell theirs
        // `dimensionId`, a difference this fixture has to reproduce or it will not catch a read that confuses them.
        connection.createStatement().use { statement ->
            DDL.forEach { statement.execute(it) }
        }
        // The recovery steps run their own SQL — recurring-task repair, auto-advance, score-rollup backfill —
        // so the fixture is the real migrated schema on a real connection rather than a stub. A stub would
        // make every step throw, and `runCatching` drops throwing steps from the list, so a stub would make
        // this file pass for the wrong reason: an empty list would satisfy nothing here, but it would also
        // hide a genuinely skipped step behind a schema error.
        session = PayanamDatabaseSession.forGeneratedFixture(connection, schemaVersion = 21)
        LocalDataLayer.resetForTests()
        layer = DesktopPayanamDataLayer(session)
        // The full feature set, so the minimal-build case starts from a known "recovery runs" baseline
        // instead of inheriting whatever a previous test in this JVM left installed.
        PlatformFeatureFlags.resetForTests()
        // Installs a logger that reports step failures, because the implementation catches each step's exception
        // and drops that step from the returned list - so without this, a step that fails is invisible to the
        // test and reads exactly like a step that was never attempted.
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
        // Restores the DEFAULT flag state, not the last installed one — which is the one that matters for
        // a global, since "whatever was there before" is itself something another test set.
        PlatformFeatureFlags.resetForTests()
        AppLogger.resetForTests()
        LocalDataLayer.resetForTests()
        if (!session.isClosed) session.close()
    }

    /**
     * THE DRIFT TEST. Every constant in the shared enumeration must be run by the desktop.
     *
     * This is the assertion that fails if someone adds a step to [StartupRecoveryStep] and does not run
     * it here — the drift this whole port exists to prevent. Add a constant to the enum and this case goes
     * red with the missing step named in the failure; that red is the point, not an inconvenience.
     */
    @Test
    fun `every step in the shared step list is run`(): Unit =
        runBlocking {
            val steps = DesktopStartupRecovery(layer).runStartupRecovery()

            // Compared as sets, so a reordering does not fail this and an unrun constant does.
            assertThat(steps.toSet()).isEqualTo(StartupRecoveryStep.entries.toSet())
            // The other half of the claim, and the reason a logger is installed at all: the implementation drops a
            // throwing step from the list, so without this an empty list and a step that was never attempted look
            // identical. Nothing threw here, and the captured level-E entries are what says so.
            assertThat(captured.filter { it.startsWith("E ") }).isEmpty()
        }

    /**
     * A build without the habits module must report nothing, and must do so by NOT RUNNING rather than by
     * running and finding every step a no-op.
     *
     * An empty list is the observable half; the early return before the flag check is the part with the
     * logic, and it is what keeps a habits-less build from paying for three repair passes against tables
     * its schema does not have.
     */
    @Test
    fun `nothing is reported when the build has no habits module`(): Unit =
        runBlocking {
            PlatformFeatureFlags.install(FeatureFlagsState(minimalModeEnabled = true))

            try {
                val steps = DesktopStartupRecovery(layer).runStartupRecovery()

                assertThat(steps).isEmpty()
            } finally {
                // `PlatformFeatureFlags` is a global object: leaving it flipped breaks every other test in
                // this JVM, so the restore is not optional bookkeeping.
                PlatformFeatureFlags.resetForTests()
            }
        }

    /**
     * THE REPAIR TEST: habit history with NO roll-up rows in it must come out of the post-unlock recovery
     * path REBUILT, not still empty.
     *
     * The two cases above are about the SHAPE of the run — which steps run, and the case where none of them
     * do. This one is about the PAYLOAD: it seeds the one input the backfill cannot invent for itself (a
     * recurring task carrying a legacy `num/den` rule plus real past occurrences) and then asserts the three
     * metric tables were actually populated by the step.
     *
     * **This case fails if `ScoreRollupBackfillJdbc(layer.session).runIfNeeded()` is removed from
     * [DesktopStartupRecovery].** Nothing else here could notice, and the reason is worth stating so nobody
     * "simplifies" the assertions away: deleting just that one call inside the existing `runCatching` block
     * leaves an empty block that still SUCCEEDS, so the step is still appended to the returned list and
     * `every step in the shared step list is run` stays green. The only evidence that the work happened is
     * the rows, which is why the assertions below read the metric tables rather than the step list.
     *
     * **Today is not scored, so the expected days are derived rather than hard-coded.** The L1 loop stops
     * before today (it stays pending, awaiting the cascade that this platform does not have), so the rows
     * run from the first due day up to and including YESTERDAY. Asserting a fixed count instead of a derived
     * day list would make this case fail the morning after it was written, which is the fastest way to turn
     * a real assertion into one everyone learns to ignore.
     *
     * **Why the seed is shaped to keep the recurrence steps quiet.** `repairStuckRecurringTasks` and
     * `autoAdvanceRecurringTasks` run BEFORE the backfill and both end at
     * `TaskRepositoryJvm.updateRecurrenceState`, which marks the day dirty via `markDirtyForTaskDay`. That
     * writes the `daily_insights` table, which the original fixture did not have — so the fixture now
     * creates it (see the note on that DDL row) rather than depending on that write never happening.
     * Belt and braces beyond that: the task is seeded `pending` (which is the condition the repair step
     * skips on) with a NULL `dueDate` (which is the condition the advance step skips on), so in the happy
     * path both steps read and neither writes — and if either ever stops skipping, the case still runs
     * instead of failing on a missing table.
     */
    @Test
    fun `score roll-up backfill builds the missing metric rows`(): Unit =
        runBlocking {
            val today = LocalDate.now()
            // Three past days, all completed: L1 scores each of them 1.0, and the sparse-to-dense carry-forward
            // makes the dimension and day scores exactly 1.0 too, so the aggregate assertions are exact rather
            // than approximate.
            val firstDay = today.minusDays(3)
            val scoredDays = listOf(firstDay, today.minusDays(2), today.minusDays(1))
            val createdAt = firstDay.atStartOfDay().toString()
            val timestamp = LocalDateTime.now().toString()

            connection.createStatement().use { statement ->
                // The dimension is seeded because L3 reads `life_dimensions.weight` to weight the day score.
                // Without the row the day metrics still build (unknown dimensions fall back to an equal weight
                // of 1.0), so its absence would quietly weaken the case rather than break it.
                statement.executeUpdate(
                    """
                    INSERT INTO life_dimensions
                    (id, key, label, color, sortOrder, isActive, weight, createdAt, updatedAt)
                    VALUES ('dim-health', 'health', 'Health', '#4caf50', 1, 1, 1.0, '$timestamp', '$timestamp')
                    """.trimIndent(),
                )
                // '1/1' is the legacy uHabits num/den form, which the backfill must convert to the canonical
                // 'CONFIG:...' string and persist back onto this column. A rule that is already canonical
                // would pass the conversion assertion vacuously, so the legacy form is the point.
                statement.executeUpdate(
                    """
                    INSERT INTO tasks
                    (id, title, status, createdAt, updatedAt, durationMinutes, impactLevel, goalAlignment,
                     energyLevel, controlLevel, lifeIntentionCategory, dimension_id, day_key,
                     recurrenceEnabled, recurrenceRule, dayBoundaryHour)
                    VALUES ('habit-1', 'Morning walk', 'pending', '$createdAt', '$timestamp', 30, 'medium',
                     'low', 'medium', 'high', 'health', 'dim-health', NULL, 1, '1/1', 0)
                    """.trimIndent(),
                )
                // The occurrences are the non-negotiable seed: `runIfNeeded` takes its fresh-install fast
                // path (writing only the guard, computing nothing) when there are neither legacy rules nor
                // occurrences. The legacy rule above also satisfies that guard on its own, but seeding both
                // means neither half is load-bearing on its own — which is what makes "history existed" and
                // "the rule was legacy" independent inputs to the same rebuild.
                scoredDays.forEachIndexed { index, day ->
                    statement.executeUpdate(
                        """
                        INSERT INTO task_occurrences (id, taskId, dueDate, status, createdAt)
                        VALUES ('occ-$index', 'habit-1', '$day', 'completed', '$timestamp')
                        """.trimIndent(),
                    )
                }
            }

            val steps = DesktopStartupRecovery(layer).runStartupRecovery()

            assertThat(steps).contains(StartupRecoveryStep.BACKFILL_SCORE_ROLLUPS)
            // The narrow form of the no-throwing check the first case makes globally. A backfill that ran and
            // FAILED reports through `AppLogger.current.e` under its own step name rather than by throwing, so
            // the absence of that line is what distinguishes "rebuilt" from "reported a failure and moved on".
            assertThat(captured.filter { it.contains("BACKFILL_SCORE_ROLLUPS") }).isEmpty()
            assertThat(dayKeysOf("SELECT dayKey FROM habit_metrics ORDER BY dayKey ASC"))
                .isEqualTo(scoredDays.map { it.toString() })
            assertThat(dayKeysOf("SELECT dayKey FROM dimension_metrics ORDER BY dayKey ASC"))
                .isEqualTo(scoredDays.map { it.toString() })
            assertThat(dayKeysOf("SELECT dayKey FROM day_metrics ORDER BY dayKey ASC"))
                .isEqualTo(scoredDays.map { it.toString() })
            // Today is pending, not scored: no metric table may carry a row for it. Stated as its own
            // assertion because it is the invariant most likely to be "fixed" by someone who has not
            // noticed this platform has no cascade to complete the day.
            assertThat(dayKeysOf("SELECT dayKey FROM habit_metrics WHERE dayKey = '$today'")).isEmpty()
            assertThat(dayKeysOf("SELECT dayKey FROM day_metrics WHERE dayKey = '$today'")).isEmpty()
            // The roll-up the whole table exists for: three completed days average to a perfect day score.
            assertThat(
                scalarOf("SELECT dayScore FROM day_metrics WHERE dayKey = '$firstDay'") as Double,
            ).isWithin(1e-9).of(1.0)
            // The rule was rewritten in place, not merely read. This is the backfill's other job, and it is
            // asserted because it is the one write that is invisible to every row-count assertion above.
            assertThat(
                scalarOf("SELECT recurrenceRule FROM tasks WHERE id = 'habit-1'") as String,
            ).startsWith("CONFIG:")
            // The guard, written LAST inside the backfill's transaction: its presence alongside the rows is
            // what says the rebuild committed rather than rolled back, and it is the single key both
            // platforms share, so a stray value here would skip the backfill on the phone too.
            assertThat(scalarOf("SELECT value FROM app_settings WHERE key = '$backfillGuardKey'")).isNotNull()
        }

    /** One column, one row: the single-value reads above, so each assertion reads as the question it asks. */
    private fun scalarOf(sql: String): Any? =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                if (rows.next()) rows.getObject(1) else null
            }
        }

    /** The day column of a roll-up table, ordered, for the set comparison against the expected days. */
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
 * The tables the three recovery steps touch, transcribed from the schema export
 * (`core/database/schemas/io.payanam.database.PayanamDatabase/22.json`).
 *
 * `tasks` spells its dimension column `dimension_id`; the metric tables spell theirs `dimensionId`. A read that
 * confuses the two throws on a real database, so the fixture reproduces both spellings rather than a tidy one.
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
        // Added for the roll-up case, and it is NOT one of the three steps' own tables. The two recurrence
        // steps reach `TaskRepositoryJvm.updateRecurrenceState`, which marks the affected day dirty via
        // `markDirtyForTaskDay` -> `markLensDayDirtySync`, and that is an upsert into `daily_insights`
        // (columns taken verbatim from the same schema export). Creating the table here means the fixture no
        // longer depends on those steps happening to skip: the seeded task is shaped to skip anyway, but a
        // test whose pass condition is "the writes never fired" is one refactor away from failing for a reason
        // that has nothing to do with the assertion.
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
