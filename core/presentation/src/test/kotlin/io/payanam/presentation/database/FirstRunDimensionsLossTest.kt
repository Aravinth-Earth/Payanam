//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.payanam.presentation.database

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.LifeDimensionCatalogRepository
import io.payanam.presentation.preferences.AppPreferenceKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The first-run dimension-confirmation LOSS, pinned as a behaviour rather than as a line of code.
 *
 * THE BUG BEHIND THIS FILE: the first-run flow confirms life dimensions and then creates the database
 * and writes the catalog in one coroutine. A quit between those two steps leaves a database that exists
 * and a catalog that is empty, and because the rows only ever lived in a database that did not exist
 * yet at confirmation time, there was nowhere to have kept them. On the next launch the screen reopened
 * on the defaults with no hint that the choice was gone - a silent loss the user cannot recover from
 * and cannot even see. The fix does not prevent the loss (the pre-session data layer refuses every
 * write, so there is no durable home for the rows); it makes the loss VISIBLE by leaving one
 * `app_settings` intent row behind and saying so on the next launch.
 *
 * FOUR THINGS ARE WORTH PROVING, and one of them is the absence of the others:
 * A: the mark is already set at the instant the catalog write is asked for - a mark written after the
 *    write covers no window at all.
 * B: a clean run CLEARS it - the guard that stops the fix passing by marking every confirmation lost.
 * C: a launch over a mark left behind reports the loss - the whole point, in both status branches.
 * D: a launch with no mark stays silent - the guard against a false alarm, and against a status check
 *    that seeds the row it reads.
 *
 * WHY THE RESTART IS A SECOND VIEWMODEL OVER THE SAME FAKES: a real quit ends the process, so nothing
 * survives except what was durably written. A new [DatabaseInitViewModel] over the SAME
 * `FakeAppSettingsRepository` is the honest way to model that - it has no memory of the previous run
 * and can only report what the settings table still holds. It gets its OWN coroutine scope, because
 * [DatabaseInitViewModel.dispose] cancels the scope it was handed, and cancelling the first run's scope
 * is precisely what a quit is.
 *
 * NO TEST HERE READS PRODUCTION CODE STRUCTURE. Each one names the production statement that would have
 * to move for it to go red, so a revert is visible as a failure rather than as a silently weaker suite.
 */
class FirstRunDimensionsLossTest {
    private val testDispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(testDispatcher)

    private lateinit var dbDirectory: File
    private lateinit var pathResolver: FakeDatabasePathResolver
    private lateinit var sessionManager: FakeDatabaseSessionManager
    private lateinit var lifeDimensions: FakeLifeDimensionCatalogRepository
    private lateinit var appSettings: FakeAppSettingsRepository
    private lateinit var healthCheck: FakeDatabaseHealthCheck
    private lateinit var encryptionProbe: FakeDatabaseEncryptionProbe
    private lateinit var documentSource: FakeDocumentSource
    private lateinit var flagWriter: FakeDatabaseInitFlagWriter
    private lateinit var flagReader: FakeDatabaseInitFlagReader
    private lateinit var walConsolidator: FakeDatabaseInitWalConsolidator

    /**
     * Typed as the PORT, so a test can swap in a persister that observes the catalog write itself;
     * [fakePersister] keeps the shared recording one reachable for the tests that do not need to.
     */
    private lateinit var dimensionPersister: DatabaseDimensionSetupPersister

    /** What [dimensionPersister] holds unless a test swapped it - the shared recording fake. */
    private lateinit var fakePersister: FakeDatabaseDimensionSetupPersister

    private lateinit var checkpoint: FakeEncryptionStateCheckpoint
    private lateinit var vault: FakePassphraseVault

    @Before
    fun setUp() {
        dbDirectory = Files.createTempDirectory("payanam-dbinit-first-run-loss").toFile()
        pathResolver = FakeDatabasePathResolver(dbDirectory)
        sessionManager = FakeDatabaseSessionManager()
        lifeDimensions = FakeLifeDimensionCatalogRepository()
        appSettings = FakeAppSettingsRepository()
        healthCheck = FakeDatabaseHealthCheck()
        encryptionProbe = FakeDatabaseEncryptionProbe()
        documentSource = FakeDocumentSource()
        flagWriter = FakeDatabaseInitFlagWriter()
        flagReader = FakeDatabaseInitFlagReader()
        walConsolidator = FakeDatabaseInitWalConsolidator()
        fakePersister = FakeDatabaseDimensionSetupPersister()
        dimensionPersister = fakePersister
        checkpoint = FakeEncryptionStateCheckpoint()
        vault = FakePassphraseVault()
    }

    @After
    fun tearDown() {
        dbDirectory.deleteRecursively()
    }

    /**
     * The subject, with BOTH dispatchers on the test scheduler.
     *
     * [ioDispatcher] matters as much as the scope: the create runs in `withContext(ioDispatcher)`, and a
     * real `Dispatchers.IO` hop is invisible to [advanceUntilIdle], so a test that read the mark after
     * idling could read it before the catalog write was even asked for.
     *
     * [scope] is a parameter for the restart tests only, and is the whole reason a second ViewModel can
     * exist in this class: the first one's `dispose` cancels the scope it was given.
     */
    private fun newViewModel(scope: CoroutineScope = this.scope) =
        DatabaseInitViewModel(
            appSettingsRepository = appSettings,
            databaseSessionManager = sessionManager,
            lifeDimensionCatalogRepository = lifeDimensions,
            strings = testUiStrings(),
            healthCheck = healthCheck,
            databasePathResolver = pathResolver,
            encryptionProbe = encryptionProbe,
            documentSource = documentSource,
            databaseInitFlagWriter = flagWriter,
            databaseInitFlagReader = flagReader,
            databaseInitWalConsolidator = walConsolidator,
            dimensionSetupPersister = dimensionPersister,
            encryptionStateCheckpoint = checkpoint,
            passphraseVault = vault,
            scope = scope,
            ioDispatcher = testDispatcher,
        )

    /** The two life dimensions this suite confirms; identity is asserted, so both are kept distinct. */
    private val dimensionInputs =
        listOf(
            NewDatabaseDimensionInput(id = "health", label = "Health", colorHex = "#FF0000", isEnabled = true),
            NewDatabaseDimensionInput(id = "work", label = "Work", colorHex = "#00FF00", isEnabled = true),
        )

    /** A database file with real bytes, standing in for the file the create actually leaves behind. */
    private fun plantDatabase(): File =
        pathResolver.databaseFile.apply {
            writeBytes(ByteArray(4096) { (it % 251).toByte() })
        }

    /**
     * The stored value of the intent row, read the way production reads it.
     *
     * Suspend and inline rather than a convenience that returns null: the row being absent is one of the
     * two answers this suite asserts on, so a helper that hid absence would hide half of test D.
     */
    private suspend fun markAsStored(): String? =
        appSettings.getSetting(AppPreferenceKeys.KEY_FIRST_RUN_DIMENSIONS_PENDING)

    // ============================================================= the four behaviours

    /**
     * A: the mark is already there when the catalog write is asked for.
     *
     * REVERT THAT BREAKS THIS: dropping the `markFirstRunDimensionsPending()` call at
     * `DatabaseInitViewModel.kt:608` (the one before the create), or moving it to sit AFTER
     * `dimensionSetupPersister.persist` at line 633. Either leaves the mark unset while the catalog is
     * being written, so the row read back at persist time would be null and the window the mark exists
     * to cover - a quit between the create and the write - would stay silent, which is the original bug.
     *
     * WHY THE MARK IS READ INSIDE `persist` AND NOT AFTER THE FLOW: after the flow, a correct
     * implementation has already cleared it, so a post-hoc assertion could only ever see "false" and
     * could not tell a mark-then-clear from a mark-set-too-late.
     */
    @Test
    fun `confirmation is marked unlanded before the catalog write`() =
        runTest(testDispatcher) {
            val recorder = RecordingMarkPersister()
            dimensionPersister = recorder
            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.createNewDatabase(PASSPHRASE)
            advanceUntilIdle()
            viewModel.completeNewDatabaseDimensionSetup(dimensionInputs) {}
            advanceUntilIdle()

            assertThat(recorder.persistCalls).isEqualTo(1)
            assertThat(recorder.inputsAtPersist).isEqualTo(dimensionInputs)
            // The durable value at the exact moment the write was requested, read back through the same
            // repository the production code writes it through.
            assertThat(recorder.markAtPersist).isEqualTo(MARK_SET)
            // And under the SHARED key, not a second spelling of it: a per-module copy of the name would
            // strand the reader on a row nobody writes, and the loss would go silent again.
            assertThat(appSettings.writeLog).contains(AppPreferenceKeys.KEY_FIRST_RUN_DIMENSIONS_PENDING to MARK_SET)
            viewModel.dispose()
        }

    /**
     * B: an ordinary run leaves nothing behind. This is the guard that stops this fix from passing by
     * marking EVERY confirmation as lost - a screen that always claims the dimensions were lost is as
     * useless as one that never claims it.
     *
     * REVERT THAT BREAKS THIS: dropping the `clearFirstRunDimensionsPending()` call at
     * `DatabaseInitViewModel.kt:641` (the one after `dimensionSetupPersister.persist`), or moving it to
     * BEFORE the catalog write. Without the clear the row stays set forever, so the next launch reports a
     * loss for a confirmation that in fact landed - and the pair of writes below would never be seen.
     *
     * WHY THE STATUS IS RE-CHECKED AT THE END: `completeNewDatabaseDimensionSetup` does not republish
     * this flag, so only a fresh status check reads the cleared row. A clean run must therefore be silent
     * on the NEXT launch too, not just silent in the row.
     */
    @Test
    fun `a clean run clears the mark once the catalog is written`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.createNewDatabase(PASSPHRASE)
            advanceUntilIdle()

            viewModel.completeNewDatabaseDimensionSetup(dimensionInputs) {}
            advanceUntilIdle()

            assertThat(fakePersister.calls).isEqualTo(1)
            // Set on the way in, cleared on the way out, in that order: the ORDER is the fix. Clearing
            // first would leave a real loss indistinguishable from a clean run.
            assertThat(appSettings.writeLog)
                .containsAtLeast(
                    AppPreferenceKeys.KEY_FIRST_RUN_DIMENSIONS_PENDING to MARK_SET,
                    AppPreferenceKeys.KEY_FIRST_RUN_DIMENSIONS_PENDING to MARK_CLEARED,
                ).inOrder()
            assertThat(markAsStored())
                .isEqualTo(MARK_CLEARED)
            // The next launch of an install whose dimensions DID land must say nothing.
            plantDatabase()
            healthCheck.artifactsExist = true
            flagReader.put(AppSettingsRepository.DATABASE_INIT_COMPLETED_KEY, "true")
            viewModel.retryDatabaseStatusCheck()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.firstRunDimensionsNotSaved).isFalse()
            viewModel.dispose()
        }

    /**
     * C: a launch over a mark left behind reports the loss. This is the user-visible half.
     *
     * REVERT THAT BREAKS THIS: removing the `firstRunDimensionsNotSaved = firstRunDimensionsNotSaved`
     * copies at `DatabaseInitViewModel.kt:414` (existing database) and `:425` (no database), or removing
     * the `readFirstRunDimensionsPending()` call at line 374 that feeds them. The read is made BEFORE the
     * branch precisely so a loss is reported whichever side of it the launch lands; either line alone
     * goes red, and a mark that is read but never published leaves the state at its `false` default.
     *
     * WHY A FAILED CATALOG WRITE IS THE WAY IN HERE: a real quit needs a second process. A persist that
     * fails one line after the mark was set leaves the SAME durable state a quit in the same window
     * does - the row is set and never cleared - and the first run's own error message is asserted below
     * so that is visibly the state being reproduced.
     *
     * WHY BOTH STATUS BRANCHES ARE EXERCISED: the mark is a fact about the INSTALL's settings table, not
     * about the database file, so it must survive a retry that changes the file's answer entirely.
     */
    @Test
    fun `a restart after the dangerous point reports the confirmation did not survive`() =
        runTest(testDispatcher) {
            val failingPersist =
                FakeDatabaseDimensionSetupPersister().apply {
                    failure = IllegalStateException("catalog write rejected")
                }
            dimensionPersister = failingPersist
            val firstRun = newViewModel()
            advanceUntilIdle()
            firstRun.createNewDatabase(PASSPHRASE)
            advanceUntilIdle()

            firstRun.completeNewDatabaseDimensionSetup(dimensionInputs) {}
            advanceUntilIdle()

            // The durable state the loss leaves behind, asserted before the restart so the restart's
            // report cannot be mistaken for the first run's own state.
            assertThat(firstRun.uiState.value.errorMessage).isEqualTo("catalog write rejected")
            assertThat(appSettings.getSetting(AppPreferenceKeys.KEY_FIRST_RUN_DIMENSIONS_PENDING)).isEqualTo(MARK_SET)
            // The quit: this run's scope is gone, so the next ViewModel can only report what is on disk.
            firstRun.dispose()

            val restartScope = CoroutineScope(testDispatcher)
            val restarted = newViewModel(scope = restartScope)
            advanceUntilIdle()

            assertThat(restarted.uiState.value.firstRunDimensionsNotSaved).isTrue()
            // The same mark, now with a leftover file that is NOT a placeholder: the report is owed to the
            // install, so it must not depend on which branch the status check took.
            plantDatabase()
            healthCheck.artifactsExist = true
            encryptionProbe.counts = mapOf("tasks" to 3)
            restarted.retryDatabaseStatusCheck()
            advanceUntilIdle()

            assertThat(restarted.uiState.value.databaseExists).isTrue()
            assertThat(restarted.uiState.value.firstRunDimensionsNotSaved).isTrue()
            restarted.dispose()
        }

    /**
     * D: no mark, no alarm. The half of the fix that is only about restraint.
     *
     * REVERT THAT BREAKS THIS: defaulting `readFirstRunDimensionsPending()` (or the state field) to
     * `true`, or having the status check write the row it reads to see whether it sticks. Both would make
     * a first launch that never confirmed anything announce a lost confirmation - and the writeLog
     * assertion below catches the second shape, which a state-only assertion would pass.
     */
    @Test
    fun `a first launch with no mark reports nothing was lost`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()

            // Absent, not "false": a first install has no such row at all, and a reader that demanded
            // one would have nothing to say.
            assertThat(appSettings.getSetting(AppPreferenceKeys.KEY_FIRST_RUN_DIMENSIONS_PENDING)).isNull()
            assertThat(viewModel.uiState.value.firstRunDimensionsNotSaved).isFalse()
            // Merely LOOKING must not create the row. A status check that seeded the mark would make
            // every install look like it had lost a confirmation it never made.
            assertThat(appSettings.writeLog).isEmpty()
            viewModel.dispose()
        }
}

/**
 * The catalog-write fake that reads the pending mark THROUGH the repository it is handed, at the instant
 * the write is asked for.
 *
 * A separate fake rather than the shared one's `beforeFailure` hook because the hook is not a suspend
 * function and the mark can only be read through a suspending repository call; asserting the row from
 * outside the write would be asserting the wrong moment.
 */
private class RecordingMarkPersister : DatabaseDimensionSetupPersister {
    var persistCalls = 0
    var markAtPersist: String? = null
    var inputsAtPersist: List<NewDatabaseDimensionInput> = emptyList()

    override suspend fun persist(
        lifeDimensionCatalogRepository: LifeDimensionCatalogRepository,
        appSettingsRepository: AppSettingsRepository,
        dimensionInputs: List<NewDatabaseDimensionInput>,
    ) {
        persistCalls++
        inputsAtPersist = dimensionInputs
        markAtPersist = appSettingsRepository.getSetting(AppPreferenceKeys.KEY_FIRST_RUN_DIMENSIONS_PENDING)
    }
}

/** Any passphrase the fake vault accepts: the policy is not what this suite is about. */
private const val PASSPHRASE = "correct horse"

/** The two stored values of the intent row, named so an assertion reads as a claim rather than a string. */
private const val MARK_SET = "true"
private const val MARK_CLEARED = "false"
