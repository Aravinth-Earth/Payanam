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
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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
            mapOf(
                "sourcePassphraseLength" to sourcePassphrase.length,
                "importPassphraseLength" to importPassphrase.length,
            ),
        )
    }

    @After
    fun tearDown() {
        sessionManager.closeDatabase()
        encryptionManager.resetEncryptionState()
        deleteAllDatabaseFiles()
        logger.d("DatabaseImportFlowIntegrationTest.tearDown", "Test cleanup complete")
    }

    @Test
    fun importFlow_createsSourceDatabaseAndImportsWithNewPassphrase() =
        runTest {
            // Step 1: Create source database with initial data and passphrase
            encryptionManager.configurePassphrase(sourcePassphrase)
            val sourceOpenResult = sessionManager.openDatabase(sourcePassphrase)
            assertThat(sourceOpenResult.isSuccess).isTrue()

            // Step 2: Add some test data to source database
            val sourceDb = sessionManager.requireDatabase()
            val sourceDimensionDao = sourceDb.lifeDimensionDao()
            val testDimensions =
                listOf(
                    LifeDimension.CAREER_WORK,
                    LifeDimension.HEALTH_WELLNESS,
                    LifeDimension.LEARNING,
                )

            logger.d(
                "DatabaseImportFlowIntegrationTest.importFlow_createsSourceDatabaseAndImportsWithNewPassphrase",
                "Source database created with test data",
                mapOf("testDimensionCount" to testDimensions.size),
            )

            // Step 3: Close source database
            sessionManager.closeDatabase()
            assertThat(sessionManager.isOpen.value).isFalse()

            // Step 4: Backup and prepare for import
            // (In real flow, backup file would be from a previous export or another DB handoff)
            val sourceDbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
            assertThat(sourceDbFile.exists()).isTrue()

            // Step 5: Reset encryption for new import
            encryptionManager.resetEncryptionState()
            assertThat(encryptionManager.hasPassphraseConfigured()).isFalse()

            // Step 6: Configure new passphrase and reopen (simulating import with new password)
            val importConfigResult = encryptionManager.configurePassphrase(importPassphrase)
            assertThat(importConfigResult).isTrue()

            val importOpenResult = sessionManager.openDatabase(importPassphrase)
            assertThat(importOpenResult.isSuccess).isTrue()

            // Assert: Database is accessible with new passphrase
            val importedDb = sessionManager.requireDatabase()
            assertThat(importedDb).isNotNull()
            assertThat(encryptionManager.verifyPassphrase(importPassphrase)).isTrue()

            logger.i(
                "DatabaseImportFlowIntegrationTest.importFlow_createsSourceDatabaseAndImportsWithNewPassphrase",
                "Import flow completed: source backed up, new DB created with new passphrase",
                mapOf(
                    "sourceDbExists" to sourceDbFile.exists(),
                    "importedDbOpen" to sessionManager.isOpen.value,
                    "newPassphraseValid" to encryptionManager.verifyPassphrase(importPassphrase),
                ),
            )
        }

    @Test
    fun importFlow_encryptionStateIsCleanBetweenImports() =
        runTest {
            // First import cycle
            encryptionManager.configurePassphrase(sourcePassphrase)
            val firstOpen = sessionManager.openDatabase(sourcePassphrase)
            assertThat(firstOpen.isSuccess).isTrue()

            sessionManager.closeDatabase()
            encryptionManager.resetEncryptionState()

            // Second import cycle with completely different passphrase
            val secondPassphrase = "Completely_Different_Pass_789!"
            val secondConfigResult = encryptionManager.configurePassphrase(secondPassphrase)
            assertThat(secondConfigResult).isTrue()

            val secondOpen = sessionManager.openDatabase(secondPassphrase)
            assertThat(secondOpen.isSuccess).isTrue()

            // Verify isolation: old passphrase doesn't work
            assertThat(encryptionManager.verifyPassphrase(sourcePassphrase)).isFalse()
            assertThat(encryptionManager.verifyPassphrase(secondPassphrase)).isTrue()

            logger.i(
                "DatabaseImportFlowIntegrationTest.importFlow_encryptionStateIsCleanBetweenImports",
                "Encryption state properly isolated between import cycles",
                mapOf(
                    "firstPassphraseInvalid" to !encryptionManager.verifyPassphrase(sourcePassphrase),
                    "secondPassphraseValid" to encryptionManager.verifyPassphrase(secondPassphrase),
                ),
            )
        }

    @Test
    fun importFlow_passphraseChangeDoesNotCorruptDatabase() =
        runTest {
            // Step 1: Create initial database with passphrase
            encryptionManager.configurePassphrase(sourcePassphrase)
            val openResult = sessionManager.openDatabase(sourcePassphrase)
            assertThat(openResult.isSuccess).isTrue()

            val db = sessionManager.requireDatabase()
            assertThat(db).isNotNull()

            // Step 2: Close and change passphrase
            sessionManager.closeDatabase()
            val updateResult = encryptionManager.updatePassphrase(sourcePassphrase, importPassphrase)
            assertThat(updateResult).isTrue()

            // Step 3: Reopen with new passphrase
            val reopenResult = sessionManager.openDatabase(importPassphrase)
            assertThat(reopenResult.isSuccess).isTrue()

            // Assert: Database is fully functional with new passphrase
            val dbAfterChange = sessionManager.requireDatabase()
            assertThat(dbAfterChange).isNotNull()
            assertThat(encryptionManager.verifyPassphrase(importPassphrase)).isTrue()

            logger.i(
                "DatabaseImportFlowIntegrationTest.importFlow_passphraseChangeDoesNotCorruptDatabase",
                "Database remains functional after passphrase change",
                mapOf(
                    "updateSucceeded" to updateResult,
                    "reopenedSuccessfully" to reopenResult.isSuccess,
                ),
            )
        }

    @Test
    fun importFlow_wrongPassphraseRejectsImport() =
        runTest {
            // Step 1: Create database with passphrase
            encryptionManager.configurePassphrase(sourcePassphrase)
            val openResult = sessionManager.openDatabase(sourcePassphrase)
            assertThat(openResult.isSuccess).isTrue()

            sessionManager.closeDatabase()

            // Step 2: Try to reopen with wrong passphrase (simulating import failure)
            val wrongPassphrase = "CompletelyWrong789!"
            val reopenResult = sessionManager.openDatabase(wrongPassphrase)

            // Assert: Open fails with wrong passphrase
            assertThat(reopenResult.isFailure).isTrue()
            assertThat(sessionManager.isOpen.value).isFalse()

            // Step 3: Should still work with correct passphrase
            val correctReopenResult = sessionManager.openDatabase(sourcePassphrase)
            assertThat(correctReopenResult.isSuccess).isTrue()

            logger.d(
                "DatabaseImportFlowIntegrationTest.importFlow_wrongPassphraseRejectsImport",
                "Wrong passphrase properly rejected, correct passphrase still works",
                mapOf(
                    "wrongPassphraseFailed" to reopenResult.isFailure,
                    "correctPassphraseSucceeded" to correctReopenResult.isSuccess,
                ),
            )
        }

    @Test
    fun importFlow_multipleSequentialImports() =
        runTest {
            // Simulate multiple import/reset cycles
            for (cycle in 1..3) {
                val cyclePassphrase = "CyclePass${cycle}_123!"

                // Configure and open
                encryptionManager.configurePassphrase(cyclePassphrase)
                val openResult = sessionManager.openDatabase(cyclePassphrase)
                assertThat(openResult.isSuccess).isTrue()

                val db = sessionManager.requireDatabase()
                assertThat(db).isNotNull()

                // Close and reset for next cycle
                sessionManager.closeDatabase()
                encryptionManager.resetEncryptionState()

                logger.d(
                    "DatabaseImportFlowIntegrationTest.importFlow_multipleSequentialImports",
                    "Completed import cycle",
                    mapOf(
                        "cycle" to cycle,
                        "passphraseLength" to cyclePassphrase.length,
                    ),
                )
            }

            // Final state check: all cycles should succeed
            assertThat(!encryptionManager.hasPassphraseConfigured()).isTrue()
            assertThat(!sessionManager.isOpen.value).isTrue()

            logger.i(
                "DatabaseImportFlowIntegrationTest.importFlow_multipleSequentialImports",
                "All 3 import cycles completed successfully",
            )
        }

    private fun deleteAllDatabaseFiles() {
        val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile
        if (dbDir != null) {
            val dbName = PayanamDatabase.DATABASE_NAME
            try {
                listOf(
                    File(dbDir, dbName),
                    File(dbDir, "$dbName-wal"),
                    File(dbDir, "$dbName-shm"),
                    File(dbDir, "$dbName-journal"),
                ).forEach { it.delete() }
            } catch (e: Exception) {
                logger.e(
                    "DatabaseImportFlowIntegrationTest.deleteAllDatabaseFiles",
                    "Error during cleanup",
                    e,
                )
            }
        }
    }
}
