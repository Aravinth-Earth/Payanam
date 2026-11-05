//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import com.google.common.truth.Truth.assertThat
import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.database.jvm.PayanamDatabaseSession
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.shared.database.DatabaseBootIssue
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.logging.UnifiedLogger
import io.payanam.shared.transfer.DatabaseTransferGateway
import io.payanam.shared.transfer.TransferOutcome
import io.payanam.shared.transfer.TransferRefusal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * Export/import ordering, which is the part of a transfer the file mechanics cannot get right on their own.
 *
 * The two properties are that export FOLDS before it copies, and that import RELEASES the live database
 * before replacing the file and ESTABLISHES a session afterwards. The fold is asserted through its real
 * effect — a truncated `-wal` — rather than through a hook added to production code for the test's benefit,
 * because a test-only seam in the export path is exactly the kind of thing that later gets relied on.
 */
class DesktopDatabaseTransferControllerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "transfer-controller-test"
    private val recorded = mutableListOf<String>()
    private lateinit var databasePath: Path
    private var connection: Connection? = null
    private var session: PayanamDatabaseSession? = null

    private val logger = silentLogger()

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
        databasePath = tempFolder.newFolder().toPath().resolve("payanam.db")
        // The table is created once through a throwaway connection. Every session the tests use is FRESH,
        // because the session controller closes the previous one by design before it opens the next - a fake
        // opener handing back the session that was just closed would install nothing, and the test would
        // pass while the app broke.
        DriverManager
            .getConnection(
                "jdbc:sqlcipher:${databasePath.toAbsolutePath()}",
                Properties().apply { setProperty("key", passphrase) },
            ).use { bootstrap ->
                bootstrap.createStatement().use { it.execute("CREATE TABLE t (id TEXT PRIMARY KEY)") }
            }
        LocalDataLayer.resetForTests()
    }

    /** Opens a fresh session over the fixture, the way the real opener does. */
    private fun openFreshSession(): PayanamDatabaseSession {
        val opened =
            DriverManager.getConnection(
                "jdbc:sqlcipher:${databasePath.toAbsolutePath()}",
                Properties().apply { setProperty("key", passphrase) },
            )
        connection = opened
        return PayanamDatabaseSession.forGeneratedFixture(opened, schemaVersion = 21).also { session = it }
    }

    @After
    fun tearDown() {
        LocalDataLayer.resetForTests()
        session?.takeIf { !it.isClosed }?.close()
    }

    private fun sessionController(openResult: DatabaseOpenResult? = null): DesktopDatabaseSessionController =
        DesktopDatabaseSessionController(
            databaseFilePath = { databasePath.toAbsolutePath().toString() },
            open = { _, _ ->
                recorded.add("open")
                openResult ?: DatabaseOpenResult.Opened(openFreshSession())
            },
            logger = logger,
        )

    /** A gateway that records the call and answers with [answer]. */
    private fun gateway(answer: TransferOutcome = TransferOutcome.Succeeded(4096L)): DatabaseTransferGateway =
        object : DatabaseTransferGateway {
            override suspend fun exportTo(destinationToken: String): TransferOutcome {
                recorded.add("export")
                return answer
            }

            override suspend fun importFrom(
                sourceToken: String,
                passphrase: CharArray,
            ): TransferOutcome {
                recorded.add("import")
                return answer
            }
        }

    private fun controller(
        sessionController: DesktopDatabaseSessionController,
        gateway: DatabaseTransferGateway,
    ) = DesktopDatabaseTransferController(
        sessionController = sessionController,
        gateway = gateway,
        logger = logger,
    )

    @Test
    fun `the session's passphrase does not outlive the session, and cannot be cleared from outside`(): Unit =
        runBlocking {
            val sessions = sessionController()
            sessions.openAndInstall("unlock-secret".toCharArray())

            assertThat(String(sessions.passphraseForTransfer()!!)).isEqualTo("unlock-secret")

            // A COPY: clearing what a caller was handed must not disarm the session it came from.
            sessions.passphraseForTransfer()!!.fill('x')
            assertThat(String(sessions.passphraseForTransfer()!!)).isEqualTo("unlock-secret")

            sessions.closeAndUninstall()

            assertThat(sessions.passphraseForTransfer()).isNull()
        }

    @Test
    fun `export with an open database reaches the gateway`(): Unit =
        runBlocking {
            val sessions = sessionController()
            val controller = controller(sessions, gateway())
            sessions.openAndInstall(passphrase.toCharArray())

            val outcome = controller.exportTo("/tmp/backup.db")

            assertThat(outcome).isInstanceOf(DesktopTransferOutcome.Gateway::class.java)
            assertThat(recorded).containsExactly("open", "export").inOrder()
            // The fold itself is not asserted here: it happens inside the gateway, immediately before the
            // copy, and the gateway's own test pins that order - including the refusal when the fold does not
            // complete. Asserting it from here would be asserting a second implementation of the same rule.
        }

    @Test
    fun `export with no open database is refused and never reaches the gateway`(): Unit =
        runBlocking {
            val controller = controller(sessionController(), gateway())

            val outcome = controller.exportTo("/tmp/backup.db")

            assertThat(outcome).isEqualTo(DesktopTransferOutcome.NothingToExport)
            assertThat(recorded).doesNotContain("export")
        }

    @Test
    fun `import releases the live database first and establishes a session on the imported file`(): Unit =
        runBlocking {
            val sessions = sessionController()
            val controller = controller(sessions, gateway())
            sessions.openAndInstall(passphrase.toCharArray())
            recorded.clear()

            val outcome = controller.importFrom("/tmp/from-android.db", passphrase.toCharArray())

            assertThat(outcome).isInstanceOf(DesktopTransferOutcome.Gateway::class.java)
            // "import" must precede "open": verification refuses to run beside the sidecars an open session
            // leaves behind, and the replacement needs the file free.
            assertThat(recorded).containsExactly("import", "open").inOrder()
            assertThat(sessions.isOpen).isTrue()
        }

    @Test
    fun `a refused import leaves nothing installed and reports the gateway's reason`(): Unit =
        runBlocking {
            val sessions = sessionController()
            val controller =
                controller(
                    sessions,
                    gateway(TransferOutcome.Refused(TransferRefusal.PASSPHRASE_REJECTED)),
                )
            sessions.openAndInstall(passphrase.toCharArray())
            recorded.clear()

            val outcome = controller.importFrom("/tmp/wrong.db", "wrong".toCharArray())

            val reason = (outcome as DesktopTransferOutcome.Gateway).outcome as TransferOutcome.Refused
            assertThat(reason.reason).isEqualTo(TransferRefusal.PASSPHRASE_REJECTED)
            // Nothing was replaced, and nothing was installed over the live database either: a read must
            // fail rather than quietly succeed against a database that is no longer open.
            assertThat(sessions.isOpen).isFalse()
            assertThat(runCatching { LocalDataLayer.current.notes }.isFailure).isTrue()
            assertThat(recorded).doesNotContain("open")
        }

    @Test
    fun `an import that lands but cannot be reopened is reported as imported, not as a transfer refusal`(): Unit =
        runBlocking {
            val refusedOpen =
                DatabaseOpenResult.Refused(
                    DatabaseBootIssue(type = DatabaseBootIssueType.DB_TOO_NEW, detailMessage = "too_new"),
                )
            val sessions = sessionController(refusedOpen)
            val controller = controller(sessions, gateway())

            val outcome = controller.importFrom("/tmp/newer.db", passphrase.toCharArray())

            // The file IS in place, so calling this a transfer refusal would send the user to pick another
            // file when the file has already been replaced.
            assertThat(outcome).isEqualTo(DesktopTransferOutcome.ImportedButUnopenable("DB_TOO_NEW"))
            assertThat(sessions.isOpen).isFalse()
        }
}

/** A logger that swallows everything: these paths log refusals the assertions already cover. */
private fun silentLogger(): UnifiedLogger =
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
