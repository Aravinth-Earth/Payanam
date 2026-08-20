//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
/**
 * DatabaseEncryptionManagerSecurityModelTest.
 */
class DatabaseEncryptionManagerSecurityModelTest {
    private lateinit var context: Context
    private lateinit var manager: DatabaseEncryptionManager
    private lateinit var prefs: android.content.SharedPreferences

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
        manager = DatabaseEncryptionManager(context)
        prefs = context.getSharedPreferences("db_security_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    /**
     * Configure passphrase persists verifier only and does not persist recoverable passphrase.
     */
    fun configurePassphrase_persistsVerifierOnly_andDoesNotPersistRecoverablePassphrase() {
        /** Configured. */
        val configured = manager.configurePassphrase("ValidTest#Pass123")

        /** Assert that. */
        assertThat(configured).isTrue()
        /** Assert that. */
        assertThat(manager.hasPassphraseConfigured()).isTrue()
        /** Assert that. */
        assertThat(prefs.getString("db_mode", null)).isEqualTo("encrypted")
        /** Assert that. */
        assertThat(prefs.getString("db_verifier_hash", null)).isNotNull()
        /** Assert that. */
        assertThat(prefs.getString("db_verifier_salt", null)).isNotNull()
        /** Assert that. */
        assertThat(prefs.getString("db_wrapped_passphrase", null)).isNull()
        /** Assert that. */
        assertThat(prefs.getString("db_wrapped_iv", null)).isNull()
        /** Assert that. */
        assertThat(prefs.getString("db_biometric_wrapped_passphrase", null)).isNull()
        /** Assert that. */
        assertThat(prefs.getString("db_biometric_wrapped_iv", null)).isNull()
        /** Assert that. */
        assertThat(manager.isBiometricUnlockEnabled()).isFalse()
    }

    @Test
    /**
     * Set biometric unlock enabled true without biometric wrapped secret is ignored.
     */
    fun setBiometricUnlockEnabled_true_withoutBiometricWrappedSecret_isIgnored() {
        manager.configurePassphrase("ValidTest#Pass123")

        manager.setBiometricUnlockEnabled(true)

        /** Assert that. */
        assertThat(manager.isBiometricUnlockEnabled()).isFalse()
        /** Assert that. */
        assertThat(prefs.getBoolean("biometric_unlock_enabled", false)).isFalse()
    }

    @Test
    /**
     * Disable biometric unlock clears all biometric and legacy wrapped material without passphrase prompt.
     */
    fun disableBiometricUnlock_clearsAllBiometricAndLegacyWrappedMaterial_withoutPassphrasePrompt() {
        /** Prefs. */
        prefs
            .edit()
            .putBoolean("biometric_unlock_enabled", true)
            .putString("db_biometric_wrapped_passphrase", "cipher_blob")
            .putString("db_biometric_wrapped_iv", "iv_blob")
            .putString("db_wrapped_passphrase", "legacy_cipher_blob")
            .putString("db_wrapped_iv", "legacy_iv_blob")
            .commit()

        /** Disabled. */
        val disabled = manager.disableBiometricUnlock()

        /** Assert that. */
        assertThat(disabled).isTrue()
        /** Assert that. */
        assertThat(manager.isBiometricUnlockEnabled()).isFalse()
        /** Assert that. */
        assertThat(prefs.getBoolean("biometric_unlock_enabled", true)).isFalse()
        /** Assert that. */
        assertThat(prefs.getString("db_biometric_wrapped_passphrase", null)).isNull()
        /** Assert that. */
        assertThat(prefs.getString("db_biometric_wrapped_iv", null)).isNull()
        /** Assert that. */
        assertThat(prefs.getString("db_wrapped_passphrase", null)).isNull()
        /** Assert that. */
        assertThat(prefs.getString("db_wrapped_iv", null)).isNull()
    }

    @Test
    /**
     * Update passphrase disables biometric and removes biometric wrapped secret.
     */
    fun updatePassphrase_disablesBiometric_andRemovesBiometricWrappedSecret() {
        manager.configurePassphrase("OldPass#123456")
        /** Prefs. */
        prefs
            .edit()
            .putBoolean("biometric_unlock_enabled", true)
            .putString("db_biometric_wrapped_passphrase", "cipher_blob")
            .putString("db_biometric_wrapped_iv", "iv_blob")
            .commit()

        /** Updated. */
        val updated = manager.updatePassphrase("OldPass#123456", "NewPass#654321")

        /** Assert that. */
        assertThat(updated).isTrue()
        /** Assert that. */
        assertThat(manager.verifyPassphrase("OldPass#123456")).isFalse()
        /** Assert that. */
        assertThat(manager.verifyPassphrase("NewPass#654321")).isTrue()
        /** Assert that. */
        assertThat(manager.isBiometricUnlockEnabled()).isFalse()
        /** Assert that. */
        assertThat(prefs.getString("db_biometric_wrapped_passphrase", null)).isNull()
        /** Assert that. */
        assertThat(prefs.getString("db_biometric_wrapped_iv", null)).isNull()
    }

    @Test
    /**
     * Backup and restore encryption prefs restores verifier and biometric state.
     */
    fun backupAndRestoreEncryptionPrefs_restoresVerifierAndBiometricState() {
        /** Prefs. */
        prefs
            .edit()
            .putString("db_mode", "encrypted")
            .putString("db_verifier_hash", "hash_blob")
            .putString("db_verifier_salt", "salt_blob")
            .putBoolean("biometric_unlock_enabled", true)
            .putString("db_biometric_wrapped_passphrase", "cipher_blob")
            .putString("db_biometric_wrapped_iv", "iv_blob")
            .commit()

        /** Backed up. */
        val backedUp = manager.backupEncryptionPrefs()
        /** Assert that. */
        assertThat(backedUp).isTrue()

        /** Prefs. */
        prefs
            .edit()
            .remove("db_mode")
            .remove("db_verifier_hash")
            .remove("db_verifier_salt")
            .putBoolean("biometric_unlock_enabled", false)
            .remove("db_biometric_wrapped_passphrase")
            .remove("db_biometric_wrapped_iv")
            .commit()

        /** Restored. */
        val restored = manager.restoreEncryptionPrefs()
        /** Assert that. */
        assertThat(restored).isTrue()
        /** Assert that. */
        assertThat(prefs.getString("db_mode", null)).isEqualTo("encrypted")
        /** Assert that. */
        assertThat(prefs.getString("db_verifier_hash", null)).isEqualTo("hash_blob")
        /** Assert that. */
        assertThat(prefs.getString("db_verifier_salt", null)).isEqualTo("salt_blob")
        /** Assert that. */
        assertThat(prefs.getBoolean("biometric_unlock_enabled", false)).isTrue()
        /** Assert that. */
        assertThat(prefs.getString("db_biometric_wrapped_passphrase", null)).isEqualTo("cipher_blob")
        /** Assert that. */
        assertThat(prefs.getString("db_biometric_wrapped_iv", null)).isEqualTo("iv_blob")
    }
}
