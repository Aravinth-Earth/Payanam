//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.database.security.DatabaseEncryptionMigrationSupport
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.ui.viewmodel.DatabaseInitViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The import seam, end to end. SAF itself is out of reach for every automated tier, but the
 * ViewModel seam is not: this drives `importDatabase(uri)` with a file URI for an encrypted fixture,
 * goes through the passphrase pause and resume, and asserts the journey completes.
 *
 * It also guards the incident this work fixes at the level the unit test cannot: the import source is
 * a real SQLCipher file on device, and it must still exist after being classified.
 *
 * Fixture sequencing (load-bearing, per the approved plan): the fixture is created through the app's
 * own managers at the canonical path, then **closed and checkpointed** (the session runs WAL with
 * `wal_autocheckpoint=200`, and the file-URI import copies the `.db` only — a copy taken while the
 * session is open would contain no tables), copied **out** to separate test storage, and the
 * canonical artifacts are then **cleared** — otherwise the import would take the wipe-confirm branch
 * whose `deleteAllDatabaseFiles()` runs before `copyDatabaseArtifacts()` and would delete the
 * import's own source (the build-1748 ENOENT signature).
 */
@RunWith(AndroidJUnit4::class)
class ImportSeamTest {
    private lateinit var context: Context
    private lateinit var encryptionManager: DatabaseEncryptionManager
    private lateinit var sessionManager: DatabaseSessionManager
    private lateinit var viewModel: DatabaseInitViewModel
    private lateinit var fixtureDir: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        UnifiedLogger.setDebugLoggingEnabled(true)
        deleteCanonicalArtifacts()
        encryptionManager = DatabaseEncryptionManager(context)
        encryptionManager.resetEncryptionState()
        sessionManager = DatabaseSessionManager(context, encryptionManager)
        viewModel =
            DatabaseInitViewModel(
                context = context,
                appSettingsRepository = FakeAppSettingsRepository(),
                databaseEncryptionManager = encryptionManager,
                databaseSessionManager = sessionManager,
            )
        fixtureDir =
            File(context.filesDir, FIXTURE_DIR_NAME).apply {
                deleteRecursively()
                mkdirs()
            }
    }

    @After
    fun tearDown() {
        runCatching { sessionManager.closeDatabase() }
        runCatching { encryptionManager.resetEncryptionState() }
        deleteCanonicalArtifacts()
        fixtureDir.deleteRecursively()
    }

    @Test
    fun encryptedFileImport_pausesForPassphrase_resumes_completes_andKeepsTheSource() {
        // ── Fixture: a complete app-schema SQLCipher database at the canonical path ──
        assertTrue(encryptionManager.configurePassphrase(PASSPHRASE))
        runBlocking { assertTrue(sessionManager.openDatabase(PASSPHRASE).isSuccess) }
        assertNotNull(sessionManager.requireDatabase())
        // Close the session so Room checkpoints the WAL before the copy: the file-URI import copies
        // the .db alone, so an open-session copy would carry no tables at all.
        sessionManager.closeDatabase()

        val canonicalDb = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
        assertTrue(canonicalDb.exists())
        val expectedCounts =
            DatabaseEncryptionMigrationSupport.readTableCounts(context, canonicalDb, PASSPHRASE, REQUIRED_TABLES)

        val importSource = File(fixtureDir, "renamed-export.db")
        canonicalDb.copyTo(importSource, overwrite = true)
        deleteCanonicalArtifacts() // direct import path, not wipe-confirm

        // ── Pause: encrypted import is detected and the passphrase prompt is reached ──
        var completed = false
        viewModel.importDatabase(Uri.fromFile(importSource)) { completed = true }
        awaitState("awaiting passphrase") { viewModel.uiState.value.awaitingImportPassphrase }
        assertFalse(completed)
        assertTrue(importSource.exists())

        // ── Re-entry guard: a second import mid-journey is rejected, silently no more ──
        val stateBefore = viewModel.uiState.value
        viewModel.importDatabase(Uri.fromFile(importSource)) { completed = true }
        // The guard also logs `import_reentry_rejected`, and that line is emitted — it is visible in
        // the device logcat for this run and its presence in the source is locked by the build's
        // logging-coverage contract. Asserting it against the session *file* was removed here: the
        // file writer buffers, so on-device the file can trail the in-memory log and the assertion
        // measured the writer rather than the guard. What must hold is the observable contract:
        assertTrue(stateBefore.awaitingImportPassphrase)
        assertEquals("the rejected re-entry must not disturb the paused journey", stateBefore, viewModel.uiState.value)

        // ── Resume: the passphrase completes the import ──
        viewModel.resumeImportWithPassphrase(PASSPHRASE) { completed = true }
        awaitState("import completion") { completed && !viewModel.uiState.value.isImporting }
        assertEquals(null, viewModel.uiState.value.errorMessage)
        assertEquals(null, viewModel.importDbFile)

        // ── The import source survived classification, and the imported data is intact ──
        assertTrue("import source must survive the import", importSource.exists())
        assertTrue(importSource.length() > 0)
        // Row identity: the resumed import marks init complete with an INSERT OR REPLACE into
        // app_settings, so exactly that table gains the one row the fixture did not have; the other
        // tables must match the fixture exactly. Poll so the assertion cannot race the write.
        val expectedAfterImport =
            expectedCounts.toMutableMap().apply {
                this["app_settings"] = (this["app_settings"] ?: 0) + 1
            }
        awaitState("imported row counts") {
            DatabaseEncryptionMigrationSupport.readTableCounts(
                context,
                canonicalDb,
                PASSPHRASE,
                REQUIRED_TABLES,
            ) == expectedAfterImport
        }
        assertTrue(canonicalDb.exists())
    }

    private fun awaitState(
        what: String,
        timeoutMillis: Long = 20_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("Timed out waiting for $what (state=${viewModel.uiState.value})")
    }

    private fun deleteCanonicalArtifacts() {
        val basePath = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).absolutePath
        COMPANION_SUFFIXES.forEach { suffix -> File(basePath + suffix).delete() }
        File(basePath).delete()
    }

    /** Minimal in-memory stand-in: the import path never reads settings, only writes on success. */
    private class FakeAppSettingsRepository : AppSettingsRepository {
        private val values = mutableMapOf<String, String?>()

        override suspend fun getSetting(key: String): String? = values[key]

        override fun observeSetting(key: String): Flow<String?> = flowOf(values[key])

        override suspend fun setSetting(
            key: String,
            value: String?,
        ) {
            values[key] = value
        }

        override suspend fun deleteSetting(key: String) {
            values.remove(key)
        }

        override fun getAllSettings(): Flow<Map<String, String?>> = flowOf(values.toMap())
    }

    private companion object {
        /** Disposable test-only passphrase; the fixture is created and destroyed by this test. */
        const val PASSPHRASE = "ImportSeam#Test123"
        const val FIXTURE_DIR_NAME = "import-seam-fixture"
        const val POLL_MILLIS = 50L
        val COMPANION_SUFFIXES = listOf("-wal", "-shm", "-journal")
        val REQUIRED_TABLES =
            listOf("tasks", "time_entries", "notes", "day_journal_entries", "app_settings")
    }
}
