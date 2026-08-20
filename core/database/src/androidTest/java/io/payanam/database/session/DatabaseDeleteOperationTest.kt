//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import io.payanam.database.security.DatabaseEncryptionManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Tests for database deletion and cleanup operations.
 * Verifies that database files are properly removed and encryption state is cleared.
 * Critical for data entry flows: clean import, factory reset, account logout.
 */
class DatabaseDeleteOperationTest {
    private lateinit var context: Context
    private lateinit var logger: UnifiedLogger
    private lateinit var encryptionManager: DatabaseEncryptionManager
    private lateinit var sessionManager: DatabaseSessionManager

    private val testPassphrase = "DeleteTest123!"

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
            "DatabaseDeleteOperationTest.setUp",
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
        // Clean up database files
        /** Delete database. */
        deleteDatabase(PayanamDatabase.DATABASE_NAME)
        logger.d("DatabaseDeleteOperationTest.tearDown", "Database and encryption state cleaned up")
    }

    @Test
    /**
     * Delete database removes physical database file.
     */
    fun deleteDatabase_removesPhysicalDatabaseFile() =
        runTest {
            // Arrange: Create and open database
            encryptionManager.configurePassphrase(testPassphrase)
            /** Open result. */
            val openResult = sessionManager.openDatabase(testPassphrase)
            /** Assert that. */
            assertThat(openResult.isSuccess).isTrue()

            /** Db file. */
            val dbFile = File(context.getDatabasePath(PayanamDatabase.DATABASE_NAME).absolutePath)
            /** Assert that. */
            assertThat(dbFile.exists()).isTrue()
            logger.d(
                "DatabaseDeleteOperationTest.deleteDatabase_removesPhysicalDatabaseFile",
                "Database file exists before deletion",
                /** Map of. */
                mapOf("path" to dbFile.absolutePath),
            )

            // Act: Close and delete database
            sessionManager.closeDatabase()
            /** Delete database. */
            deleteDatabase(PayanamDatabase.DATABASE_NAME)

            // Assert: Database file no longer exists
            /** Still exists. */
            val stillExists = dbFile.exists()
            /** Assert that. */
            assertThat(stillExists).isFalse()
            logger.i(
                "DatabaseDeleteOperationTest.deleteDatabase_removesPhysicalDatabaseFile",
                "Database file successfully deleted",
                /** Map of. */
                mapOf("fileRemoved" to !stillExists),
            )
        }

    @Test
    /**
     * Delete database clears encryption state.
     */
    fun deleteDatabase_clearsEncryptionState() =
        runTest {
            // Arrange: Configure passphrase and create database
            /** Config result. */
            val configResult = encryptionManager.configurePassphrase(testPassphrase)
            /** Assert that. */
            assertThat(configResult).isTrue()
            /** Assert that. */
            assertThat(encryptionManager.hasPassphraseConfigured()).isTrue()

            /** Open result. */
            val openResult = sessionManager.openDatabase(testPassphrase)
            /** Assert that. */
            assertThat(openResult.isSuccess).isTrue()

            // Act: Close database and reset encryption state
            sessionManager.closeDatabase()
            /** Reset result. */
            val resetResult = encryptionManager.resetEncryptionState()

            // Assert: Encryption state is cleared
            /** Assert that. */
            assertThat(resetResult).isTrue()
            /** Assert that. */
            assertThat(encryptionManager.hasPassphraseConfigured()).isFalse()
            /** Assert that. */
            assertThat(encryptionManager.isEncryptionEnabled()).isFalse()
            logger.i(
                "DatabaseDeleteOperationTest.deleteDatabase_clearsEncryptionState",
                "Encryption state successfully reset",
                /** Map of. */
                mapOf(
                    "resetSucceeded" to resetResult,
                    "passphraseConfigured" to encryptionManager.hasPassphraseConfigured(),
                ),
            )
        }

    @Test
    /**
     * Delete database allows recreation with different passphrase.
     */
    fun deleteDatabase_allowsRecreationWithDifferentPassphrase() =
        runTest {
            // Arrange: Create database with first passphrase
            /** Pass1. */
            val pass1 = "FirstPass123!"
            /** Pass2. */
            val pass2 = "SecondPass456!"

            encryptionManager.configurePassphrase(pass1)
            /** Open result1. */
            val openResult1 = sessionManager.openDatabase(pass1)
            /** Assert that. */
            assertThat(openResult1.isSuccess).isTrue()

            // Act: Close, delete, and reset
            sessionManager.closeDatabase()
            /** Delete database. */
            deleteDatabase(PayanamDatabase.DATABASE_NAME)
            encryptionManager.resetEncryptionState()

            // Act: Create new database with different passphrase
            /** Config result2. */
            val configResult2 = encryptionManager.configurePassphrase(pass2)
            /** Open result2. */
            val openResult2 = sessionManager.openDatabase(pass2)

            // Assert: New database opens successfully with different passphrase
            /** Assert that. */
            assertThat(configResult2).isTrue()
            /** Assert that. */
            assertThat(openResult2.isSuccess).isTrue()
            /** Assert that. */
            assertThat(encryptionManager.verifyPassphrase(pass2)).isTrue()
            /** Assert that. */
            assertThat(encryptionManager.verifyPassphrase(pass1)).isFalse()

            logger.i(
                "DatabaseDeleteOperationTest.deleteDatabase_allowsRecreationWithDifferentPassphrase",
                "Database successfully recreated with different passphrase",
                /** Map of. */
                mapOf(
                    "newPassphraseVerified" to encryptionManager.verifyPassphrase(pass2),
                    "oldPassphraseRejected" to !encryptionManager.verifyPassphrase(pass1),
                ),
            )
        }

    @Test
    /**
     * Delete database handles multiple consecutive deletions.
     */
    fun deleteDatabase_handlesMultipleConsecutiveDeletions() =
        runTest {
            // Arrange: Create database
            encryptionManager.configurePassphrase(testPassphrase)
            /** Open result. */
            val openResult = sessionManager.openDatabase(testPassphrase)
            /** Assert that. */
            assertThat(openResult.isSuccess).isTrue()

            sessionManager.closeDatabase()
            /** Db file. */
            val dbFile = File(context.getDatabasePath(PayanamDatabase.DATABASE_NAME).absolutePath)

            // Act: First deletion
            /** Delete database. */
            deleteDatabase(PayanamDatabase.DATABASE_NAME)
            /** Assert that. */
            assertThat(dbFile.exists()).isFalse()

            // Act: Second deletion (should not crash even if file already gone)
            try {
                /** Delete database. */
                deleteDatabase(PayanamDatabase.DATABASE_NAME)
                // If we get here, deletion was idempotent
                /** Assert that. */
                assertThat(true).isTrue()
                logger.d(
                    "DatabaseDeleteOperationTest.deleteDatabase_handlesMultipleConsecutiveDeletions",
                    "Multiple deletions handled gracefully (idempotent)",
                )
            } catch (e: Exception) {
                logger.e(
                    "DatabaseDeleteOperationTest.deleteDatabase_handlesMultipleConsecutiveDeletions",
                    "Unexpected error during second deletion",
                    /** E. */
                    e,
                )
                throw e
            }
        }

    @Test
    /**
     * Session manager clears state on database close.
     */
    fun sessionManager_clearsStateOnDatabaseClose() =
        runTest {
            // Arrange: Open database
            encryptionManager.configurePassphrase(testPassphrase)
            /** Open result. */
            val openResult = sessionManager.openDatabase(testPassphrase)
            /** Assert that. */
            assertThat(openResult.isSuccess).isTrue()
            /** Assert that. */
            assertThat(sessionManager.isOpen.value).isTrue()

            // Act: Close database
            sessionManager.closeDatabase()

            // Assert: All session state is cleared
            /** Assert that. */
            assertThat(sessionManager.isOpen.value).isFalse()

            try {
                sessionManager.requireDatabase()
                /** Assert that. */
                assertThat(false).isTrue() // Should not reach here
            } catch (e: IllegalStateException) {
                // Expected: database should not be available after close
                logger.d(
                    "DatabaseDeleteOperationTest.sessionManager_clearsStateOnDatabaseClose",
                    "Database access correctly blocked after close",
                    /** Map of. */
                    mapOf("exceptionMessage" to (e.message ?: "")),
                )
            }
        }

    @Test
    /**
     * Delete database complete import scenario.
     */
    fun deleteDatabase_completeImportScenario() =
        runTest {
            // Simulates: user imports new DB file, clearing old one
            // Step 1: Create initial database
            encryptionManager.configurePassphrase(testPassphrase)
            /** Open result1. */
            val openResult1 = sessionManager.openDatabase(testPassphrase)
            /** Assert that. */
            assertThat(openResult1.isSuccess).isTrue()

            // Verify database is populated (in real scenario, would have actual data)
            /** Db1. */
            val db1 = sessionManager.requireDatabase()
            /** Assert that. */
            assertThat(db1).isNotNull()

            // Step 2: Close database before import
            sessionManager.closeDatabase()
            /** Assert that. */
            assertThat(sessionManager.isOpen.value).isFalse()

            // Step 3: Delete old database
            /** Delete database. */
            deleteDatabase(PayanamDatabase.DATABASE_NAME)
            /** Db file. */
            val dbFile = File(context.getDatabasePath(PayanamDatabase.DATABASE_NAME).absolutePath)
            /** Assert that. */
            assertThat(dbFile.exists()).isFalse()

            // Step 4: Reset encryption state for new import
            encryptionManager.resetEncryptionState()
            /** Assert that. */
            assertThat(encryptionManager.hasPassphraseConfigured()).isFalse()

            // Step 5: Configure new passphrase and open "imported" database
            /** New passphrase. */
            val newPassphrase = "ImportedDBPass123!"
            /** Config result. */
            val configResult = encryptionManager.configurePassphrase(newPassphrase)
            /** Open result2. */
            val openResult2 = sessionManager.openDatabase(newPassphrase)

            // Assert: Import flow completed successfully
            /** Assert that. */
            assertThat(configResult).isTrue()
            /** Assert that. */
            assertThat(openResult2.isSuccess).isTrue()
            /** Assert that. */
            assertThat(encryptionManager.verifyPassphrase(newPassphrase)).isTrue()

            logger.i(
                "DatabaseDeleteOperationTest.deleteDatabase_completeImportScenario",
                "Complete import scenario (delete old, set new) succeeded",
                /** Map of. */
                mapOf(
                    "oldDbDeleted" to !dbFile.exists(),
                    "newDbOpened" to sessionManager.isOpen.value,
                ),
            )
        }

    private fun deleteDatabase(databaseName: String) {
        /** Db file. */
        val dbFile = File(context.getDatabasePath(databaseName).absolutePath)
        /** Wal file. */
        val walFile = File(context.getDatabasePath("$databaseName-wal").absolutePath)
        /** Shm file. */
        val shmFile = File(context.getDatabasePath("$databaseName-shm").absolutePath)

        dbFile.delete()
        walFile.delete()
        shmFile.delete()

        logger.d(
            "DatabaseDeleteOperationTest.deleteDatabase",
            "Database files deleted",
            /** Map of. */
            mapOf(
                "dbPath" to dbFile.absolutePath,
                "dbExists" to dbFile.exists(),
                "walExists" to walFile.exists(),
                "shmExists" to shmFile.exists(),
            ),
        )
    }
}
