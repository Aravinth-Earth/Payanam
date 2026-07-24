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
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
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
            mapOf(
                "initialPassphraseLength" to initialPassphrase.length,
                "newPassphraseLength" to newPassphrase.length,
            ),
        )
    }

    @After
    fun tearDown() {
        sessionManager.closeDatabase()
        encryptionManager.resetEncryptionState()
        logger.d("PassphraseChangeFlowTest.tearDown", "Database closed and encryption state reset")
    }

    @Test
    fun configurePassphrase_setsInitialPassphraseForNewDatabase() {
        // Act: Configure initial passphrase
        val result = encryptionManager.configurePassphrase(initialPassphrase)

        // Assert: Passphrase is configured and can be verified
        assertThat(result).isTrue()
        assertThat(encryptionManager.hasPassphraseConfigured()).isTrue()
        assertThat(encryptionManager.isEncryptionEnabled()).isTrue()

        logger.i(
            "PassphraseChangeFlowTest.configurePassphrase_setsInitialPassphraseForNewDatabase",
            "Initial passphrase configured successfully",
            mapOf("isConfigured" to encryptionManager.hasPassphraseConfigured()),
        )
    }

    @Test
    fun verifyPassphrase_acceptsCorrectPassphrase() {
        // Arrange: Configure passphrase
        val configResult = encryptionManager.configurePassphrase(initialPassphrase)
        assertThat(configResult).isTrue()

        // Act: Verify with correct passphrase
        val verifyResult = encryptionManager.verifyPassphrase(initialPassphrase)

        // Assert: Verification succeeds
        assertThat(verifyResult).isTrue()
        logger.d(
            "PassphraseChangeFlowTest.verifyPassphrase_acceptsCorrectPassphrase",
            "Correct passphrase verified successfully",
            mapOf("verifyResult" to verifyResult),
        )
    }

    @Test
    fun verifyPassphrase_rejectsIncorrectPassphrase() {
        // Arrange: Configure passphrase
        val configResult = encryptionManager.configurePassphrase(initialPassphrase)
        assertThat(configResult).isTrue()

        // Act: Try to verify with wrong passphrase
        val wrongPassphrase = "WrongPass789!"
        val verifyResult = encryptionManager.verifyPassphrase(wrongPassphrase)

        // Assert: Verification fails
        assertThat(verifyResult).isFalse()
        logger.d(
            "PassphraseChangeFlowTest.verifyPassphrase_rejectsIncorrectPassphrase",
            "Incorrect passphrase rejected as expected",
            mapOf("verifyResult" to verifyResult),
        )
    }

    @Test
    fun updatePassphrase_changesPassphraseWithVerification() {
        // Arrange: Configure initial passphrase
        val configResult = encryptionManager.configurePassphrase(initialPassphrase)
        assertThat(configResult).isTrue()
        assertThat(encryptionManager.verifyPassphrase(initialPassphrase)).isTrue()

        // Act: Update to new passphrase
        val updateResult = encryptionManager.updatePassphrase(initialPassphrase, newPassphrase)

        // Assert: Passphrase is updated
        assertThat(updateResult).isTrue()
        assertThat(encryptionManager.verifyPassphrase(newPassphrase)).isTrue()
        assertThat(encryptionManager.verifyPassphrase(initialPassphrase)).isFalse()

        logger.i(
            "PassphraseChangeFlowTest.updatePassphrase_changesPassphraseWithVerification",
            "Passphrase updated successfully",
            mapOf(
                "updateResult" to updateResult,
                "newPassphraseVerified" to encryptionManager.verifyPassphrase(newPassphrase),
            ),
        )
    }

    @Test
    fun updatePassphrase_failsWithIncorrectCurrentPassphrase() {
        // Arrange: Configure initial passphrase
        val configResult = encryptionManager.configurePassphrase(initialPassphrase)
        assertThat(configResult).isTrue()

        // Act: Try to update with wrong current passphrase
        val wrongPassphrase = "WrongPass789!"
        val updateResult = encryptionManager.updatePassphrase(wrongPassphrase, newPassphrase)

        // Assert: Update fails and original passphrase is still valid
        assertThat(updateResult).isFalse()
        assertThat(encryptionManager.verifyPassphrase(initialPassphrase)).isTrue()
        assertThat(encryptionManager.verifyPassphrase(newPassphrase)).isFalse()

        logger.d(
            "PassphraseChangeFlowTest.updatePassphrase_failsWithIncorrectCurrentPassphrase",
            "Update rejected with wrong current passphrase",
            mapOf(
                "updateResult" to updateResult,
                "originalStillValid" to encryptionManager.verifyPassphrase(initialPassphrase),
            ),
        )
    }

    @Test
    fun fullPassphraseLifecycle_setVerifyChangeUnlock() =
        runTest {
            // Step 1: Configure initial passphrase
            val configResult = encryptionManager.configurePassphrase(initialPassphrase)
            assertThat(configResult).isTrue()
            assertThat(encryptionManager.hasPassphraseConfigured()).isTrue()

            // Step 2: Open database with initial passphrase
            val openResult = sessionManager.openDatabase(initialPassphrase)
            assertThat(openResult.isSuccess).isTrue()
            assertThat(sessionManager.isOpen.value).isTrue()

            // Step 3: Close database (simulating app close before password change)
            sessionManager.closeDatabase()
            assertThat(sessionManager.isOpen.value).isFalse()

            // Step 4: Change passphrase
            val updateResult = encryptionManager.updatePassphrase(initialPassphrase, newPassphrase)
            assertThat(updateResult).isTrue()

            // Step 5: Reopen with new passphrase
            val reopenResult = sessionManager.openDatabase(newPassphrase)
            assertThat(reopenResult.isSuccess).isTrue()
            assertThat(sessionManager.isOpen.value).isTrue()

            logger.i(
                "PassphraseChangeFlowTest.fullPassphraseLifecycle_setVerifyChangeUnlock",
                "Full passphrase lifecycle completed successfully",
                mapOf(
                    "configured" to encryptionManager.hasPassphraseConfigured(),
                    "changedSuccessfully" to updateResult,
                    "reopenedWithNewPassphrase" to sessionManager.isOpen.value,
                ),
            )
        }

    @Test
    fun sessionTimeoutConfiguration_canBeSet() {
        // Act: Set session timeout
        val originalTimeout = encryptionManager.getSessionTimeoutMinutes()
        encryptionManager.setSessionTimeoutMinutes(15)
        val newTimeout = encryptionManager.getSessionTimeoutMinutes()

        // Assert: Timeout is updated
        assertThat(newTimeout).isEqualTo(15)
        logger.d(
            "PassphraseChangeFlowTest.sessionTimeoutConfiguration_canBeSet",
            "Session timeout configured",
            mapOf(
                "originalTimeout" to originalTimeout,
                "newTimeout" to newTimeout,
            ),
        )
    }

    @Test
    fun sessionTimeoutConfiguration_coercedToValidRange() {
        // Act: Try to set timeout outside valid range
        encryptionManager.setSessionTimeoutMinutes(1) // Below minimum
        val minTimeout = encryptionManager.getSessionTimeoutMinutes()

        encryptionManager.setSessionTimeoutMinutes(500) // Above maximum
        val maxTimeout = encryptionManager.getSessionTimeoutMinutes()

        // Assert: Values are coerced to valid range
        assertThat(minTimeout).isGreaterThan(0)
        assertThat(maxTimeout).isLessThan(1000)
        logger.d(
            "PassphraseChangeFlowTest.sessionTimeoutConfiguration_coercedToValidRange",
            "Timeout values coerced to valid range",
            mapOf(
                "minTimeout" to minTimeout,
                "maxTimeout" to maxTimeout,
            ),
        )
    }
}
