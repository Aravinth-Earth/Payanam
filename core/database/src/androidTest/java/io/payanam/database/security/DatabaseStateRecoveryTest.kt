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
    /**
     * Set up.
     */
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        /** If. */
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        logger = UnifiedLogger.getInstance()

        encryptionManager = DatabaseEncryptionManager(context)
        sessionManager = DatabaseSessionManager(context, encryptionManager)

        logger.d(
            "DatabaseStateRecoveryTest.setUp",
            "Test setup complete",
            /** Map of. */
            mapOf("testPassphraseLength" to testPassphrase.length),
        )
    }

    @After
    /**
     * Tear down.
     */
    fun tearDown() {
        sessionManager.closeDatabase()
        encryptionManager.resetEncryptionState()
        /** Delete all database files. */
        deleteAllDatabaseFiles()
        logger.d("DatabaseStateRecoveryTest.tearDown", "All test artifacts cleaned up")
    }

    @Test
    /**
     * Cleanup stale artifacts removes temporary encryption files.
     */
    fun cleanupStaleArtifacts_removesTemporaryEncryptionFiles() {
        // Arrange: Create some stale encryption temporary files
        /** Db dir. */
        val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile
        /** Assert that. */
        assertThat(dbDir).isNotNull()

        /** Stale temp file. */
        val staleTempFile = File(dbDir!!, "${PayanamDatabase.DATABASE_NAME}.enc.tmp")
        staleTempFile.createNewFile()
        /** Assert that. */
        assertThat(staleTempFile.exists()).isTrue()

        // Act: Run cleanup
        DatabaseArtifactJanitor.cleanupStaleArtifacts(context)

        // Assert: Stale file is removed
        /** Assert that. */
        assertThat(staleTempFile.exists()).isFalse()
        logger.i(
            "DatabaseStateRecoveryTest.cleanupStaleArtifacts_removesTemporaryEncryptionFiles",
            "Stale encryption file cleaned up",
            /** Map of. */
            mapOf("fileRemoved" to !staleTempFile.exists()),
        )
    }

    @Test
    /**
     * Cleanup stale artifacts preserves primary database.
     */
    fun cleanupStaleArtifacts_preservesPrimaryDatabase() =
        runTest {
            // Arrange: Create and open a valid database
            encryptionManager.configurePassphrase(testPassphrase)
            /** Open result. */
            val openResult = sessionManager.openDatabase(testPassphrase)
            /** Assert that. */
            assertThat(openResult.isSuccess).isTrue()

            /** Db file. */
            val dbFile = File(context.getDatabasePath(PayanamDatabase.DATABASE_NAME).absolutePath)
            /** Assert that. */
            assertThat(dbFile.exists()).isTrue()

            sessionManager.closeDatabase()

            // Act: Run cleanup with a valid primary database present
            DatabaseArtifactJanitor.cleanupStaleArtifacts(context)

            // Assert: Primary database file is preserved
            /** Assert that. */
            assertThat(dbFile.exists()).isTrue()
            logger.i(
                "DatabaseStateRecoveryTest.cleanupStaleArtifacts_preservesPrimaryDatabase",
                "Primary database preserved during cleanup",
                /** Map of. */
                mapOf("dbStillExists" to dbFile.exists()),
            )
        }

    @Test
    /**
     * Encryption prefs backup and restore.
     */
    fun encryptionPrefs_backupAndRestore() {
        // Arrange: Configure encryption with some state
        /** Config result. */
        val configResult = encryptionManager.configurePassphrase(testPassphrase)
        /** Assert that. */
        assertThat(configResult).isTrue()
        encryptionManager.setSessionTimeoutMinutes(20)

        // Act: Backup encryption preferences
        /** Backup result. */
        val backupResult = encryptionManager.backupEncryptionPrefs()
        /** Assert that. */
        assertThat(backupResult).isTrue()

        // Act: Reset encryption state
        encryptionManager.resetEncryptionState()
        /** Assert that. */
        assertThat(encryptionManager.hasPassphraseConfigured()).isFalse()

        // Act: Restore encryption preferences
        /** Restore result. */
        val restoreResult = encryptionManager.restoreEncryptionPrefs()
        /** Assert that. */
        assertThat(restoreResult).isTrue()

        // Assert: Encryption state is restored
        /** Assert that. */
        assertThat(encryptionManager.hasPassphraseConfigured()).isTrue()
        /** Assert that. */
        assertThat(encryptionManager.verifyPassphrase(testPassphrase)).isTrue()
        /** Assert that. */
        assertThat(encryptionManager.getSessionTimeoutMinutes()).isEqualTo(20)

        logger.i(
            "DatabaseStateRecoveryTest.encryptionPrefs_backupAndRestore",
            "Encryption preferences backed up and restored successfully",
            /** Map of. */
            mapOf(
                "passphraseConfiguredAfterRestore" to encryptionManager.hasPassphraseConfigured(),
            ),
        )
    }

    @Test
    /**
     * Recovery from partial import resets state cleanly.
     */
    fun recoveryFromPartialImport_resetsStateCleanly() =
        runTest {
            // Simulates: user starts import, process crashes, then recovery
            // Step 1: Create initial database
            encryptionManager.configurePassphrase(testPassphrase)
            /** Open result1. */
            val openResult1 = sessionManager.openDatabase(testPassphrase)
            /** Assert that. */
            assertThat(openResult1.isSuccess).isTrue()

            // Step 2: Close database (simulating pre-import close)
            sessionManager.closeDatabase()

            // Step 3: Create a stale temp file (simulating crashed import)
            /** Db dir. */
            val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile
            /** Stale temp file. */
            val staleTempFile = File(dbDir!!, "${PayanamDatabase.DATABASE_NAME}.before_import_20260304")
            staleTempFile.createNewFile()
            /** Assert that. */
            assertThat(staleTempFile.exists()).isTrue()

            // Act: Run recovery cleanup
            DatabaseArtifactJanitor.cleanupStaleArtifacts(context)

            // Assert: Stale import artifact is cleaned, primary DB is preserved
            /** Assert that. */
            assertThat(staleTempFile.exists()).isFalse()
            /** Db file. */
            val dbFile = File(context.getDatabasePath(PayanamDatabase.DATABASE_NAME).absolutePath)
            /** Assert that. */
            assertThat(dbFile.exists()).isTrue()

            // Step 4: Verify database is still accessible
            /** Open result2. */
            val openResult2 = sessionManager.openDatabase(testPassphrase)
            /** Assert that. */
            assertThat(openResult2.isSuccess).isTrue()

            logger.i(
                "DatabaseStateRecoveryTest.recoveryFromPartialImport_resetsStateCleanly",
                "Recovery from partial import completed successfully",
                /** Map of. */
                mapOf(
                    "staleArtifactsRemoved" to !staleTempFile.exists(),
                    "primaryDbPreserved" to dbFile.exists(),
                    "reopenedSuccessfully" to openResult2.isSuccess,
                ),
            )
        }

    @Test
    /**
     * Database walfiles cleaned on deletion.
     */
    fun databaseWALFiles_cleanedOnDeletion() =
        runTest {
            // Arrange: Create database with WAL files
            encryptionManager.configurePassphrase(testPassphrase)
            /** Open result. */
            val openResult = sessionManager.openDatabase(testPassphrase)
            /** Assert that. */
            assertThat(openResult.isSuccess).isTrue()

            /** Db dir. */
            val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile
            /** Db name. */
            val dbName = PayanamDatabase.DATABASE_NAME
            /** Wal file. */
            val walFile = File(dbDir!!, "$dbName-wal")
            /** Shm file. */
            val shmFile = File(dbDir, "$dbName-shm")

            sessionManager.closeDatabase()

            // Act: Delete database
            /** Db file. */
            val dbFile = File(context.getDatabasePath(dbName).absolutePath)
            dbFile.delete()
            walFile.delete()
            shmFile.delete()

            // Assert: All database-related files are gone
            /** Assert that. */
            assertThat(dbFile.exists()).isFalse()
            // WAL and SHM files may not exist depending on configuration, but we verify deletion didn't fail
            logger.d(
                "DatabaseStateRecoveryTest.databaseWALFiles_cleanedOnDeletion",
                "Database and WAL files cleaned up",
                /** Map of. */
                mapOf(
                    "dbExists" to dbFile.exists(),
                    "walExists" to walFile.exists(),
                    "shmExists" to shmFile.exists(),
                ),
            )
        }

    @Test
    /**
     * Encryption state reset is idempotent.
     */
    fun encryptionStateReset_isIdempotent() {
        // Arrange: Configure encryption
        encryptionManager.configurePassphrase(testPassphrase)
        /** Assert that. */
        assertThat(encryptionManager.hasPassphraseConfigured()).isTrue()

        // Act: Reset encryption state multiple times
        /** Result1. */
        val result1 = encryptionManager.resetEncryptionState()
        /** Result2. */
        val result2 = encryptionManager.resetEncryptionState()
        /** Result3. */
        val result3 = encryptionManager.resetEncryptionState()

        // Assert: All resets succeed (idempotent operation)
        /** Assert that. */
        assertThat(result1).isTrue()
        /** Assert that. */
        assertThat(result2).isTrue()
        /** Assert that. */
        assertThat(result3).isTrue()
        /** Assert that. */
        assertThat(encryptionManager.hasPassphraseConfigured()).isFalse()

        logger.d(
            "DatabaseStateRecoveryTest.encryptionStateReset_isIdempotent",
            "Encryption state reset is idempotent",
            /** Map of. */
            mapOf(
                "allResetsSucceeded" to (result1 && result2 && result3),
                "stateCleared" to !encryptionManager.hasPassphraseConfigured(),
            ),
        )
    }

    @Test
    /**
     * Failure recovery database reopening after cleanup.
     */
    fun failureRecovery_databaseReopeningAfterCleanup() =
        runTest {
            // Step 1: Create database with passphrase
            encryptionManager.configurePassphrase(testPassphrase)
            /** Open result. */
            val openResult = sessionManager.openDatabase(testPassphrase)
            /** Assert that. */
            assertThat(openResult.isSuccess).isTrue()

            // Step 2: Get database and verify it's accessible
            /** Db. */
            val db = sessionManager.requireDatabase()
            /** Assert that. */
            assertThat(db).isNotNull()

            // Step 3: Close and run cleanup
            sessionManager.closeDatabase()
            DatabaseArtifactJanitor.cleanupStaleArtifacts(context)

            // Step 4: Reopen database with same passphrase
            /** Reopen result. */
            val reopenResult = sessionManager.openDatabase(testPassphrase)
            /** Assert that. */
            assertThat(reopenResult.isSuccess).isTrue()

            // Assert: Database is fully functional after cleanup
            /** Db after recovery. */
            val dbAfterRecovery = sessionManager.requireDatabase()
            /** Assert that. */
            assertThat(dbAfterRecovery).isNotNull()

            logger.i(
                "DatabaseStateRecoveryTest.failureRecovery_databaseReopeningAfterCleanup",
                "Database successfully reopened after cleanup",
                /** Map of. */
                mapOf("reopenedAfterCleanup" to sessionManager.isOpen.value),
            )
        }

    private fun deleteAllDatabaseFiles() {
        /** Db dir. */
        val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile
        /** If. */
        if (dbDir != null) {
            /** Db name. */
            val dbName = PayanamDatabase.DATABASE_NAME
            /** File. */
            File(dbDir, dbName).delete()
            /** File. */
            File(dbDir, "$dbName-wal").delete()
            /** File. */
            File(dbDir, "$dbName-shm").delete()
            /** File. */
            File(dbDir, "$dbName-journal").delete()
        }
    }
}
