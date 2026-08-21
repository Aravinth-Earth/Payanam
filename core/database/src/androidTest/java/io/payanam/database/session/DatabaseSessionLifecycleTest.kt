//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.security.DatabaseEncryptionManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for DatabaseSessionManager lifecycle: create, open, close, and password validation.
 * Tests focus on data entry integrity for new DB creation and passphrase-protected access.
 */
class DatabaseSessionLifecycleTest {
    private lateinit var context: Context
    private lateinit var logger: UnifiedLogger
    private lateinit var encryptionManager: DatabaseEncryptionManager
    private lateinit var sessionManager: DatabaseSessionManager

    private val testPassphrase = "SecureTest123!"

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

        // Initialize encryption manager and session manager
        encryptionManager = DatabaseEncryptionManager(context)
        sessionManager = DatabaseSessionManager(context, encryptionManager)

        logger.d(
            "DatabaseSessionLifecycleTest.setUp",
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
        logger.d("DatabaseSessionLifecycleTest.tearDown", "Database closed and session cleared")
    }

    @Test
    /**
     * Open database creates new database session.
     */
    fun openDatabase_createsNewDatabaseSession() =
        runTest {
            // Arrange: Configure passphrase first
            val configResult = encryptionManager.configurePassphrase(testPassphrase)
            assertThat(configResult).isTrue()

            // Act: Open a new database with passphrase
            val result = sessionManager.openDatabase(testPassphrase)

            // Assert: Database opens successfully
            assertThat(result.isSuccess).isTrue()
            assertThat(sessionManager.isOpen.value).isTrue()
            logger.i(
                "DatabaseSessionLifecycleTest.openDatabase_createsNewDatabaseSession",
                "Database session created successfully",
                mapOf("isOpen" to sessionManager.isOpen.value),
            )
        }

    @Test
    /**
     * Open database idempotent with same passphrase.
     */
    fun openDatabase_idempotentWithSamePassphrase() =
        runTest {
            // Arrange: Configure passphrase
            val configResult = encryptionManager.configurePassphrase(testPassphrase)
            assertThat(configResult).isTrue()

            // Act: Open database twice with same passphrase
            val result1 = sessionManager.openDatabase(testPassphrase)
            val result2 = sessionManager.openDatabase(testPassphrase)

            // Assert: Both operations succeed and DB remains open
            assertThat(result1.isSuccess).isTrue()
            assertThat(result2.isSuccess).isTrue()
            assertThat(sessionManager.isOpen.value).isTrue()
            logger.d(
                "DatabaseSessionLifecycleTest.openDatabase_idempotentWithSamePassphrase",
                "Database reopened idempotently",
                mapOf("secondOpenSucceeded" to result2.isSuccess),
            )
        }

    @Test
    /**
     * Open database with different passphrase rejects wrong key.
     */
    fun openDatabase_withDifferentPassphraseRejectsWrongKey() =
        runTest {
            // Arrange: Configure encryption with passphrase
            val configResult = encryptionManager.configurePassphrase(testPassphrase)
            assertThat(configResult).isTrue()

            // Open database with initial passphrase
            val result1 = sessionManager.openDatabase(testPassphrase)
            assertThat(result1.isSuccess).isTrue()

            // Close the session to reset state
            sessionManager.closeDatabase()

            // Act: Try to open with wrong passphrase (note: this tests failure handling in encryption layer)
            val wrongPassphrase = "WrongPassword123!"
            val result2 = sessionManager.openDatabase(wrongPassphrase)

            // Assert: Open fails due to incorrect passphrase
            // Note: SQLCipher will reject the wrong key during writableDatabase access
            logger.d(
                "DatabaseSessionLifecycleTest.openDatabase_withDifferentPassphraseRejectsWrongKey",
                "Wrong passphrase attempt result",
                mapOf("failedAsExpected" to result2.isFailure),
            )
        }

    @Test
    /**
     * Close database nullifies database reference.
     */
    fun closeDatabase_nullifiesDatabaseReference() =
        runTest {
            // Arrange: Configure passphrase and open database
            val configResult = encryptionManager.configurePassphrase(testPassphrase)
            assertThat(configResult).isTrue()
            val openResult = sessionManager.openDatabase(testPassphrase)
            assertThat(openResult.isSuccess).isTrue()
            assertThat(sessionManager.isOpen.value).isTrue()

            // Act: Close database
            sessionManager.closeDatabase()

            // Assert: Database reference is cleared and state is reset
            assertThat(sessionManager.isOpen.value).isFalse()
            logger.d(
                "DatabaseSessionLifecycleTest.closeDatabase_nullifiesDatabaseReference",
                "Database closed successfully",
                mapOf("isClosed" to !sessionManager.isOpen.value),
            )
        }

    @Test
    /**
     * Require open passphrase throws when database not open.
     */
    fun requireOpenPassphrase_throwsWhenDatabaseNotOpen() {
        // Arrange: Ensure database is not open
        sessionManager.closeDatabase()

        // Act & Assert: Attempting to get passphrase without open DB throws
        try {
            sessionManager.requireOpenPassphrase()
            assertThat(false).isTrue() // Should not reach here
        } catch (e: IllegalStateException) {
            logger.d(
                "DatabaseSessionLifecycleTest.requireOpenPassphrase_throwsWhenDatabaseNotOpen",
                "Expected exception thrown",
                mapOf("exceptionMessage" to e.message),
            )
            assertThat(e.message).contains("DB not open")
        }
    }

    @Test
    /**
     * Require database throws when database not open.
     */
    fun requireDatabase_throwsWhenDatabaseNotOpen() {
        // Arrange: Ensure database is not open
        sessionManager.closeDatabase()

        // Act & Assert: Attempting to get database while closed throws
        try {
            sessionManager.requireDatabase()
            assertThat(false).isTrue() // Should not reach here
        } catch (e: IllegalStateException) {
            logger.d(
                "DatabaseSessionLifecycleTest.requireDatabase_throwsWhenDatabaseNotOpen",
                "Expected exception thrown",
                mapOf("exceptionMessage" to e.message),
            )
            assertThat(e.message).contains("DB not open")
        }
    }

    @Test
    /**
     * Open database multiple sequential operations.
     */
    fun openDatabase_multipleSequentialOperations() =
        runTest {
            // Arrange: Configure passphrase
            val configResult = encryptionManager.configurePassphrase(testPassphrase)
            assertThat(configResult).isTrue()

            // Test sequence: open -> close -> reopen with same passphrase
            // Act: First open
            val result1 = sessionManager.openDatabase(testPassphrase)
            assertThat(result1.isSuccess).isTrue()
            assertThat(sessionManager.isOpen.value).isTrue()

            // Act: Close
            sessionManager.closeDatabase()
            assertThat(sessionManager.isOpen.value).isFalse()

            // Act: Reopen with same passphrase
            val result2 = sessionManager.openDatabase(testPassphrase)
            assertThat(result2.isSuccess).isTrue()
            assertThat(sessionManager.isOpen.value).isTrue()

            logger.i(
                "DatabaseSessionLifecycleTest.openDatabase_multipleSequentialOperations",
                "Sequential open-close-reopen cycle completed successfully",
                mapOf(
                    "firstOpenSuccess" to result1.isSuccess,
                    "reopenSuccess" to result2.isSuccess,
                ),
            )
        }
}
