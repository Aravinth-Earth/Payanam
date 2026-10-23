//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.core.ui.text.uiStringsFor
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.NoteRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TimeEntryRepository
import io.payanam.platform.AndroidDatabaseEncryptionProbe
import io.payanam.platform.AndroidDocumentSource
import io.payanam.platform.appDatabaseInitWalConsolidator
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.data.PayanamDataLayer
import io.payanam.presentation.data.UninstalledDataLayer
import io.payanam.presentation.settings.ImportResult
import io.payanam.service.BackupStatusStore
import io.payanam.service.DatabaseBackupCoordinator
import io.payanam.shared.database.PayanamDatabaseSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The settings half of the encrypted-only import rule: a plaintext SQLite database
 * offered to [SettingsViewModel.importDatabase] is REFUSED, and the database the user already had is
 * left byte-identical.
 *
 * WHY THE WHOLE VIEWMODEL IS BUILT FOR THIS: the rule is one `isStandardSqliteFile` check buried
 * inside an eleven-step coroutine (back up, delete, copy, fold, classify, …). A test that called the
 * classifier directly would prove nothing about the thing that changed — the branch. So the real
 * pipeline runs: a real file-URI source copied through the real `AndroidDocumentSource`, a real backup
 * of the user's artifacts, and the real `DatabaseEncryptionManager` the restore writes through. Only
 * the repositories the `init` block touches are mocks, because `init` is not the subject.
 *
 * WHY THE TEST POLLS RATHER THAN ADVANCES VIRTUAL TIME: this path's IO runs on a real `Dispatchers.IO`
 * (`SettingsEncryptedImportSupport` hard-codes it; there is no injected dispatcher to swap), so
 * `advanceUntilIdle` on a test dispatcher would return while the copy was still in flight and every
 * assertion after it would be reading an empty state.
 *
 * THE ASSERTION THAT MATTERS IS THE SECOND ONE. A refusal that replaced the user's database with the
 * file it just declined would still show the right error message, so the message alone cannot
 * distinguish a refusal from a half-done import. The bytes are asserted too.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsPlaintextImportRefusalTest {
    private lateinit var context: Context
    private lateinit var databaseFile: File

    @Before
    fun setUp() {
        // A real dispatcher for Main, because the import coroutine hops to a real IO pool.
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        UnifiedLogger.initialize(context, "test", 0)
        databaseFile = context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME)
        databaseFile.parentFile?.mkdirs()
        databaseFile.parentFile?.listFiles()?.forEach { it.deleteRecursively() }
    }

    @After
    fun tearDown() {
        databaseFile.parentFile?.listFiles()?.forEach { it.deleteRecursively() }
        Dispatchers.resetMain()
    }

    @Test
    fun `a plaintext database offered to the settings import is refused and the user's database is left byte-identical`() {
        // The user's database: a real one with a row in it, so "unchanged afterwards" is a claim
        // about a file that has content worth losing.
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { db ->
            db.version = PayanamDatabaseSchema.VERSION
            db.execSQL("CREATE TABLE IF NOT EXISTS tasks (id TEXT PRIMARY KEY, title TEXT)")
            db.execSQL("INSERT INTO tasks (id, title) VALUES ('task-1', 'the one that matters')")
        }
        val beforeBytes = databaseFile.readBytes()
        assertThat(beforeBytes.size).isGreaterThan(0)

        // The offered file: a genuine plaintext SQLite database — the exact shape this gate is about.
        val offered = writePlaintextDatabase("offered-plaintext.db")

        val viewModel = newViewModel()
        viewModel.importDatabase(Uri.fromFile(offered).toString(), databaseFile, AndroidDatabaseEncryptionProbe(context), uiStringsFor(context), AndroidDocumentSource(context), appDatabaseInitWalConsolidator(context, UnifiedLogger.getInstance()))
        awaitSettled(viewModel)

        // 1. REFUSED — and refused with the PLAINTEXT string, not the unreadable one or a generic
        //    failure. See `appString` for why the assertion is on the resource identity
        //    rather than on the sentence.
        val result = viewModel.uiState.value.importResult
        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
        val message = (result as ImportResult.Error).message
        assertThat(message).isEqualTo(appString(io.payanam.R.string.settings_import_error_plaintext_db))
        // The two refusals are genuinely different resources, so this is not a coincidence: a
        // message equal to the UNREADABLE string would mean the wrong branch threw.
        assertThat(message)
            .isNotEqualTo(appString(io.payanam.R.string.settings_import_error_unreadable_db))
        assertThat(viewModel.uiState.value.isImporting).isFalse()
        // Nothing was re-encrypted and no passphrase was ever requested: a file that was going to be
        // converted is a file whose passphrase question is moot.
        assertThat(viewModel.uiState.value.awaitingImportPassphrase).isFalse()
        assertThat(viewModel.pendingEncryptedImportDbFile).isNull()

        // 2. THE USER'S DATABASE IS BYTE-IDENTICAL. The refusal restores the pre-import backup, so the
        // picked file — which WAS copied over the real path first, because the copy precedes the gate —
        // is gone and the original is back, with its row still readable.
        //
        // WHY THIS IS NOT VACUOUS, because "the file is unchanged" also describes a copy that never
        // happened. Two things rule that out. The comparison is between two genuinely different files
        // (asserted), and the refusal message itself is only reachable AFTER `copyDatabaseArtifacts`
        // has run and after the zero-length guard has passed — the gate reads the header of the file
        // the copy wrote. So a wrong message, or no message, would mean the copy never landed.
        assertThat(offered.readBytes().toList()).isNotEqualTo(beforeBytes.toList())
        assertThat(databaseFile.readBytes().toList()).isEqualTo(beforeBytes.toList())
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT title FROM tasks WHERE id = 'task-1'", null).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("the one that matters")
            }
        }

        // 3. No leftover second database: the safety copy is cleaned up once the import is over, so a
        //    refused import does not accumulate a `.bak` per attempt.
        val strays = databaseFile.parentFile!!.listFiles()!!.filter { it.name.endsWith(".bak") }
        assertThat(strays).isEmpty()
    }

    @Test
    fun `a file too short to carry a header is refused with the unreadable message instead`() {
        // The sibling branch, kept honest: a file that is neither shape must NOT be reported as
        // "unencrypted" (it is not a database at all) and must not fall through into the plaintext
        // pipeline either. The two refusals are different sentences about different files.
        //
        // WHY A SHORT FILE, AND NOT A LARGE ONE OF JUNK BYTES: `EncryptedFileClassifier` calls any file
        // at least 16 bytes long that does not open with the SQLite magic ENCRYPTED, on purpose — a
        // Payanam export is exactly that shape, so the classifier cannot tell SQLCipher from a
        // corrupted file and is built to guess encrypted rather than send the user away. A 4KB file of
        // pseudo-random bytes therefore pauses for a passphrase by design. The one shape that reaches
        // the unreadable refusal is a file too short to hold a header, and that is what this pins.
        val offered = File.createTempFile("offered-too-short", ".db", context.cacheDir)
        offered.writeBytes(ByteArray(8) { 0x2A })

        val viewModel = newViewModel()
        viewModel.importDatabase(Uri.fromFile(offered).toString(), databaseFile, AndroidDatabaseEncryptionProbe(context), uiStringsFor(context), AndroidDocumentSource(context), appDatabaseInitWalConsolidator(context, UnifiedLogger.getInstance()))
        awaitSettled(viewModel)

        val result = viewModel.uiState.value.importResult
        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
        val message = (result as ImportResult.Error).message
        assertThat(message).isEqualTo(appString(io.payanam.R.string.settings_import_error_unreadable_db))
        assertThat(message)
            .isNotEqualTo(appString(io.payanam.R.string.settings_import_error_plaintext_db))
        assertThat(viewModel.uiState.value.awaitingImportPassphrase).isFalse()
    }

    /** A real, readable, unencrypted database at a path the picker would accept. */
    private fun writePlaintextDatabase(name: String): File {
        val file = File(context.cacheDir, name)
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.version = PayanamDatabaseSchema.VERSION
            db.execSQL("CREATE TABLE IF NOT EXISTS tasks (id TEXT PRIMARY KEY, title TEXT)")
        }
        return file
    }

    /**
     * What [Context.getString] returns for [resId] in this module's unit tests - the real resolved
     * sentence - and why the assertions above are written against it.
     *
     * HISTORY (retired 2026-10-07): this module's Robolectric DID NOT merge Android resources, so
     * getString fell back to `"String resource ID #0x…"` and this helper hand-built that placeholder;
     * production returned the same placeholder, so the equality pinned the resource ID. The fallback
     * is GONE: `app/build.gradle.kts` now sets `testOptions.unitTests.isIncludeAndroidResources =
     * true` (added so TaskReminderSchedulerTest could resolve `task_notification_title`), so
     * production resolves the real sentence and the expectation must too - a wrong resource id still
     * fails, because it resolves to a different sentence (or throws). The wording itself stays pinned
     * by `Test-StringParity` in all four shipping tables.
     */
    private fun appString(resId: Int): String = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().getString(resId)

    /** Waits for the import coroutine to publish a terminal state, or fails the test. */
    private fun awaitSettled(viewModel: SettingsViewModel) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS
        while (viewModel.uiState.value.importResult == null) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError(
                    "The import never settled: isImporting=${viewModel.uiState.value.isImporting}, " +
                        "awaitingPassphrase=${viewModel.uiState.value.awaitingImportPassphrase}",
                )
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
    }

    /**
     * The ViewModel under test, with only the collaborators that are not the subject faked.
     *
     * [DatabaseEncryptionManager] and [DatabaseSessionManager] are the app's real types on purpose:
     * the settings import backs up and restores the encryption preferences and needs an open session to
     * read counts, and a mock there would let the restore arm pass without a restore having happened.
     * The repositories are mocks because nothing on the import path reads them — only the `init` block
     * does, and it is written to log-and-continue when they fail.
     */
    private fun newViewModel(): SettingsViewModel {
        val encryptionManager = DatabaseEncryptionManager(context)
        // The ViewModel reads its repositories from the installed data layer now that it is Hilt-free, so
        // the layer must be installed before it is constructed - its property initialisers run at build
        // time, and the default layer throws on every read by design. This delegates to that default and
        // overrides exactly the four members this ViewModel touches, which is the same shape the other
        // core/presentation tests use; the values are mocks for the same reason the old code passed mocks
        // (nothing on the import path reads a repository, only the `init` block does).
        LocalDataLayer.install(
            object : PayanamDataLayer by UninstalledDataLayer {
                override val tasks: TaskRepository = mock()
                override val timeEntries: TimeEntryRepository = mock()
                override val notes: NoteRepository = mock()
                override val appSettings: AppSettingsRepository = mock()
            },
        )
        return SettingsViewModel(
            context = context,
            sessionManager = DatabaseSessionManager(context, encryptionManager),
            databaseEncryptionManager = encryptionManager,
            databaseBackupCoordinator =
            DatabaseBackupCoordinator(
                context,
                mock(),
                BackupStatusStore(context),
            ),
        )
    }

    private companion object {
        const val AWAIT_TIMEOUT_MILLIS = 20_000L
        const val POLL_INTERVAL_MILLIS = 20L
    }
}
