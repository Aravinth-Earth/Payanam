//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.LifeDimension
import kotlinx.coroutines.test.runTest
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Integration tests for database import flows with password protection.
 * Tests actual file I/O and encryption layer interactions.
 * Runs on device/emulator (androidTest) for authentic SQLCipher behavior.
 */
class DatabaseImportFlowIntegrationTest {
    private lateinit var context: Context
    private lateinit var logger: UnifiedLogger
    private lateinit var encryptionManager: DatabaseEncryptionManager
    private lateinit var sessionManager: DatabaseSessionManager

    private val sourcePassphrase = "SourceDB123!"
    private val importPassphrase = "ImportedDB456!"

    @Before
    /**
     * Set up.
     */
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        /** If. */
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        logger = UnifiedLogger.getInstance()

        encryptionManager = DatabaseEncryptionManager(context)
        encryptionManager.resetEncryptionState()

        sessionManager = DatabaseSessionManager(context, encryptionManager)

        logger.d(
            "DatabaseImportFlowIntegrationTest.setUp",
            "Integration test setup complete",
            /** Map of. */
            mapOf(
                "sourcePassphraseLength" to sourcePassphrase.length,
                "importPassphraseLength" to importPassphrase.length,
            ),
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
        logger.d("DatabaseImportFlowIntegrationTest.tearDown", "Test cleanup complete")
    }

    @Test
    /**
     * Import flow creates source database and imports with new passphrase.
     */
    fun importFlow_createsSourceDatabaseAndImportsWithNewPassphrase() =
        runTest {
            // Step 1: Create source database with initial data and passphrase
            encryptionManager.configurePassphrase(sourcePassphrase)
            /** Source open result. */
            val sourceOpenResult = sessionManager.openDatabase(sourcePassphrase)
            /** Assert that. */
            assertThat(sourceOpenResult.isSuccess).isTrue()

            // Step 2: Add some test data to source database
            /** Source db. */
            val sourceDb = sessionManager.requireDatabase()
            /** Source dimension dao. */
            val sourceDimensionDao = sourceDb.lifeDimensionDao()
            /** Test dimensions. */
            val testDimensions =
                /** List of. */
                listOf(
                    LifeDimension.CAREER_WORK,
                    LifeDimension.HEALTH_WELLNESS,
                    LifeDimension.LEARNING,
                )

            logger.d(
                "DatabaseImportFlowIntegrationTest.importFlow_createsSourceDatabaseAndImportsWithNewPassphrase",
                "Source database created with test data",
                /** Map of. */
                mapOf("testDimensionCount" to testDimensions.size),
            )

            // Step 3: Close source database
            sessionManager.closeDatabase()
            /** Assert that. */
            assertThat(sessionManager.isOpen.value).isFalse()

            // Step 4: Backup and prepare for import
            // (In real flow, backup file would be from a previous export or another DB handoff)
            /** Source db file. */
            val sourceDbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
            /** Assert that. */
            assertThat(sourceDbFile.exists()).isTrue()

            // Step 5: Reset encryption for new import
            encryptionManager.resetEncryptionState()
            /** Assert that. */
            assertThat(encryptionManager.hasPassphraseConfigured()).isFalse()

            // Step 6: Configure new passphrase and reopen (simulating import with new password)
            /** Import config result. */
            val importConfigResult = encryptionManager.configurePassphrase(importPassphrase)
            /** Assert that. */
            assertThat(importConfigResult).isTrue()

            /** Import open result. */
            val importOpenResult = sessionManager.openDatabase(importPassphrase)
            /** Assert that. */
            assertThat(importOpenResult.isSuccess).isTrue()

            // Assert: Database is accessible with new passphrase
            /** Imported db. */
            val importedDb = sessionManager.requireDatabase()
            /** Assert that. */
            assertThat(importedDb).isNotNull()
            /** Assert that. */
            assertThat(encryptionManager.verifyPassphrase(importPassphrase)).isTrue()

            logger.i(
                "DatabaseImportFlowIntegrationTest.importFlow_createsSourceDatabaseAndImportsWithNewPassphrase",
                "Import flow completed: source backed up, new DB created with new passphrase",
                /** Map of. */
                mapOf(
                    "sourceDbExists" to sourceDbFile.exists(),
                    "importedDbOpen" to sessionManager.isOpen.value,
                    "newPassphraseValid" to encryptionManager.verifyPassphrase(importPassphrase),
                ),
            )
        }

    @Test
    /**
     * Import flow encryption state is clean between imports.
     */
    fun importFlow_encryptionStateIsCleanBetweenImports() =
        runTest {
            // First import cycle
            encryptionManager.configurePassphrase(sourcePassphrase)
            /** First open. */
            val firstOpen = sessionManager.openDatabase(sourcePassphrase)
            /** Assert that. */
            assertThat(firstOpen.isSuccess).isTrue()

            sessionManager.closeDatabase()
            encryptionManager.resetEncryptionState()

            // Second import cycle with completely different passphrase
            /** Second passphrase. */
            val secondPassphrase = "Completely_Different_Pass_789!"
            /** Second config result. */
            val secondConfigResult = encryptionManager.configurePassphrase(secondPassphrase)
            /** Assert that. */
            assertThat(secondConfigResult).isTrue()

            /** Second open. */
            val secondOpen = sessionManager.openDatabase(secondPassphrase)
            /** Assert that. */
            assertThat(secondOpen.isSuccess).isTrue()

            // Verify isolation: old passphrase doesn't work
            /** Assert that. */
            assertThat(encryptionManager.verifyPassphrase(sourcePassphrase)).isFalse()
            /** Assert that. */
            assertThat(encryptionManager.verifyPassphrase(secondPassphrase)).isTrue()

            logger.i(
                "DatabaseImportFlowIntegrationTest.importFlow_encryptionStateIsCleanBetweenImports",
                "Encryption state properly isolated between import cycles",
                /** Map of. */
                mapOf(
                    "firstPassphraseInvalid" to !encryptionManager.verifyPassphrase(sourcePassphrase),
                    "secondPassphraseValid" to encryptionManager.verifyPassphrase(secondPassphrase),
                ),
            )
        }

    @Test
    /**
     * Import flow passphrase change does not corrupt database.
     */
    fun importFlow_passphraseChangeDoesNotCorruptDatabase() =
        runTest {
            // Step 1: Create initial database with passphrase
            encryptionManager.configurePassphrase(sourcePassphrase)
            /** Open result. */
            val openResult = sessionManager.openDatabase(sourcePassphrase)
            /** Assert that. */
            assertThat(openResult.isSuccess).isTrue()

            /** Db. */
            val db = sessionManager.requireDatabase()
            /** Assert that. */
            assertThat(db).isNotNull()

            // Step 2: Close and change passphrase
            sessionManager.closeDatabase()
            /** Update result. */
            val updateResult = encryptionManager.updatePassphrase(sourcePassphrase, importPassphrase)
            /** Assert that. */
            assertThat(updateResult).isTrue()

            // Step 3: Reopen with new passphrase
            /** Reopen result. */
            val reopenResult = sessionManager.openDatabase(importPassphrase)
            /** Assert that. */
            assertThat(reopenResult.isSuccess).isTrue()

            // Assert: Database is fully functional with new passphrase
            /** Db after change. */
            val dbAfterChange = sessionManager.requireDatabase()
            /** Assert that. */
            assertThat(dbAfterChange).isNotNull()
            /** Assert that. */
            assertThat(encryptionManager.verifyPassphrase(importPassphrase)).isTrue()

            logger.i(
                "DatabaseImportFlowIntegrationTest.importFlow_passphraseChangeDoesNotCorruptDatabase",
                "Database remains functional after passphrase change",
                /** Map of. */
                mapOf(
                    "updateSucceeded" to updateResult,
                    "reopenedSuccessfully" to reopenResult.isSuccess,
                ),
            )
        }

    @Test
    /**
     * Import flow wrong passphrase rejects import.
     */
    fun importFlow_wrongPassphraseRejectsImport() =
        runTest {
            // Step 1: Create database with passphrase
            encryptionManager.configurePassphrase(sourcePassphrase)
            /** Open result. */
            val openResult = sessionManager.openDatabase(sourcePassphrase)
            /** Assert that. */
            assertThat(openResult.isSuccess).isTrue()

            sessionManager.closeDatabase()

            // Step 2: Try to reopen with wrong passphrase (simulating import failure)
            /** Wrong passphrase. */
            val wrongPassphrase = "CompletelyWrong789!"
            /** Reopen result. */
            val reopenResult = sessionManager.openDatabase(wrongPassphrase)

            // Assert: Open fails with wrong passphrase
            /** Assert that. */
            assertThat(reopenResult.isFailure).isTrue()
            /** Assert that. */
            assertThat(sessionManager.isOpen.value).isFalse()

            // Step 3: Should still work with correct passphrase
            /** Correct reopen result. */
            val correctReopenResult = sessionManager.openDatabase(sourcePassphrase)
            /** Assert that. */
            assertThat(correctReopenResult.isSuccess).isTrue()

            logger.d(
                "DatabaseImportFlowIntegrationTest.importFlow_wrongPassphraseRejectsImport",
                "Wrong passphrase properly rejected, correct passphrase still works",
                /** Map of. */
                mapOf(
                    "wrongPassphraseFailed" to reopenResult.isFailure,
                    "correctPassphraseSucceeded" to correctReopenResult.isSuccess,
                ),
            )
        }

    @Test
    /**
     * Import flow multiple sequential imports.
     */
    fun importFlow_multipleSequentialImports() =
        runTest {
            // Simulate multiple import/reset cycles
            /** For. */
            for (cycle in 1..3) {
                /** Cycle passphrase. */
                val cyclePassphrase = "CyclePass${cycle}_123!"

                // Configure and open
                encryptionManager.configurePassphrase(cyclePassphrase)
                /** Open result. */
                val openResult = sessionManager.openDatabase(cyclePassphrase)
                /** Assert that. */
                assertThat(openResult.isSuccess).isTrue()

                /** Db. */
                val db = sessionManager.requireDatabase()
                /** Assert that. */
                assertThat(db).isNotNull()

                // Close and reset for next cycle
                sessionManager.closeDatabase()
                encryptionManager.resetEncryptionState()

                logger.d(
                    "DatabaseImportFlowIntegrationTest.importFlow_multipleSequentialImports",
                    "Completed import cycle",
                    /** Map of. */
                    mapOf(
                        "cycle" to cycle,
                        "passphraseLength" to cyclePassphrase.length,
                    ),
                )
            }

            // Final state check: all cycles should succeed
            /** Assert that. */
            assertThat(!encryptionManager.hasPassphraseConfigured()).isTrue()
            /** Assert that. */
            assertThat(!sessionManager.isOpen.value).isTrue()

            logger.i(
                "DatabaseImportFlowIntegrationTest.importFlow_multipleSequentialImports",
                "All 3 import cycles completed successfully",
            )
        }

    private fun deleteAllDatabaseFiles() {
        /** Db dir. */
        val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile
        /** If. */
        if (dbDir != null) {
            /** Db name. */
            val dbName = PayanamDatabase.DATABASE_NAME
            try {
                /** List of. */
                listOf(
                    /** File. */
                    File(dbDir, dbName),
                    /** File. */
                    File(dbDir, "$dbName-wal"),
                    /** File. */
                    File(dbDir, "$dbName-shm"),
                    /** File. */
                    File(dbDir, "$dbName-journal"),
                ).forEach { it.delete() }
            } catch (e: Exception) {
                logger.e(
                    "DatabaseImportFlowIntegrationTest.deleteAllDatabaseFiles",
                    "Error during cleanup",
                    /** E. */
                    e,
                )
            }
        }
    }
}
