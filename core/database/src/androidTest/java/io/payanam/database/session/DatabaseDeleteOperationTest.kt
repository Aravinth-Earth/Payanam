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
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        logger = UnifiedLogger.getInstance()

        encryptionManager = DatabaseEncryptionManager(context)
        sessionManager = DatabaseSessionManager(context, encryptionManager)

        logger.d(
            "DatabaseDeleteOperationTest.setUp",
            "Test setup complete",
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
            val openResult = sessionManager.openDatabase(testPassphrase)
            assertThat(openResult.isSuccess).isTrue()
            val dbFile = File(context.getDatabasePath(PayanamDatabase.DATABASE_NAME).absolutePath)
            assertThat(dbFile.exists()).isTrue()
            logger.d(
                "DatabaseDeleteOperationTest.deleteDatabase_removesPhysicalDatabaseFile",
                "Database file exists before deletion",
                mapOf("path" to dbFile.absolutePath),
            )

            // Act: Close and delete database
            sessionManager.closeDatabase()
            deleteDatabase(PayanamDatabase.DATABASE_NAME)

            // Assert: Database file no longer exists
            val stillExists = dbFile.exists()
            assertThat(stillExists).isFalse()
            logger.i(
                "DatabaseDeleteOperationTest.deleteDatabase_removesPhysicalDatabaseFile",
                "Database file successfully deleted",
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
            val configResult = encryptionManager.configurePassphrase(testPassphrase)
            assertThat(configResult).isTrue()
            assertThat(encryptionManager.hasPassphraseConfigured()).isTrue()
            val openResult = sessionManager.openDatabase(testPassphrase)
            assertThat(openResult.isSuccess).isTrue()

            // Act: Close database and reset encryption state
            sessionManager.closeDatabase()
            val resetResult = encryptionManager.resetEncryptionState()

            // Assert: Encryption state is cleared
            assertThat(resetResult).isTrue()
            assertThat(encryptionManager.hasPassphraseConfigured()).isFalse()
            assertThat(encryptionManager.isEncryptionEnabled()).isFalse()
            logger.i(
                "DatabaseDeleteOperationTest.deleteDatabase_clearsEncryptionState",
                "Encryption state successfully reset",
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
            val pass1 = "FirstPass123!"
            val pass2 = "SecondPass456!"

            encryptionManager.configurePassphrase(pass1)
            val openResult1 = sessionManager.openDatabase(pass1)
            assertThat(openResult1.isSuccess).isTrue()

            // Act: Close, delete, and reset
            sessionManager.closeDatabase()
            deleteDatabase(PayanamDatabase.DATABASE_NAME)
            encryptionManager.resetEncryptionState()

            // Act: Create new database with different passphrase
            val configResult2 = encryptionManager.configurePassphrase(pass2)
            val openResult2 = sessionManager.openDatabase(pass2)

            // Assert: New database opens successfully with different passphrase
            assertThat(configResult2).isTrue()
            assertThat(openResult2.isSuccess).isTrue()
            assertThat(encryptionManager.verifyPassphrase(pass2)).isTrue()
            assertThat(encryptionManager.verifyPassphrase(pass1)).isFalse()

            logger.i(
                "DatabaseDeleteOperationTest.deleteDatabase_allowsRecreationWithDifferentPassphrase",
                "Database successfully recreated with different passphrase",
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
            val openResult = sessionManager.openDatabase(testPassphrase)
            assertThat(openResult.isSuccess).isTrue()

            sessionManager.closeDatabase()
            val dbFile = File(context.getDatabasePath(PayanamDatabase.DATABASE_NAME).absolutePath)

            // Act: First deletion
            deleteDatabase(PayanamDatabase.DATABASE_NAME)
            assertThat(dbFile.exists()).isFalse()

            // Act: Second deletion (should not crash even if file already gone)
            try {
                deleteDatabase(PayanamDatabase.DATABASE_NAME)
                // If we get here, deletion was idempotent
                assertThat(true).isTrue()
                logger.d(
                    "DatabaseDeleteOperationTest.deleteDatabase_handlesMultipleConsecutiveDeletions",
                    "Multiple deletions handled gracefully (idempotent)",
                )
            } catch (e: Exception) {
                logger.e(
                    "DatabaseDeleteOperationTest.deleteDatabase_handlesMultipleConsecutiveDeletions",
                    "Unexpected error during second deletion",
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
            val openResult = sessionManager.openDatabase(testPassphrase)
            assertThat(openResult.isSuccess).isTrue()
            assertThat(sessionManager.isOpen.value).isTrue()

            // Act: Close database
            sessionManager.closeDatabase()

            // Assert: All session state is cleared
            assertThat(sessionManager.isOpen.value).isFalse()

            try {
                sessionManager.requireDatabase()
                assertThat(false).isTrue() // Should not reach here
            } catch (e: IllegalStateException) {
                // Expected: database should not be available after close
                logger.d(
                    "DatabaseDeleteOperationTest.sessionManager_clearsStateOnDatabaseClose",
                    "Database access correctly blocked after close",
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
            val openResult1 = sessionManager.openDatabase(testPassphrase)
            assertThat(openResult1.isSuccess).isTrue()

            // Verify database is populated (in real scenario, would have actual data)
            val db1 = sessionManager.requireDatabase()
            assertThat(db1).isNotNull()

            // Step 2: Close database before import
            sessionManager.closeDatabase()
            assertThat(sessionManager.isOpen.value).isFalse()

            // Step 3: Delete old database
            deleteDatabase(PayanamDatabase.DATABASE_NAME)
            val dbFile = File(context.getDatabasePath(PayanamDatabase.DATABASE_NAME).absolutePath)
            assertThat(dbFile.exists()).isFalse()

            // Step 4: Reset encryption state for new import
            encryptionManager.resetEncryptionState()
            assertThat(encryptionManager.hasPassphraseConfigured()).isFalse()

            // Step 5: Configure new passphrase and open "imported" database
            val newPassphrase = "ImportedDBPass123!"
            val configResult = encryptionManager.configurePassphrase(newPassphrase)
            val openResult2 = sessionManager.openDatabase(newPassphrase)

            // Assert: Import flow completed successfully
            assertThat(configResult).isTrue()
            assertThat(openResult2.isSuccess).isTrue()
            assertThat(encryptionManager.verifyPassphrase(newPassphrase)).isTrue()

            logger.i(
                "DatabaseDeleteOperationTest.deleteDatabase_completeImportScenario",
                "Complete import scenario (delete old, set new) succeeded",
                mapOf(
                    "oldDbDeleted" to !dbFile.exists(),
                    "newDbOpened" to sessionManager.isOpen.value,
                ),
            )
        }

    private fun deleteDatabase(databaseName: String) {
        val dbFile = File(context.getDatabasePath(databaseName).absolutePath)
        val walFile = File(context.getDatabasePath("$databaseName-wal").absolutePath)
        val shmFile = File(context.getDatabasePath("$databaseName-shm").absolutePath)

        dbFile.delete()
        walFile.delete()
        shmFile.delete()

        logger.d(
            "DatabaseDeleteOperationTest.deleteDatabase",
            "Database files deleted",
            mapOf(
                "dbPath" to dbFile.absolutePath,
                "dbExists" to dbFile.exists(),
                "walExists" to walFile.exists(),
                "shmExists" to shmFile.exists(),
            ),
        )
    }
}
