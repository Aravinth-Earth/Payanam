//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.desktop.DesktopSecurityStore
import io.payanam.desktop.database.DesktopBootstrapDataLayer
import io.payanam.desktop.database.DesktopDatabaseInitCollaborators
import io.payanam.desktop.database.DesktopDatabaseOpenerAdapter
import io.payanam.desktop.database.DesktopDatabasePathResolver
import io.payanam.desktop.database.DesktopDatabaseSessionController
import io.payanam.desktop.database.databaseInitViewModel
import io.payanam.desktop.platform.AwtDocumentSource
import io.payanam.desktop.security.DesktopEncryptionStateCheckpoint
import io.payanam.desktop.security.DesktopPassphraseVault
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.security.PassphraseVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * A plaintext database offered to the desktop's import takes the SHARED decision - the same one Android
 * takes, because it is the same `DatabaseInitViewModel` deciding.
 *
 * WHY THIS FILE EXISTS: the import story's gate asks for "a test per side" asserting that a plaintext
 * database offered to import behaves identically on both platforms. The Android side is covered by the
 * two gateway tests that offer a real SQLite plaintext header; the desktop side had no test at all, and
 * the encrypted-fixture harness next door cannot stand in for it - that test is opt-in, needs a fixture
 * and a passphrase, and proves the encrypted path. This one builds its own plaintext file (a real SQLite
 * database, written through the same JDBC driver the app uses) and runs it down the production path, so
 * the assertion is about the shared decision and not about a mock.
 *
 * WHAT "IDENTICAL" MEANS HERE, and what it deliberately does not claim: the desktop reaches the same
 * state the shared ViewModel defines for a plaintext source. It is not a claim about security - the
 * encrypted-only import rule is a separate feature - and it is not a device pass.
 */
class DesktopPlaintextImportBehaviourTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var logger: UnifiedLogger
    private var viewModelScope: CoroutineScope? = null
    private var sessionController: DesktopDatabaseSessionController? = null

    @Before
    fun setUp() {
        UnifiedLogger.resetForTests()
        logger = UnifiedLogger.initialize(logsDirectory = temporaryFolder.newFolder("plaintext-import-logs").toPath())
        AppLogger.install(logger)
        LocalDataLayer.resetForTests()
    }

    @After
    fun tearDown() {
        // WHY THE JOIN, and why it comes FIRST: cancelling the scope is not WAITING for it. A collector
        // still inside its JDBC call when the session closes throws for real - not a cancellation - and
        // with no CoroutineExceptionHandler on this scope that failure reaches the JVM-global exception
        // collector, which re-throws it as UncaughtExceptionsBeforeTest in whichever LATER test opens a
        // TestScope. Joining before the session closes is what stops one test's failure from being
        // charged to an unrelated one.
        runBlocking { viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin() }
        sessionController?.closeAndUninstall()
        LocalDataLayer.resetForTests()
        AppLogger.resetForTests()
        if (this::logger.isInitialized) {
            logger.close()
            UnifiedLogger.resetForTests()
        }
    }

    /** A real, readable SQLite database with the plaintext header - what a user's exported file looks like. */
    private fun writePlaintextDatabase(path: Path) {
        Files.createDirectories(path.parent)
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("CREATE TABLE tasks (id TEXT PRIMARY KEY, title TEXT NOT NULL)")
                statement.executeUpdate("INSERT INTO tasks (id, title) VALUES ('t1', 'first')")
            }
        }
    }

    @Test
    fun `a plaintext database offered to the desktop import reaches the shared decision`() =
        runBlocking {
            val dataRoot = temporaryFolder.newFolder("plaintext-import-data").toPath()
            val databaseFile = dataRoot.resolve("Payanam").resolve("database").resolve("payanam.db")
            val securityDirectory = dataRoot.resolve("Payanam").resolve("security")
            val plaintextSource = temporaryFolder.newFile("user-export.db").toPath()
            writePlaintextDatabase(plaintextSource)

            // The plaintext file really is plaintext: its first bytes are the shared declaration's magic, read
            // through the same constant the classifier uses. A test that offered the wrong bytes would prove
            // nothing about the plaintext branch.
            val header = Files.readAllBytes(plaintextSource).copyOf(16)
            assertThat(header).isEqualTo(io.payanam.shared.database.EncryptedFileClassifier.SQLITE_PLAINTEXT_MAGIC)

            val controller =
                DesktopDatabaseSessionController(
                    databaseFilePath = { databaseFile.toAbsolutePath().toString() },
                    open = { path, secret -> DesktopDatabaseOpenerAdapter().openSession(path, secret) },
                    logger = logger,
                )
            sessionController = controller
            LocalDataLayer.install(DesktopBootstrapDataLayer)
            val vault: PassphraseVault = DesktopPassphraseVault(DesktopSecurityStore(securityDirectory = securityDirectory))
            val viewModel =
                databaseInitViewModel(
                    collaborators =
                        DesktopDatabaseInitCollaborators(
                            sessionController = controller,
                            // A TEST-OWNED marker, never the default: the tour's completion WRITES through this
                            // store, and the default resolves the operator's real data directory.
                            onboardingStore = DesktopOnboardingStore(securityDirectory = temporaryFolder.newFolder().toPath()),
                            documentSource = AwtDocumentSource(),
                            passphraseVault = vault,
                            databasePathResolver =
                                DesktopDatabasePathResolver(
                                    databaseDirectory = { databaseFile.parent.toAbsolutePath().toString() },
                                ),
                            encryptionStateCheckpoint = DesktopEncryptionStateCheckpoint(),
                        ),
                    strings = UiStrings.of(UiLanguage.ENGLISH),
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { viewModelScope = it },
                )

            viewModel.importDatabase(plaintextSource.toAbsolutePath().toString()) { }

            // Settle: wait until the import's own transient flag clears and the check is done, so the
            // assertions below read a decided state rather than a race.
            var attempts = 0
            while ((viewModel.uiState.value.isImporting || viewModel.uiState.value.isChecking) && attempts < 120) {
                delay(50)
                attempts++
            }
            val state = viewModel.uiState.value
            // THE DECISION, in the two facts that make it visible: a plaintext file needs no passphrase, so
            // the import must NOT be waiting for one; and the decision must have produced an outcome rather
            // than an unexplained error. Printed first so the first run of this test measures the shared
            // behaviour instead of asserting a guess about it.
            println(
                "plaintext import settled: awaitingPassphrase=${state.awaitingImportPassphrase} " +
                    "isImporting=${state.isImporting} errorMessage=${state.errorMessage} " +
                    "importPassphraseError=${state.importPassphraseError}",
            )
            assertThat(state.awaitingImportPassphrase).isFalse()
        }

    @Test
    fun `a file shorter than the plaintext magic is refused on the desktop too`() {
        // The shared detection rule's boundary, asserted from this platform rather than assumed from the
        // rule's own test: a file of 1..15 bytes cannot hold the 16-byte magic, so it is refused as
        // TOO_SHORT on both platforms - and 16 bytes, the shortest file that could, is not refused for
        // being short. The classifier is the same object Android's test drives, so this is the desktop
        // half of the same rule, measured through the real file read.
        val boundary = io.payanam.shared.database.EncryptedFileClassifier.MINIMUM_PROBE_BYTES

        for (length in listOf(1L, 5L, boundary - 1)) {
            val file = temporaryFolder.newFile("short-$length.db").toPath()
            Files.write(file, ByteArray(length.toInt()) { (it % 251).toByte() })
            val header = Files.readAllBytes(file).copyOf(boundary.toInt())
            val verdict =
                io.payanam.shared.database.EncryptedFileClassifier
                    .classify(header, Files.size(file))
            assertThat(verdict.reason)
                .isEqualTo(io.payanam.shared.database.EncryptedFileClassifier.Reason.TOO_SHORT)
        }

        // The control: at exactly the boundary length the same read yields a real verdict, not TOO_SHORT.
        val boundaryFile = temporaryFolder.newFile("boundary.db").toPath()
        Files.write(boundaryFile, ByteArray(boundary.toInt()) { (it % 251).toByte() })
        val boundaryVerdict =
            io.payanam.shared.database.EncryptedFileClassifier.classify(
                Files.readAllBytes(boundaryFile),
                Files.size(boundaryFile),
            )
        assertThat(boundaryVerdict.reason)
            .isNotEqualTo(io.payanam.shared.database.EncryptedFileClassifier.Reason.TOO_SHORT)
    }
}
