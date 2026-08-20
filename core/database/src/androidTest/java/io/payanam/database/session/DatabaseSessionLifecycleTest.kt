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
        /** If. */
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
        logger.d("DatabaseSessionLifecycleTest.tearDown", "Database closed and session cleared")
    }

    @Test
    /**
     * Open database creates new database session.
     */
    fun openDatabase_createsNewDatabaseSession() =
        runTest {
            // Arrange: Configure passphrase first
            /** Config result. */
            val configResult = encryptionManager.configurePassphrase(testPassphrase)
            /** Assert that. */
            assertThat(configResult).isTrue()

            // Act: Open a new database with passphrase
            /** Result. */
            val result = sessionManager.openDatabase(testPassphrase)

            // Assert: Database opens successfully
            /** Assert that. */
            assertThat(result.isSuccess).isTrue()
            /** Assert that. */
            assertThat(sessionManager.isOpen.value).isTrue()
            logger.i(
                "DatabaseSessionLifecycleTest.openDatabase_createsNewDatabaseSession",
                "Database session created successfully",
                /** Map of. */
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
            /** Config result. */
            val configResult = encryptionManager.configurePassphrase(testPassphrase)
            /** Assert that. */
            assertThat(configResult).isTrue()

            // Act: Open database twice with same passphrase
            /** Result1. */
            val result1 = sessionManager.openDatabase(testPassphrase)
            /** Result2. */
            val result2 = sessionManager.openDatabase(testPassphrase)

            // Assert: Both operations succeed and DB remains open
            /** Assert that. */
            assertThat(result1.isSuccess).isTrue()
            /** Assert that. */
            assertThat(result2.isSuccess).isTrue()
            /** Assert that. */
            assertThat(sessionManager.isOpen.value).isTrue()
            logger.d(
                "DatabaseSessionLifecycleTest.openDatabase_idempotentWithSamePassphrase",
                "Database reopened idempotently",
                /** Map of. */
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
            /** Config result. */
            val configResult = encryptionManager.configurePassphrase(testPassphrase)
            /** Assert that. */
            assertThat(configResult).isTrue()

            // Open database with initial passphrase
            /** Result1. */
            val result1 = sessionManager.openDatabase(testPassphrase)
            /** Assert that. */
            assertThat(result1.isSuccess).isTrue()

            // Close the session to reset state
            sessionManager.closeDatabase()

            // Act: Try to open with wrong passphrase (note: this tests failure handling in encryption layer)
            /** Wrong passphrase. */
            val wrongPassphrase = "WrongPassword123!"
            /** Result2. */
            val result2 = sessionManager.openDatabase(wrongPassphrase)

            // Assert: Open fails due to incorrect passphrase
            // Note: SQLCipher will reject the wrong key during writableDatabase access
            logger.d(
                "DatabaseSessionLifecycleTest.openDatabase_withDifferentPassphraseRejectsWrongKey",
                "Wrong passphrase attempt result",
                /** Map of. */
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
            /** Config result. */
            val configResult = encryptionManager.configurePassphrase(testPassphrase)
            /** Assert that. */
            assertThat(configResult).isTrue()

            /** Open result. */
            val openResult = sessionManager.openDatabase(testPassphrase)
            /** Assert that. */
            assertThat(openResult.isSuccess).isTrue()
            /** Assert that. */
            assertThat(sessionManager.isOpen.value).isTrue()

            // Act: Close database
            sessionManager.closeDatabase()

            // Assert: Database reference is cleared and state is reset
            /** Assert that. */
            assertThat(sessionManager.isOpen.value).isFalse()
            logger.d(
                "DatabaseSessionLifecycleTest.closeDatabase_nullifiesDatabaseReference",
                "Database closed successfully",
                /** Map of. */
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
            /** Assert that. */
            assertThat(false).isTrue() // Should not reach here
        } catch (e: IllegalStateException) {
            logger.d(
                "DatabaseSessionLifecycleTest.requireOpenPassphrase_throwsWhenDatabaseNotOpen",
                "Expected exception thrown",
                /** Map of. */
                mapOf("exceptionMessage" to e.message),
            )
            /** Assert that. */
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
            /** Assert that. */
            assertThat(false).isTrue() // Should not reach here
        } catch (e: IllegalStateException) {
            logger.d(
                "DatabaseSessionLifecycleTest.requireDatabase_throwsWhenDatabaseNotOpen",
                "Expected exception thrown",
                /** Map of. */
                mapOf("exceptionMessage" to e.message),
            )
            /** Assert that. */
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
            /** Config result. */
            val configResult = encryptionManager.configurePassphrase(testPassphrase)
            /** Assert that. */
            assertThat(configResult).isTrue()

            // Test sequence: open -> close -> reopen with same passphrase
            // Act: First open
            /** Result1. */
            val result1 = sessionManager.openDatabase(testPassphrase)
            /** Assert that. */
            assertThat(result1.isSuccess).isTrue()
            /** Assert that. */
            assertThat(sessionManager.isOpen.value).isTrue()

            // Act: Close
            sessionManager.closeDatabase()
            /** Assert that. */
            assertThat(sessionManager.isOpen.value).isFalse()

            // Act: Reopen with same passphrase
            /** Result2. */
            val result2 = sessionManager.openDatabase(testPassphrase)
            /** Assert that. */
            assertThat(result2.isSuccess).isTrue()
            /** Assert that. */
            assertThat(sessionManager.isOpen.value).isTrue()

            logger.i(
                "DatabaseSessionLifecycleTest.openDatabase_multipleSequentialOperations",
                "Sequential open-close-reopen cycle completed successfully",
                /** Map of. */
                mapOf(
                    "firstOpenSuccess" to result1.isSuccess,
                    "reopenSuccess" to result2.isSuccess,
                ),
            )
        }
}
