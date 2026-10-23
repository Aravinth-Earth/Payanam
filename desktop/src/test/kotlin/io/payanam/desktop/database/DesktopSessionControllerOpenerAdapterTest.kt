//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import com.google.common.truth.Truth.assertThat
import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.database.jvm.PayanamDatabaseSession
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.data.UninstalledDataLayer
import io.payanam.shared.database.DatabaseBootIssue
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.database.DatabaseOpenOutcome
import io.payanam.shared.database.handleOrNull
import io.payanam.shared.database.refusalIssue
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.UnifiedLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * Tests for the desktop's unlock OPENER - the one that routes a shared-screen unlock through the
 * session controller instead of opening a second, dropped session.
 *
 * Three responsibilities, one per case, and each names the defect it would catch:
 *
 *  - `readOnly = true` is REFUSED. The contract makes it a request a platform must refuse, and
 *    `openAndInstall` has no read-only mode, so honouring it as a read-write open would mutate a
 *    database the caller only meant to inspect.
 *  - an ALREADY-OPEN controller short-circuits. `openAndInstall` begins with `closeAndUninstall`, so
 *    re-entering it on the re-auth path (`onSilentUnlockRequested` -> `PASSPHRASE_UNLOCK`) would close
 *    and reinstall the live session underneath retained ViewModels still holding the closed connection.
 *  - a not-open controller leaves `LocalDataLayer` INSTALLED, which is the entire point of the change:
 *    the collaborators used to hold a bare `DesktopDatabaseOpenerAdapter`, whose handle was dropped, so
 *    an unlocked desktop shell read empty lists.
 *
 * ## WHAT IS REUSED, AND WHAT IS NOT
 *
 * Reused from `DesktopDatabaseSessionControllerTest` (which is the fixture `DesktopDatabaseSessionPortTest`
 * and `DesktopSharedStateExecutionTest` also follow): the REAL SQLCipher file built with
 * `DriverManager.getConnection("jdbc:sqlcipher:...")` and `PayanamDatabaseSession.forGeneratedFixture`, the
 * injected `open` lambda as the way to drive both outcomes without a real driver open, the capturing
 * [UnifiedLogger], and [LocalDataLayer.resetForTests] in BOTH `@Before` and `@After` - the both-directions
 * reset the `DesktopNavHostDestinationRenderTest` KDoc explains, because test classes in this module share
 * one JVM and the holder is process-wide.
 *
 * NOT reused, deliberately: the passphrase vault, the security store and the compose rule. The adapter's
 * `verifyPassphrase` delegates to `DesktopDatabaseOpenerAdapter` and is not one of the three
 * responsibilities under test, so a security store and a temp security directory would be scaffolding with
 * nothing to assert through. No screen is mounted either - this is the collaborator contract, and
 * `DesktopNavHostDestinationRenderTest` already owns the rendering claim.
 */
class DesktopSessionControllerOpenerAdapterTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "session-opener-adapter-test"
    private val records = mutableListOf<Triple<String, String, Map<String, Any?>>>()

    private lateinit var databasePath: Path
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

    /** How many times the controller actually reached its injected open. The measure of "a reopen". */
    private var openCalls = 0

    /**
     * The adapter logs through [AppLogger] and the controller through its own injected logger, so one
     * capture serves both and the refusal can be asserted as REPORTED rather than swallowed.
     */
    private val capturingLogger =
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
                records.add(Triple("i", message, data))
            }

            override fun w(
                source: String,
                message: String,
                data: Map<String, Any?>,
            ) {
                records.add(Triple("w", message, data))
            }

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
        AppLogger.install(capturingLogger)
        databasePath = tempFolder.newFolder("database").toPath().resolve("payanam.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection = DriverManager.getConnection("jdbc:sqlcipher:${databasePath.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            // `journal_notes` is the table the JVM note repository reads, so the installed-layer assertion
            // below is a real query against a real encrypted file, not a property that merely does not
            // throw. Timestamps are ISO local strings because the note mapper parses them.
            statement.execute(
                """
                CREATE TABLE journal_notes (
                    id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, details TEXT,
                    lifeIntentionCategory TEXT NOT NULL, dimension_id TEXT, day_key TEXT NOT NULL,
                    created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                "INSERT INTO journal_notes VALUES ('note-1', 'Shared-screen unlock', 'read me', " +
                    "'FINANCE', NULL, '2026-09-30', '2026-09-30T09:15:00', '2026-09-30T09:15:00')",
            )
        }
        session = PayanamDatabaseSession.forGeneratedFixture(connection, schemaVersion = 21)
        LocalDataLayer.resetForTests()
        records.clear()
        openCalls = 0
    }

    @After
    fun tearDown() {
        // Both directions, for the reason `LocalDataLayer.resetForTests` documents: a failing assertion
        // aborts the body before it can clean up, so neither reset can live at the end of a case.
        LocalDataLayer.resetForTests()
        AppLogger.resetForTests()
        records.clear()
        if (!session.isClosed) session.close()
    }

    private fun controller(open: suspend (String, CharArray) -> DatabaseOpenResult) =
        DesktopDatabaseSessionController(
            databaseFilePath = { databasePath.toAbsolutePath().toString() },
            open = open,
            logger = capturingLogger,
        )

    /** An open that ALWAYS succeeds, counting its calls: an extra call is a reopen, and nothing else. */
    private fun countingAcceptingOpen(): suspend (String, CharArray) -> DatabaseOpenResult =
        { _, _ ->
            openCalls++
            DatabaseOpenResult.Opened(session)
        }

    /**
     * Whether reading a repository through the shared holder fails. The uninstalled layer throws on every
     * access by design, so `false` means a layer is genuinely installed and reachable.
     */
    private fun readingNotesFails(): Boolean = runCatching { LocalDataLayer.current.notes }.isFailure

    @Test
    fun `a read-only request is refused and the controller is never asked to open`(): Unit =
        runBlocking {
            // Refuses rather than accepts, so a mutant that drops the `readOnly` guard is caught TWICE:
            // the issue type below stops matching and `openCalls` stops being 0.
            val sessionController =
                controller { _, _ ->
                    openCalls++
                    DatabaseOpenResult.Refused(DatabaseBootIssue(type = DatabaseBootIssueType.OPEN_FAILED))
                }

            val outcome =
                DesktopSessionControllerOpenerAdapter(sessionController).open(
                    databasePath = databasePath.toString(),
                    passphrase = passphrase.toCharArray(),
                    readOnly = true,
                )

            assertThat(outcome).isInstanceOf(DatabaseOpenOutcome.Refused::class.java)
            // The adapter's OWN refusal, not one passed up from an open that was attempted and failed.
            assertThat(outcome.refusalIssue()?.type)
                .isEqualTo(DatabaseBootIssueType.NON_REPAIRABLE_GENERIC)
            assertThat(openCalls).isEqualTo(0)
            assertThat(sessionController.isOpen).isFalse()
            // A read-only probe must not leave repositories behind either.
            assertThat(LocalDataLayer.current).isSameInstanceAs(UninstalledDataLayer)
            assertThat(readingNotesFails()).isTrue()
            // Reported, not swallowed: a silent read-only refusal is indistinguishable from a stuck unlock.
            assertThat(records.map { it.first }).contains("w")
        }

    @Test
    fun `an already-open controller reports opened and is not reopened underneath the live session`(): Unit =
        runBlocking {
            val sessionController = controller(countingAcceptingOpen())
            sessionController.openAndInstall(passphrase.toCharArray())
            assertThat(openCalls).isEqualTo(1)
            val installedLayer = LocalDataLayer.current

            val outcome =
                DesktopSessionControllerOpenerAdapter(sessionController).open(
                    databasePath = databasePath.toString(),
                    passphrase = passphrase.toCharArray(),
                    readOnly = false,
                )

            // True, not merely tolerated: the session IS in force, so the shared screen's success callback
            // fires on a real open.
            assertThat(outcome).isInstanceOf(DatabaseOpenOutcome.Opened::class.java)
            assertThat(outcome.refusalIssue()).isNull()
            // The hazard, measured rather than described: `openAndInstall` closes and uninstalls before it
            // opens, so a second call here would close the session live ViewModels still hold.
            assertThat(openCalls).isEqualTo(1)
            assertThat(session.isClosed).isFalse()
            assertThat(sessionController.isOpen).isTrue()
            // The SAME layer instance: not uninstalled and reinstalled underneath those ViewModels.
            assertThat(LocalDataLayer.current).isSameInstanceAs(installedLayer)
            assertThat(readingNotesFails()).isFalse()
            // The returned handle is the controller's to own: closing what the screen was given closes
            // nothing, which is the only way a short-circuit can be safe.
            outcome.handleOrNull()?.close()
            assertThat(session.isClosed).isFalse()
            assertThat(readingNotesFails()).isFalse()
        }

    @Test
    fun `a not-open controller opens through the controller and leaves the shared layer installed`(): Unit =
        runBlocking {
            val sessionController = controller(countingAcceptingOpen())
            // The precondition the change exists for: nothing installed, so every read fails loudly.
            assertThat(sessionController.isOpen).isFalse()
            assertThat(readingNotesFails()).isTrue()

            val outcome =
                DesktopSessionControllerOpenerAdapter(sessionController).open(
                    databasePath = databasePath.toString(),
                    passphrase = passphrase.toCharArray(),
                    readOnly = false,
                )

            // Neither throws nor refuses needlessly: a fresh unlock must reach `openAndInstall`.
            assertThat(outcome).isInstanceOf(DatabaseOpenOutcome.Opened::class.java)
            assertThat(outcome.refusalIssue()).isNull()
            assertThat(openCalls).isEqualTo(1)
            assertThat(sessionController.isOpen).isTrue()
            // The point of the change, asserted as an OBSERVABLE install rather than an `Opened`: the
            // holder is the shared uninstalled singleton until a platform installs over it.
            assertThat(LocalDataLayer.current).isNotSameInstanceAs(UninstalledDataLayer)
            // And the installed layer answers a REAL repository query over the real encrypted file - the
            // empty-list screen this replaces returned nothing and looked like a working app.
            assertThat(
                LocalDataLayer.current.notes
                    .getAllNotes()
                    .first()
                    .map { it.title },
            ).containsExactly("Shared-screen unlock")
        }
}
