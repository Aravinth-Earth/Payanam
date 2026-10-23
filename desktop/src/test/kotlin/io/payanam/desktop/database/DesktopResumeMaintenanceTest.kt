//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import com.google.common.truth.Truth.assertThat
import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.database.jvm.PayanamDatabaseSession
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.shared.logging.UnifiedLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The resume path's three properties, exercised on the FUNCTION rather than on the window.
 *
 * **WHY THIS FILE EXISTS.** `runStartupMaintenance` had exactly one call site — `openAndInstall` — so a
 * laptop that suspended across midnight never advanced yesterday's recurring tasks, and nothing about that
 * looks like a bug: the app showed yesterday's state exactly as it had at suspend time, indefinitely, until
 * it was restarted. Android has no such window because `MainActivity.onStart` re-runs its maintenance on
 * every foreground return. The fix adds a resume entry point and a window observer; the three properties
 * below are the ones a future edit could silently break.
 *
 * **The window observer is not asserted here, and cannot be.** A Compose window needs a display and a frame
 * to fire `ON_RESUME`; the honest assertion for the trigger is a source-level wiring check (the idiom
 * `DesktopBackupRotationWiringTest` already uses in this module), not a headless render. What IS
 * headlessly assertable — and is the part with real logic in it — is the function the observer calls, and
 * that is what these cases pin.
 *
 * **The maintenance steps are injected, and that is the point of the concurrency case.** "Cannot run twice
 * concurrently" is a claim about a function body, so proving it requires substituting that body and counting
 * its invocations. Counting invocations of the real `RecurrenceManager` instead would assert on which rows a
 * WAL-backed JDBC layer happens to write — a different, weaker claim. The production default is the real
 * steps (`runDesktopRecurrenceMaintenance`), so this substitution cannot become a second implementation of
 * the maintenance itself; it replaces a collaborator, not the rule.
 *
 * **The overlap case uses a gate rather than a sleep.** A sleep would make the test assert on timing, which
 * is the flakiest possible assertion and would pass for the wrong reason on a slow machine. The injected
 * maintenance parks on a `CompletableDeferred` the test completes only once it has confirmed the first call
 * is in flight, so the second call provably overlaps the first.
 *
 * **The logger is a stub, not the desktop singleton.** `UnifiedLogger.getInstance()` throws in a unit test
 * with no `initialize()` call, and `d()` is a no-op unless debug logging is on — so a case asserting on a
 * debug line would assert on nothing. These cases assert on the injected function's call count, which is
 * observable regardless of log level.
 */
class DesktopResumeMaintenanceTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "resume-test"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

    /** How many times the maintenance body actually ran. The property under test in every case below. */
    private val maintenanceRuns = AtomicInteger()

    @Before
    fun setUp() {
        // The desktop logger is process-wide, and this class constructs collaborators that reach for it:
        // initialize it HERE (fully qualified, because several siblings import the SHARED UnifiedLogger under
        // the same name) rather than relying on a sibling class's leak - the shared-JVM class order is not a
        // contract, and a solo run of this class must work. The directory outlives the class on purpose.
        io.payanam.desktop.UnifiedLogger.resetForTests()
        io.payanam.desktop.UnifiedLogger.initialize(
            logsDirectory = java.nio.file.Files.createTempDirectory("payanam-desktop-test-logs"),
        )
        val path = tempFolder.newFolder().toPath().resolve("payanam.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection = DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE journal_notes (
                    id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, details TEXT,
                    lifeIntentionCategory TEXT NOT NULL, dimension_id TEXT, day_key TEXT NOT NULL,
                    created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }
        session = PayanamDatabaseSession.forGeneratedFixture(connection, schemaVersion = 21)
        LocalDataLayer.resetForTests()
        maintenanceRuns.set(0)
    }

    @After
    fun tearDown() {
        LocalDataLayer.resetForTests()
        if (!session.isClosed) session.close()
    }

    /**
     * A controller whose maintenance counts its own invocations, so "ran once" is a number rather than an
     * inference from what a real rollover happened to write.
     */
    private fun controller(maintenance: suspend (DesktopPayanamDataLayer) -> Unit = { maintenanceRuns.incrementAndGet() }) =
        DesktopDatabaseSessionController(
            databaseFilePath = { tempFolder.root.resolve("payanam.db").absolutePath },
            open = { _, _ -> DatabaseOpenResult.Opened(session) },
            logger = silentLogger,
            maintenance = maintenance,
        )

    /**
     * Drops every level, including `e`: this file asserts call counts, and a logger that throws or that
     * reaches for the desktop singleton would make a passing assertion depend on log initialization order.
     */
    private val silentLogger =
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
            ) = Unit

            override fun w(
                source: String,
                message: String,
                data: Map<String, Any?>,
            ) = Unit

            override fun e(
                source: String,
                message: String,
                error: Throwable?,
                data: Map<String, Any?>,
            ) = Unit

            override fun eSync(
                source: String,
                message: String,
                error: Throwable?,
                data: Map<String, Any?>,
            ) = Unit
        }

    /**
     * Waits for the run `openAndInstall` fires, then zeroes the counter.
     *
     * That run is **fire-and-forget** — `runStartupMaintenance` launches it on its own scope — so a count
     * read straight after an open is a race with it. Isolated, the read usually wins and sees 0; in a full
     * suite the launched run lands first and the read sees its 1, which is how these cases measured a `1`
     * where the case expected a baseline and a `2` where it expected one resume. The subject of every case
     * below is THE RESUME's run, so this waits for the open's run to land — bounded, on the counter itself
     * rather than on a sleep — and then zeroes it. What each case asserts is then purely resume-driven.
     */
    private suspend fun settleStartupMaintenance() {
        withTimeout(5_000) {
            while (maintenanceRuns.get() == 0) yield()
        }
        maintenanceRuns.set(0)
    }

    @Test
    fun `a resume with no session open runs no maintenance at all`(): Unit =
        runBlocking {
            val controller = controller()

            // The observer fires on the window's FIRST focus, which on a fresh launch is long before any
            // passphrase is typed. A resume there must be a no-op rather than a failure report: there is no
            // layer to repair or advance, and logging an error for the pre-unlock half of every launch
            // would make a normal state look like a defect.
            controller.runMaintenanceAfterResume()

            assertThat(maintenanceRuns.get()).isEqualTo(0)
            assertThat(controller.isOpen).isFalse()
        }

    @Test
    fun `one resume after an open runs the maintenance exactly once`(): Unit =
        runBlocking {
            val controller = controller()
            controller.openAndInstall(passphrase.toCharArray())
            // The open's own run is subtracted before the resume, so this counts resume-driven runs only.
            settleStartupMaintenance()

            controller.runMaintenanceAfterResume()

            assertThat(maintenanceRuns.get()).isEqualTo(1)
        }

    @Test
    fun `two overlapping resumes run the maintenance once and not twice`(): Unit =
        runBlocking {
            // THE OVERLAP HAS TO BE REAL, and on a single-threaded dispatcher it is not. `runBlocking` runs
            // its children on one event loop, so a second `async` cannot even start until the first
            // suspends - and by then the first has already left the guarded section and released the flag,
            // which is how this case first measured a `2` where the property says `1`. That was the test
            // creating no overlap, not the guard failing to close one.
            //
            // So both calls get their own worker, and the second is JOINED before the first is released:
            // while it is joined the first is still parked inside the maintenance body, so the duplicate
            // provably arrives inside the guarded section. A sleep would assert on timing instead, and
            // would pass for the wrong reason on a slow machine.
            val firstInFlight = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val concurrentPeak = AtomicInteger()
            val inFlight = AtomicInteger()
            // The SAME lambda serves the run the open fires AND the resumes this case drives, so the parking
            // has to be armed only once the open's run is out of the way, and the open's run must not park at
            // all. Left ungated it did both kinds of damage: it completed `firstInFlight` for the test, so
            // the "overlap" was measured against a call that was already parked by someone else, and it held
            // the guard open, so the first resume was skipped instead of running. Neither shows up as a
            // wrong-looking failure - only as a wrong count at the end.
            val parkResumes = AtomicBoolean(false)
            val controller =
                controller {
                    maintenanceRuns.incrementAndGet()
                    if (!parkResumes.get()) return@controller
                    concurrentPeak.updateAndGet { previous -> maxOf(previous, inFlight.incrementAndGet()) }
                    firstInFlight.complete(Unit)
                    release.await()
                    inFlight.decrementAndGet()
                }
            controller.openAndInstall(passphrase.toCharArray())
            // Wait for the open's own run to land (bounded, on the counter) and zero the count, then arm the
            // parking, so everything asserted below is resume-driven and the guard is free when it starts.
            settleStartupMaintenance()
            parkResumes.set(true)

            val first = async(Dispatchers.Default) { controller.runMaintenanceAfterResume() }
            firstInFlight.await()
            // Bounded on purpose: with the guard in place this returns at once, because the duplicate is
            // skipped; without it the second call would park on `release`, and the timeout turns that into
            // a failure with a name instead of a test that hangs until the suite's own timeout.
            val second = async(Dispatchers.Default) { controller.runMaintenanceAfterResume() }
            withTimeout(5_000) { second.join() }
            release.complete(Unit)
            first.await()

            // Skipped, not queued: the guard's job is to stop the second CALLER from starting a second
            // pass, not to run the whole repair set again once the first finishes.
            assertThat(maintenanceRuns.get()).isEqualTo(1)
            assertThat(concurrentPeak.get()).isEqualTo(1)
        }

    @Test
    fun `a resume after the session is closed runs no maintenance`(): Unit =
        runBlocking {
            val controller = controller()
            controller.openAndInstall(passphrase.toCharArray())
            // Baseline taken only once the open's fire-and-forget run has landed; a count read before it
            // would be a race, and the assertion below is about the resume, not about the open.
            settleStartupMaintenance()
            assertThat(maintenanceRuns.get()).isEqualTo(0)

            controller.closeAndUninstall()

            // The layer kept for the resume path is released with the session, so this takes the same early
            // return as the never-opened case rather than running the recurrence steps over a connection
            // that has been closed.
            controller.runMaintenanceAfterResume()

            assertThat(maintenanceRuns.get()).isEqualTo(0)
        }
}
