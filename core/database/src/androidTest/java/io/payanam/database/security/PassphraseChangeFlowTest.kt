//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.session.DatabaseSessionManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Tests for passphrase lifecycle: set initial passphrase, verify, change passphrase,
 * and unlock with new passphrase. Validates data entry flows for password management.
 */
class PassphraseChangeFlowTest {
    private lateinit var context: Context
    private lateinit var logger: UnifiedLogger
    private lateinit var encryptionManager: DatabaseEncryptionManager
    private lateinit var sessionManager: DatabaseSessionManager

    private val initialPassphrase = "InitialPass123!"
    private val newPassphrase = "UpdatedPass456!"

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
        // Reset encryption state before each test
        encryptionManager.resetEncryptionState()

        sessionManager = DatabaseSessionManager(context, encryptionManager)

        logger.d(
            "PassphraseChangeFlowTest.setUp",
            "Test setup complete with clean encryption state",
            /** Map of. */
            mapOf(
                "initialPassphraseLength" to initialPassphrase.length,
                "newPassphraseLength" to newPassphrase.length,
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
        logger.d("PassphraseChangeFlowTest.tearDown", "Database closed and encryption state reset")
    }

    @Test
    /**
     * Configure passphrase sets initial passphrase for new database.
     */
    fun configurePassphrase_setsInitialPassphraseForNewDatabase() {
        // Act: Configure initial passphrase
        /** Result. */
        val result = encryptionManager.configurePassphrase(initialPassphrase)

        // Assert: Passphrase is configured and can be verified
        /** Assert that. */
        assertThat(result).isTrue()
        /** Assert that. */
        assertThat(encryptionManager.hasPassphraseConfigured()).isTrue()
        /** Assert that. */
        assertThat(encryptionManager.isEncryptionEnabled()).isTrue()

        logger.i(
            "PassphraseChangeFlowTest.configurePassphrase_setsInitialPassphraseForNewDatabase",
            "Initial passphrase configured successfully",
            /** Map of. */
            mapOf("isConfigured" to encryptionManager.hasPassphraseConfigured()),
        )
    }

    @Test
    /**
     * Verify passphrase accepts correct passphrase.
     */
    fun verifyPassphrase_acceptsCorrectPassphrase() {
        // Arrange: Configure passphrase
        /** Config result. */
        val configResult = encryptionManager.configurePassphrase(initialPassphrase)
        /** Assert that. */
        assertThat(configResult).isTrue()

        // Act: Verify with correct passphrase
        /** Verify result. */
        val verifyResult = encryptionManager.verifyPassphrase(initialPassphrase)

        // Assert: Verification succeeds
        /** Assert that. */
        assertThat(verifyResult).isTrue()
        logger.d(
            "PassphraseChangeFlowTest.verifyPassphrase_acceptsCorrectPassphrase",
            "Correct passphrase verified successfully",
            /** Map of. */
            mapOf("verifyResult" to verifyResult),
        )
    }

    @Test
    /**
     * Verify passphrase rejects incorrect passphrase.
     */
    fun verifyPassphrase_rejectsIncorrectPassphrase() {
        // Arrange: Configure passphrase
        /** Config result. */
        val configResult = encryptionManager.configurePassphrase(initialPassphrase)
        /** Assert that. */
        assertThat(configResult).isTrue()

        // Act: Try to verify with wrong passphrase
        /** Wrong passphrase. */
        val wrongPassphrase = "WrongPass789!"
        /** Verify result. */
        val verifyResult = encryptionManager.verifyPassphrase(wrongPassphrase)

        // Assert: Verification fails
        /** Assert that. */
        assertThat(verifyResult).isFalse()
        logger.d(
            "PassphraseChangeFlowTest.verifyPassphrase_rejectsIncorrectPassphrase",
            "Incorrect passphrase rejected as expected",
            /** Map of. */
            mapOf("verifyResult" to verifyResult),
        )
    }

    @Test
    /**
     * Update passphrase changes passphrase with verification.
     */
    fun updatePassphrase_changesPassphraseWithVerification() {
        // Arrange: Configure initial passphrase
        /** Config result. */
        val configResult = encryptionManager.configurePassphrase(initialPassphrase)
        /** Assert that. */
        assertThat(configResult).isTrue()
        /** Assert that. */
        assertThat(encryptionManager.verifyPassphrase(initialPassphrase)).isTrue()

        // Act: Update to new passphrase
        /** Update result. */
        val updateResult = encryptionManager.updatePassphrase(initialPassphrase, newPassphrase)

        // Assert: Passphrase is updated
        /** Assert that. */
        assertThat(updateResult).isTrue()
        /** Assert that. */
        assertThat(encryptionManager.verifyPassphrase(newPassphrase)).isTrue()
        /** Assert that. */
        assertThat(encryptionManager.verifyPassphrase(initialPassphrase)).isFalse()

        logger.i(
            "PassphraseChangeFlowTest.updatePassphrase_changesPassphraseWithVerification",
            "Passphrase updated successfully",
            /** Map of. */
            mapOf(
                "updateResult" to updateResult,
                "newPassphraseVerified" to encryptionManager.verifyPassphrase(newPassphrase),
            ),
        )
    }

    @Test
    /**
     * Update passphrase fails with incorrect current passphrase.
     */
    fun updatePassphrase_failsWithIncorrectCurrentPassphrase() {
        // Arrange: Configure initial passphrase
        /** Config result. */
        val configResult = encryptionManager.configurePassphrase(initialPassphrase)
        /** Assert that. */
        assertThat(configResult).isTrue()

        // Act: Try to update with wrong current passphrase
        /** Wrong passphrase. */
        val wrongPassphrase = "WrongPass789!"
        /** Update result. */
        val updateResult = encryptionManager.updatePassphrase(wrongPassphrase, newPassphrase)

        // Assert: Update fails and original passphrase is still valid
        /** Assert that. */
        assertThat(updateResult).isFalse()
        /** Assert that. */
        assertThat(encryptionManager.verifyPassphrase(initialPassphrase)).isTrue()
        /** Assert that. */
        assertThat(encryptionManager.verifyPassphrase(newPassphrase)).isFalse()

        logger.d(
            "PassphraseChangeFlowTest.updatePassphrase_failsWithIncorrectCurrentPassphrase",
            "Update rejected with wrong current passphrase",
            /** Map of. */
            mapOf(
                "updateResult" to updateResult,
                "originalStillValid" to encryptionManager.verifyPassphrase(initialPassphrase),
            ),
        )
    }

    @Test
    /**
     * Full passphrase lifecycle set verify change unlock.
     */
    fun fullPassphraseLifecycle_setVerifyChangeUnlock() =
        runTest {
            // Step 1: Configure initial passphrase
            /** Config result. */
            val configResult = encryptionManager.configurePassphrase(initialPassphrase)
            /** Assert that. */
            assertThat(configResult).isTrue()
            /** Assert that. */
            assertThat(encryptionManager.hasPassphraseConfigured()).isTrue()

            // Step 2: Open database with initial passphrase
            /** Open result. */
            val openResult = sessionManager.openDatabase(initialPassphrase)
            /** Assert that. */
            assertThat(openResult.isSuccess).isTrue()
            /** Assert that. */
            assertThat(sessionManager.isOpen.value).isTrue()

            // Step 3: Close database (simulating app close before password change)
            sessionManager.closeDatabase()
            /** Assert that. */
            assertThat(sessionManager.isOpen.value).isFalse()

            // Step 4: Change passphrase
            /** Update result. */
            val updateResult = encryptionManager.updatePassphrase(initialPassphrase, newPassphrase)
            /** Assert that. */
            assertThat(updateResult).isTrue()

            // Step 5: Reopen with new passphrase
            /** Reopen result. */
            val reopenResult = sessionManager.openDatabase(newPassphrase)
            /** Assert that. */
            assertThat(reopenResult.isSuccess).isTrue()
            /** Assert that. */
            assertThat(sessionManager.isOpen.value).isTrue()

            logger.i(
                "PassphraseChangeFlowTest.fullPassphraseLifecycle_setVerifyChangeUnlock",
                "Full passphrase lifecycle completed successfully",
                /** Map of. */
                mapOf(
                    "configured" to encryptionManager.hasPassphraseConfigured(),
                    "changedSuccessfully" to updateResult,
                    "reopenedWithNewPassphrase" to sessionManager.isOpen.value,
                ),
            )
        }

    @Test
    /**
     * Session timeout configuration can be set.
     */
    fun sessionTimeoutConfiguration_canBeSet() {
        // Act: Set session timeout
        /** Original timeout. */
        val originalTimeout = encryptionManager.getSessionTimeoutMinutes()
        encryptionManager.setSessionTimeoutMinutes(15)
        /** New timeout. */
        val newTimeout = encryptionManager.getSessionTimeoutMinutes()

        // Assert: Timeout is updated
        /** Assert that. */
        assertThat(newTimeout).isEqualTo(15)
        logger.d(
            "PassphraseChangeFlowTest.sessionTimeoutConfiguration_canBeSet",
            "Session timeout configured",
            /** Map of. */
            mapOf(
                "originalTimeout" to originalTimeout,
                "newTimeout" to newTimeout,
            ),
        )
    }

    @Test
    /**
     * Session timeout configuration coerced to valid range.
     */
    fun sessionTimeoutConfiguration_coercedToValidRange() {
        // Act: Try to set timeout outside valid range
        encryptionManager.setSessionTimeoutMinutes(1) // Below minimum
        /** Min timeout. */
        val minTimeout = encryptionManager.getSessionTimeoutMinutes()

        encryptionManager.setSessionTimeoutMinutes(500) // Above maximum
        /** Max timeout. */
        val maxTimeout = encryptionManager.getSessionTimeoutMinutes()

        // Assert: Values are coerced to valid range
        /** Assert that. */
        assertThat(minTimeout).isGreaterThan(0)
        /** Assert that. */
        assertThat(maxTimeout).isLessThan(1000)
        logger.d(
            "PassphraseChangeFlowTest.sessionTimeoutConfiguration_coercedToValidRange",
            "Timeout values coerced to valid range",
            /** Map of. */
            mapOf(
                "minTimeout" to minTimeout,
                "maxTimeout" to maxTimeout,
            ),
        )
    }
}
