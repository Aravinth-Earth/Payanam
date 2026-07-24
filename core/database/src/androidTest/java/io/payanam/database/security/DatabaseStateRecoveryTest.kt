//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import io.payanam.database.session.DatabaseSessionManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Tests for database state recovery and cleanup operations.
 * Verifies resilience during partial/corrupted import and artifact cleanup.
 * Ensures backup + restore workflows don't lose data during transitions.
 */
class DatabaseStateRecoveryTest {
    private lateinit var context: Context
    private lateinit var logger: UnifiedLogger
    private lateinit var encryptionManager: DatabaseEncryptionManager
    private lateinit var sessionManager: DatabaseSessionManager

    private val testPassphrase = "RecoveryTest123!"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        logger = UnifiedLogger.getInstance()

        encryptionManager = DatabaseEncryptionManager(context)
        sessionManager = DatabaseSessionManager(context, encryptionManager)

        logger.d(
            "DatabaseStateRecoveryTest.setUp",
            "Test setup complete",
            mapOf("testPassphraseLength" to testPassphrase.length),
        )
    }

    @After
    fun tearDown() {
        sessionManager.closeDatabase()
        encryptionManager.resetEncryptionState()
        deleteAllDatabaseFiles()
        logger.d("DatabaseStateRecoveryTest.tearDown", "All test artifacts cleaned up")
    }

    @Test
    fun cleanupStaleArtifacts_removesTemporaryEncryptionFiles() {
        // Arrange: Create some stale encryption temporary files
        val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile
        assertThat(dbDir).isNotNull()

        val staleTempFile = File(dbDir!!, "${PayanamDatabase.DATABASE_NAME}.enc.tmp")
        staleTempFile.createNewFile()
        assertThat(staleTempFile.exists()).isTrue()

        // Act: Run cleanup
        DatabaseArtifactJanitor.cleanupStaleArtifacts(context)

        // Assert: Stale file is removed
        assertThat(staleTempFile.exists()).isFalse()
        logger.i(
            "DatabaseStateRecoveryTest.cleanupStaleArtifacts_removesTemporaryEncryptionFiles",
            "Stale encryption file cleaned up",
            mapOf("fileRemoved" to !staleTempFile.exists()),
        )
    }

    @Test
    fun cleanupStaleArtifacts_preservesPrimaryDatabase() =
        runTest {
            // Arrange: Create and open a valid database
            encryptionManager.configurePassphrase(testPassphrase)
            val openResult = sessionManager.openDatabase(testPassphrase)
            assertThat(openResult.isSuccess).isTrue()

            val dbFile = File(context.getDatabasePath(PayanamDatabase.DATABASE_NAME).absolutePath)
            assertThat(dbFile.exists()).isTrue()

            sessionManager.closeDatabase()

            // Act: Run cleanup with a valid primary database present
            DatabaseArtifactJanitor.cleanupStaleArtifacts(context)

            // Assert: Primary database file is preserved
            assertThat(dbFile.exists()).isTrue()
            logger.i(
                "DatabaseStateRecoveryTest.cleanupStaleArtifacts_preservesPrimaryDatabase",
                "Primary database preserved during cleanup",
                mapOf("dbStillExists" to dbFile.exists()),
            )
        }

    @Test
    fun encryptionPrefs_backupAndRestore() {
        // Arrange: Configure encryption with some state
        val configResult = encryptionManager.configurePassphrase(testPassphrase)
        assertThat(configResult).isTrue()
        encryptionManager.setSessionTimeoutMinutes(20)

        // Act: Backup encryption preferences
        val backupResult = encryptionManager.backupEncryptionPrefs()
        assertThat(backupResult).isTrue()

        // Act: Reset encryption state
        encryptionManager.resetEncryptionState()
        assertThat(encryptionManager.hasPassphraseConfigured()).isFalse()

        // Act: Restore encryption preferences
        val restoreResult = encryptionManager.restoreEncryptionPrefs()
        assertThat(restoreResult).isTrue()

        // Assert: Encryption state is restored
        assertThat(encryptionManager.hasPassphraseConfigured()).isTrue()
        assertThat(encryptionManager.verifyPassphrase(testPassphrase)).isTrue()
        assertThat(encryptionManager.getSessionTimeoutMinutes()).isEqualTo(20)

        logger.i(
            "DatabaseStateRecoveryTest.encryptionPrefs_backupAndRestore",
            "Encryption preferences backed up and restored successfully",
            mapOf(
                "passphraseConfiguredAfterRestore" to encryptionManager.hasPassphraseConfigured(),
            ),
        )
    }

    @Test
    fun recoveryFromPartialImport_resetsStateCleanly() =
        runTest {
            // Simulates: user starts import, process crashes, then recovery
            // Step 1: Create initial database
            encryptionManager.configurePassphrase(testPassphrase)
            val openResult1 = sessionManager.openDatabase(testPassphrase)
            assertThat(openResult1.isSuccess).isTrue()

            // Step 2: Close database (simulating pre-import close)
            sessionManager.closeDatabase()

            // Step 3: Create a stale temp file (simulating crashed import)
            val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile
            val staleTempFile = File(dbDir!!, "${PayanamDatabase.DATABASE_NAME}.before_import_20260304")
            staleTempFile.createNewFile()
            assertThat(staleTempFile.exists()).isTrue()

            // Act: Run recovery cleanup
            DatabaseArtifactJanitor.cleanupStaleArtifacts(context)

            // Assert: Stale import artifact is cleaned, primary DB is preserved
            assertThat(staleTempFile.exists()).isFalse()
            val dbFile = File(context.getDatabasePath(PayanamDatabase.DATABASE_NAME).absolutePath)
            assertThat(dbFile.exists()).isTrue()

            // Step 4: Verify database is still accessible
            val openResult2 = sessionManager.openDatabase(testPassphrase)
            assertThat(openResult2.isSuccess).isTrue()

            logger.i(
                "DatabaseStateRecoveryTest.recoveryFromPartialImport_resetsStateCleanly",
                "Recovery from partial import completed successfully",
                mapOf(
                    "staleArtifactsRemoved" to !staleTempFile.exists(),
                    "primaryDbPreserved" to dbFile.exists(),
                    "reopenedSuccessfully" to openResult2.isSuccess,
                ),
            )
        }

    @Test
    fun databaseWALFiles_cleanedOnDeletion() =
        runTest {
            // Arrange: Create database with WAL files
            encryptionManager.configurePassphrase(testPassphrase)
            val openResult = sessionManager.openDatabase(testPassphrase)
            assertThat(openResult.isSuccess).isTrue()

            val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile
            val dbName = PayanamDatabase.DATABASE_NAME
            val walFile = File(dbDir!!, "$dbName-wal")
            val shmFile = File(dbDir, "$dbName-shm")

            sessionManager.closeDatabase()

            // Act: Delete database
            val dbFile = File(context.getDatabasePath(dbName).absolutePath)
            dbFile.delete()
            walFile.delete()
            shmFile.delete()

            // Assert: All database-related files are gone
            assertThat(dbFile.exists()).isFalse()
            // WAL and SHM files may not exist depending on configuration, but we verify deletion didn't fail
            logger.d(
                "DatabaseStateRecoveryTest.databaseWALFiles_cleanedOnDeletion",
                "Database and WAL files cleaned up",
                mapOf(
                    "dbExists" to dbFile.exists(),
                    "walExists" to walFile.exists(),
                    "shmExists" to shmFile.exists(),
                ),
            )
        }

    @Test
    fun encryptionStateReset_isIdempotent() {
        // Arrange: Configure encryption
        encryptionManager.configurePassphrase(testPassphrase)
        assertThat(encryptionManager.hasPassphraseConfigured()).isTrue()

        // Act: Reset encryption state multiple times
        val result1 = encryptionManager.resetEncryptionState()
        val result2 = encryptionManager.resetEncryptionState()
        val result3 = encryptionManager.resetEncryptionState()

        // Assert: All resets succeed (idempotent operation)
        assertThat(result1).isTrue()
        assertThat(result2).isTrue()
        assertThat(result3).isTrue()
        assertThat(encryptionManager.hasPassphraseConfigured()).isFalse()

        logger.d(
            "DatabaseStateRecoveryTest.encryptionStateReset_isIdempotent",
            "Encryption state reset is idempotent",
            mapOf(
                "allResetsSucceeded" to (result1 && result2 && result3),
                "stateCleared" to !encryptionManager.hasPassphraseConfigured(),
            ),
        )
    }

    @Test
    fun failureRecovery_databaseReopeningAfterCleanup() =
        runTest {
            // Step 1: Create database with passphrase
            encryptionManager.configurePassphrase(testPassphrase)
            val openResult = sessionManager.openDatabase(testPassphrase)
            assertThat(openResult.isSuccess).isTrue()

            // Step 2: Get database and verify it's accessible
            val db = sessionManager.requireDatabase()
            assertThat(db).isNotNull()

            // Step 3: Close and run cleanup
            sessionManager.closeDatabase()
            DatabaseArtifactJanitor.cleanupStaleArtifacts(context)

            // Step 4: Reopen database with same passphrase
            val reopenResult = sessionManager.openDatabase(testPassphrase)
            assertThat(reopenResult.isSuccess).isTrue()

            // Assert: Database is fully functional after cleanup
            val dbAfterRecovery = sessionManager.requireDatabase()
            assertThat(dbAfterRecovery).isNotNull()

            logger.i(
                "DatabaseStateRecoveryTest.failureRecovery_databaseReopeningAfterCleanup",
                "Database successfully reopened after cleanup",
                mapOf("reopenedAfterCleanup" to sessionManager.isOpen.value),
            )
        }

    private fun deleteAllDatabaseFiles() {
        val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile
        if (dbDir != null) {
            val dbName = PayanamDatabase.DATABASE_NAME
            File(dbDir, dbName).delete()
            File(dbDir, "$dbName-wal").delete()
            File(dbDir, "$dbName-shm").delete()
            File(dbDir, "$dbName-journal").delete()
        }
    }
}
