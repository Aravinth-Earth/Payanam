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
 * Provides the database encryption manager security model test.
 */
class DatabaseEncryptionManagerSecurityModelTest {
    private lateinit var context: Context
    private lateinit var manager: DatabaseEncryptionManager
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var armedPreferences: FailingCommitPreferences

    @Before
    /**
     * Updates the set up.
     */
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        prefs = context.getSharedPreferences("db_security_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        // The manager resolves its store from `context.applicationContext`, so the wrapper has to be
        // the application context it sees — `armedPreferences` then counts that store's commits.
        val store = FailingCommitContext(context, "db_security_prefs")
        armedPreferences = store.preferences
        manager = DatabaseEncryptionManager(store)
    }

    @Test
    /**
     * Configure passphrase persists verifier only and does not persist recoverable passphrase.
     */
    fun configurePassphrase_persistsVerifierOnly_andDoesNotPersistRecoverablePassphrase() {
        val configured = manager.configurePassphrase("ValidTest#Pass123")
        assertThat(configured).isTrue()
        assertThat(manager.hasPassphraseConfigured()).isTrue()
        assertThat(prefs.getString("db_verifier_hash", null)).isNotNull()
        assertThat(prefs.getString("db_verifier_salt", null)).isNotNull()
        assertThat(prefs.getString("db_wrapped_passphrase", null)).isNull()
        assertThat(prefs.getString("db_wrapped_iv", null)).isNull()
        assertThat(prefs.getString("db_biometric_wrapped_passphrase", null)).isNull()
        assertThat(prefs.getString("db_biometric_wrapped_iv", null)).isNull()
        assertThat(manager.isBiometricUnlockEnabled()).isFalse()
    }

    @Test
    /**
     * Set biometric unlock enabled true without biometric wrapped secret is ignored.
     */
    fun setBiometricUnlockEnabled_true_withoutBiometricWrappedSecret_isIgnored() {
        manager.configurePassphrase("ValidTest#Pass123")

        manager.setBiometricUnlockEnabled(true)
        assertThat(manager.isBiometricUnlockEnabled()).isFalse()
        assertThat(prefs.getBoolean("biometric_unlock_enabled", false)).isFalse()
    }

    @Test
    /**
     * Disable biometric unlock clears all biometric and legacy wrapped material without passphrase prompt.
     */
    fun disableBiometricUnlock_clearsAllBiometricAndLegacyWrappedMaterial_withoutPassphrasePrompt() {
        prefs
            .edit()
            .putBoolean("biometric_unlock_enabled", true)
            .putString("db_biometric_wrapped_passphrase", "cipher_blob")
            .putString("db_biometric_wrapped_iv", "iv_blob")
            .putString("db_wrapped_passphrase", "legacy_cipher_blob")
            .putString("db_wrapped_iv", "legacy_iv_blob")
            .commit()
        val disabled = manager.disableBiometricUnlock()
        assertThat(disabled).isTrue()
        assertThat(manager.isBiometricUnlockEnabled()).isFalse()
        assertThat(prefs.getBoolean("biometric_unlock_enabled", true)).isFalse()
        assertThat(prefs.getString("db_biometric_wrapped_passphrase", null)).isNull()
        assertThat(prefs.getString("db_biometric_wrapped_iv", null)).isNull()
        assertThat(prefs.getString("db_wrapped_passphrase", null)).isNull()
        assertThat(prefs.getString("db_wrapped_iv", null)).isNull()
    }

    @Test
    /**
     * Update passphrase disables biometric and removes biometric wrapped secret.
     */
    fun updatePassphrase_disablesBiometric_andRemovesBiometricWrappedSecret() {
        manager.configurePassphrase("OldPass#123456")
        prefs
            .edit()
            .putBoolean("biometric_unlock_enabled", true)
            .putString("db_biometric_wrapped_passphrase", "cipher_blob")
            .putString("db_biometric_wrapped_iv", "iv_blob")
            .commit()
        val updated = manager.updatePassphrase("OldPass#123456", "NewPass#654321")
        assertThat(updated).isTrue()
        assertThat(manager.verifyPassphrase("OldPass#123456")).isFalse()
        assertThat(manager.verifyPassphrase("NewPass#654321")).isTrue()
        assertThat(manager.isBiometricUnlockEnabled()).isFalse()
        assertThat(prefs.getString("db_biometric_wrapped_passphrase", null)).isNull()
        assertThat(prefs.getString("db_biometric_wrapped_iv", null)).isNull()
    }

    @Test
    /**
     * Backup and restore encryption prefs restores verifier and biometric state.
     */
    fun backupAndRestoreEncryptionPrefs_restoresVerifierAndBiometricState() {
        prefs
            .edit()
            .putString("db_verifier_hash", "hash_blob")
            .putString("db_verifier_salt", "salt_blob")
            .putBoolean("biometric_unlock_enabled", true)
            .putString("db_biometric_wrapped_passphrase", "cipher_blob")
            .putString("db_biometric_wrapped_iv", "iv_blob")
            .commit()
        val backedUp = manager.backupEncryptionPrefs()
        assertThat(backedUp).isTrue()
        prefs
            .edit()
            .remove("db_verifier_hash")
            .remove("db_verifier_salt")
            .putBoolean("biometric_unlock_enabled", false)
            .remove("db_biometric_wrapped_passphrase")
            .remove("db_biometric_wrapped_iv")
            .commit()
        val restored = manager.restoreEncryptionPrefs()
        assertThat(restored).isTrue()
        assertThat(prefs.getString("db_verifier_hash", null)).isEqualTo("hash_blob")
        assertThat(prefs.getString("db_verifier_salt", null)).isEqualTo("salt_blob")
        assertThat(prefs.getBoolean("biometric_unlock_enabled", false)).isTrue()
        assertThat(prefs.getString("db_biometric_wrapped_passphrase", null)).isEqualTo("cipher_blob")
        assertThat(prefs.getString("db_biometric_wrapped_iv", null)).isEqualTo("iv_blob")
    }

    @Test
    /**
     * Update passphrase restores the OLD verifier when the biometric teardown fails after the new
     * verifier is already durable.
     *
     * This is the half-succeeded change the flow cannot recover from: `persistVerifier` commits
     * first, so a failure afterwards must not leave the NEW verifier in place while reporting a
     * refusal. The failing commit is the one inside `disableBiometricUnlockInternal`, which is the
     * only commit between the two.
     */
    fun updatePassphrase_whenBiometricTeardownCommitFails_restoresTheOldVerifier() {
        manager.configurePassphrase("OldPass#123456")
        val oldHash = prefs.getString("db_verifier_hash", null)
        val oldSalt = prefs.getString("db_verifier_salt", null)
        // The biometric teardown's commit is the one that writes the biometric pref, so keying the
        // failure on that key names the step instead of counting commits.
        armedPreferences.failFirstCommitWriting("biometric_unlock_enabled")

        val updated = manager.updatePassphrase("OldPass#123456", "NewPass#654321")

        assertThat(updated).isFalse()
        assertThat(manager.verifyPassphrase("OldPass#123456")).isTrue()
        assertThat(manager.verifyPassphrase("NewPass#654321")).isFalse()
        assertThat(prefs.getString("db_verifier_hash", null)).isEqualTo(oldHash)
        assertThat(prefs.getString("db_verifier_salt", null)).isEqualTo(oldSalt)
    }

    @Test
    /**
     * Update passphrase refuses without touching the verifier when the rollback cannot be armed,
     * because an unarmed change may land half-applied.
     *
     * The failing commit is the first one — the arming commit itself — so the new verifier is
     * never written and the old one is still the only one.
     */
    fun updatePassphrase_whenTheRollbackCannotBeArmed_refusesAndLeavesTheOldVerifier() {
        manager.configurePassphrase("OldPass#123456")
        val oldHash = prefs.getString("db_verifier_hash", null)
        // The arming commit is the first that writes the side-car verifier keys.
        armedPreferences.failFirstCommitWriting("_bak_db_verifier_hash")

        val updated = manager.updatePassphrase("OldPass#123456", "NewPass#654321")

        assertThat(updated).isFalse()
        assertThat(manager.verifyPassphrase("OldPass#123456")).isTrue()
        assertThat(manager.verifyPassphrase("NewPass#654321")).isFalse()
        assertThat(prefs.getString("db_verifier_hash", null)).isEqualTo(oldHash)
    }

    @Test
    /**
     * A successful change consumes the side-car backup it armed, so a stale backup cannot shadow
     * the live verifier.
     */
    fun updatePassphrase_whenItSucceeds_clearsTheSideCarBackupItArmed() {
        manager.configurePassphrase("OldPass#123456")

        val updated = manager.updatePassphrase("OldPass#123456", "NewPass#654321")

        assertThat(updated).isTrue()
        assertThat(manager.verifyPassphrase("NewPass#654321")).isTrue()
        assertThat(prefs.getString("_bak_db_verifier_hash", null)).isNull()
        assertThat(prefs.getString("_bak_db_verifier_salt", null)).isNull()
    }
}
