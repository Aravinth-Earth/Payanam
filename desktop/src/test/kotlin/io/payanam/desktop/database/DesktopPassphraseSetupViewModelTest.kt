//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import com.google.common.truth.Truth.assertThat
import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.desktop.DesktopAppPaths
import io.payanam.desktop.DesktopSecurityStore
import io.payanam.desktop.security.DesktopBiometricUnlockPrompt
import io.payanam.desktop.security.DesktopForgottenPassphraseReset
import io.payanam.desktop.security.DesktopPassphraseVault
import io.payanam.presentation.database.DatabasePassphraseSetupViewModel
import io.payanam.shared.database.DatabaseBootIssue
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.UnifiedLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The desktop's passphrase-setup factory: WHICH vault the setup gate writes through.
 *
 * WHY THIS FILE EXISTS: the setup gate writes the passphrase that the unlock gate later verifies, so a
 * vault built here would hold a credential no other gate can check - which is why the factory takes the
 * caller's vault rather than constructing one. That is a wiring decision the compiler cannot enforce (a
 * second `DesktopPassphraseVault` over a second store would compile and pass any happy-path test), so what
 * is asserted here is IDENTITY: the passphrase the gate accepts lands in the exact vault instance the
 * caller handed over - observed through that vault's own state, not through a double that would agree with
 * anything.
 *
 * It does NOT exercise the migration path: the factory builds [DesktopDatabasePathResolver] with no injected
 * directory, so the gate's database path is the per-user one. This class therefore points `user.home` at a
 * per-test [TemporaryFolder] root for every method - asserted, not assumed - so the valid-passphrase case is
 * decided by an empty root on every host rather than by the operator's machine happening to be clean.
 *
 * That matters because the opposite was true until now: on a host whose per-user directory holds an
 * unencrypted `payanam.db`, the migration probe refuses it, the setup path returns without signalling
 * success, and this class hung to its timeout instead of failing. The old note here claimed the case "passes
 * either way"; it did not.
 *
 * The *unencrypted-database-present* arm is still not covered by this class. That is a real gap, stated
 * plainly rather than papered over.
 */
class DesktopPassphraseSetupViewModelTest {
    private val loggers = mutableListOf<UnifiedLogger>()

    private companion object {
        /**
         * The real-time budget the awaited setup is allowed, sized for the work rather than for a quiet
         * machine. See [awaitSetupSuccess] for why the wait has to be real time at all, and for why this
         * number is not the 5s it replaced.
         */
        const val SETTLE_MILLIS = 60_000L

        /** The system property the desktop's path resolver reads its per-user home from. */
        const val USER_HOME_PROPERTY = "user.home"
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** The fake home this class points `user.home` at for the duration of one test method. */
    private lateinit var isolatedHome: Path
    private lateinit var realUserHome: String

    /**
     * Every case in this class must be independent of the operator's machine.
     *
     * Without this, a case reaches the real per-user database path: if that path holds an unencrypted
     * database the setup path refuses it and never signals success, so the case waits out its whole budget
     * instead of failing. Pointing the property at a per-test folder makes the outcome the same everywhere.
     */
    @Before
    fun isolateTheDatabasePathFromTheRealHome() {
        realUserHome = System.getProperty(USER_HOME_PROPERTY)
        isolatedHome = tempFolder.newFolder("fake-home").toPath()
        System.setProperty(USER_HOME_PROPERTY, isolatedHome.toString())
    }

    @After
    fun tearDown() {
        System.setProperty(USER_HOME_PROPERTY, realUserHome)
        loggers.forEach { AppLogger.resetForTests() }
    }

    @Test
    fun `the factory's database path lands inside the isolated root, never the operator's home`() {
        val resolver = DesktopDatabasePathResolver()

        val directory = Paths.get(resolver.databaseDirectory())

        // Isolation that is believed rather than asserted is how the host-dependence hid: a resolver that
        // ignored the override would return the operator's directory and fail this line.
        assertThat(directory.startsWith(isolatedHome)).isTrue()
        assertThat(directory.startsWith(Paths.get(realUserHome))).isFalse()
        assertThat(Files.exists(directory.resolve(DesktopAppPaths.DATABASE_FILE_NAME))).isFalse()
    }

    @Test
    fun `the factory builds the shared ViewModel - not a desktop copy - and a mismatch reaches no vault`() {
        val harness = collaboratorsOverARealStore()
        val viewModel = databasePassphraseSetupViewModel(harness.collaborators)

        // The shared class itself, so the desktop reuses Android's implementation rather than a second one
        // that merely compiles.
        assertThat(viewModel).isInstanceOf(DatabasePassphraseSetupViewModel::class.java)

        viewModel.configurePassphrase(
            passphrase = "LocalPass123!",
            confirmPassphrase = "DifferentPass1!",
            onSuccess = {},
        )

        // The mismatch arm: named reason, and the vault was not touched - the no-side-effect half.
        assertThat(viewModel.uiState.value.errorReasonCode).isEqualTo("mismatch")
        assertThat(harness.vault.hasPassphraseConfigured()).isFalse()
        viewModel.dispose()
    }

    @Test
    fun `a valid passphrase pair lands in THE vault the caller handed over`() {
        val harness = collaboratorsOverARealStore()
        val viewModel = databasePassphraseSetupViewModel(harness.collaborators)
        val succeeded = CompletableDeferred<Unit>()

        viewModel.configurePassphrase(
            passphrase = "LocalPass123!",
            confirmPassphrase = "LocalPass123!",
            onSuccess = { succeeded.complete(Unit) },
        )
        // The save is launched on the ViewModel's own scope, so the wait is on the success callback -
        // bounded, on the signal itself rather than a sleep. WHY THAT WAIT MUST BE REAL TIME, and why this
        // test therefore cannot be made deterministic, is [awaitSetupSuccess]'s argument; it is not a
        // stopwatch race left in place by choice.
        awaitSetupSuccess(succeeded)

        // The identity proof: the state observed is the CALLER's vault, so the chars went through the one
        // instance the unlock gate is built over - a second vault would still read as unconfigured.
        assertThat(harness.vault.hasPassphraseConfigured()).isTrue()
        assertThat(viewModel.uiState.value.errorReasonCode).isNull()
        viewModel.dispose()
    }

    @Test
    fun `a too-short passphrase is refused before any vault call`() {
        val harness = collaboratorsOverARealStore()
        val viewModel = databasePassphraseSetupViewModel(harness.collaborators)

        viewModel.configurePassphrase(
            passphrase = "short",
            confirmPassphrase = "short",
            onSuccess = {},
        )

        assertThat(viewModel.uiState.value.errorReasonCode).isNotNull()
        assertThat(viewModel.uiState.value.errorReasonCode).isNotEqualTo("mismatch")
        assertThat(harness.vault.hasPassphraseConfigured()).isFalse()
        viewModel.dispose()
    }

    @Test
    fun `the factory logs the vault it was handed, by class name`() {
        val harness = collaboratorsOverARealStore()
        val recording = RecordingLogger()
        AppLogger.install(recording)
        loggers += recording

        val viewModel = databasePassphraseSetupViewModel(harness.collaborators)

        val line =
            recording.infos.singleOrNull {
                it.first == "DesktopDatabasePassphraseSetupViewModel.databasePassphraseSetupViewModel"
            }
        assertThat(line).isNotNull()
        assertThat(line!!.third["vault"]).isEqualTo(harness.vault.javaClass.simpleName)
        viewModel.dispose()
    }

    /** Captures INFO lines so the factory's own log is an assertion, not a hope. */
    private class RecordingLogger : UnifiedLogger {
        val infos = mutableListOf<Triple<String, String, Map<String, Any?>>>()

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
            infos += Triple(source, message, data)
        }

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
     * Waits, in REAL time, for the setup ViewModel to report success.
     *
     * WHY A REAL-TIME BUDGET IS REQUIRED HERE AND NOT A `runTest`/VIRTUAL-TIME DRIVE. This test exercises
     * the DESKTOP FACTORY, and every collaborator on the awaited path is the real platform object doing
     * real work on real dispatchers. Concretely, the chain is
     * `configurePassphrase` -> `scope.launch` (on the factory's default `Dispatchers.Default`) ->
     * `withContext(ioDispatcher)` where `ioDispatcher` is the ViewModel's own `Dispatchers.IO` default ->
     * `migrateExistingDatabaseIfNeeded` -> `DesktopSecurityStore.configurePassphrase` ->
     * `PassphraseKdf.derive` (PBKDF2-HMAC-SHA256 at 210,000 iterations) -> a real temp-file write.
     * The work is therefore CPU-bound wall-clock time and real filesystem IO, not a coroutine chain that a
     * test scheduler can run to completion.
     *
     * AND IT CANNOT BE INJECTED AWAY FROM THIS TEST: `DatabasePassphraseSetupViewModel` takes
     * `ioDispatcher` as a constructor parameter precisely so a test can drive virtual time
     * (`core/presentation`'s own suite does exactly that), but BOTH factories - the shared
     * `passphraseSetupViewModel(...)` and the desktop `databasePassphraseSetupViewModel(...)` - do not
     * forward it, and constructing the ViewModel directly would bypass the factory whose wiring this
     * whole class exists to assert. `:desktop` also does not depend on `kotlinx-coroutines-test`, so there
     * is no idle-advancing dispatcher to drive. Virtual time is unreachable without weakening what is
     * under test, so the budget is kept and made generous rather than pretended away.
     *
     * WHY 60s AND NOT THE 5s THIS REPLACED. The 5s budget asserted on a stopwatch, and the awaited work is
     * a 210k-iteration PBKDF2 derivation plus file IO - measured at ~56ms median on an idle 8-core host,
     * but scaling with load, and this test shares one Gradle invocation with the whole desktop coverage
     * gate. Under CPU contention 5s is not a safety margin, it is a coin flip, and it failed the full gate
     * for reasons having nothing to do with the wiring being asserted. The budget is now a HANG DETECTOR,
     * not a timing assertion: a dead wiring still trips it (the callback never fires), but a loaded
     * machine does not. 60s is far above any plausible legitimate duration and far below the point where a
     * genuinely stuck test wastes a run; the assertion it guards - that the caller's vault holds the
     * passphrase - is unchanged.
     */
    private fun awaitSetupSuccess(succeeded: CompletableDeferred<Unit>) {
        runBlocking { withTimeout(SETTLE_MILLIS) { succeeded.await() } }
    }

    /**
     * A real [DesktopSecurityStore] over a temp directory, so the vault under test is the same class the
     * shell builds - not a test double that would agree with anything.
     *
     * Returns the collaborators alongside the exact vault they were built from, because the assertions are
     * about IDENTITY: a factory that constructed its own second instance is the defect, and that can only
     * be observed by comparing against the original.
     */
    private fun collaboratorsOverARealStore(): Harness {
        val securityDirectory: Path = Files.createTempDirectory("payanam-setup-test")
        val store = DesktopSecurityStore(securityDirectory = securityDirectory)
        val vault = DesktopPassphraseVault(store)
        val databaseFile = securityDirectory.resolve("payanam.db")
        val reset = DesktopForgottenPassphraseReset(vault, databaseFile)
        val collaborators =
            DesktopPassphraseUnlockCollaborators(
                vault = vault,
                biometricPrompt = DesktopBiometricUnlockPrompt,
                forgottenPassphraseReset = reset,
                sessionController =
                    DesktopDatabaseSessionController(
                        databaseFilePath = { databaseFile.toAbsolutePath().toString() },
                        open = { _, _ ->
                            DatabaseOpenResult.Refused(
                                DatabaseBootIssue(type = DatabaseBootIssueType.OPEN_FAILED),
                            )
                        },
                        logger = AppLogger.current,
                    ),
                databaseFile = databaseFile.toFile(),
            )
        return Harness(collaborators = collaborators, vault = vault)
    }

    /** What a test needs to make an identity claim: the collaborators AND the vault they were built from. */
    private data class Harness(
        val collaborators: DesktopPassphraseUnlockCollaborators,
        val vault: DesktopPassphraseVault,
    )
}
