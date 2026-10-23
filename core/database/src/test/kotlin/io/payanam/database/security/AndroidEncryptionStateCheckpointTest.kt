//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.security

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.shared.security.EncryptionCheckpointOutcome
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests the Android encryption-checkpoint adapter itself, over the real [DatabaseEncryptionManager]
 * and real `SharedPreferences`.
 *
 * WHY THIS EXISTS: the shared contract suite (`EncryptionStateCheckpointContractTest`) exercises a
 * fake, and that fake clears the captured copy on discard. Nothing asserted the same property of the
 * shipped adapter, so a `discardCheckpoint()` that removed nothing stayed green there while leaving
 * the `_bak_` family — the pre-change verifier hash/salt and the wrapped passphrase — durable in
 * `db_security_prefs`. This test asserts the adapter's own effect rather than its return value.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidEncryptionStateCheckpointTest {
    private lateinit var context: Context
    private lateinit var manager: DatabaseEncryptionManager
    private lateinit var prefs: SharedPreferences

    /**
     * Builds a real manager over Robolectric prefs. The logger must be initialised before the
     * manager's constructor asks for it.
     */
    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        manager = DatabaseEncryptionManager(context)
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    /**
     * The property the shipped adapter lacked: a discard removes the captured `_bak_` family, and
     * leaves every live key exactly as it was.
     *
     * The presence assertion AFTER capture is deliberate — without it a capture that wrote nothing
     * would satisfy the absence assertion, and the test could not fail for the right reason.
     */
    @Test
    fun discard_removesTheCapturedBackupFamily_andLeavesLiveKeysAlone() {
        assertThat(manager.configurePassphrase(PASSPHRASE)).isTrue()
        seedRecoverableMaterial()
        val checkpoint = AndroidEncryptionStateCheckpoint(manager)

        assertThat(checkpoint.captureCheckpoint()).isEqualTo(EncryptionCheckpointOutcome.CONFIRMED)
        // Every key the deleter is meant to remove is present FIRST, so a deleter that quietly stopped
        // covering one of them cannot pass by having nothing to remove.
        assertThat(backupKeys()).hasSize(EXPECTED_BACKUP_KEYS)
        val liveAfterCapture = liveEntries()

        assertThat(checkpoint.discardCheckpoint())
            .isEqualTo(EncryptionCheckpointOutcome.PERFORMED_UNCONFIRMED)

        assertThat(backupKeys()).isEmpty()
        // Values, not just names: a discard that also wrote a live preference would leave the key SET
        // identical and would otherwise pass this test.
        assertThat(liveEntries()).isEqualTo(liveAfterCapture)
    }

    /**
     * The no-wipe property: discarding with nothing captured must not remove a live key.
     *
     * A discard that threw would reach the caller's generic failure arm, whose recovery path calls
     * `restoreCheckpoint()`; with nothing captured that restore removes the LIVE keys and reports
     * CONFIRMED — a lockout. So the adapter must not propagate a failure from the manager.
     */
    @Test
    fun discard_withNothingCaptured_removesNoLiveKey() {
        assertThat(manager.configurePassphrase(PASSPHRASE)).isTrue()
        seedRecoverableMaterial()
        val checkpoint = AndroidEncryptionStateCheckpoint(manager)
        val liveBefore = liveEntries()

        checkpoint.discardCheckpoint()

        assertThat(liveEntries()).isEqualTo(liveBefore)
        assertThat(manager.hasPassphraseConfigured()).isTrue()
    }

    private fun backupKeys(): Set<String> =
        prefs.all.keys.filter { it.startsWith(BACKUP_PREFIX) }.toSet()

    /** Live preferences WITH their values, so a mutation that writes a value is visible. */
    private fun liveEntries(): Map<String, Any?> =
        prefs.all.filterKeys { !it.startsWith(BACKUP_PREFIX) }

    /**
     * Seeds the four recoverable-material keys. Without them a plain [DatabaseEncryptionManager.configurePassphrase]
     * leaves the backup family at 4 of its 8 keys, and the deleter's coverage of the wrapped-passphrase keys —
     * the keys this test exists for — would go unverified. The literal names mirror
     * `DatabaseEncryptionManagerSecurityModelTest`, which does the same, because the production constants are private.
     */
    private fun seedRecoverableMaterial() {
        prefs.edit()
            .putString("db_wrapped_passphrase", WRAPPED_MATERIAL)
            .putString("db_wrapped_iv", WRAPPED_MATERIAL)
            .putString("db_biometric_wrapped_passphrase", WRAPPED_MATERIAL)
            .putString("db_biometric_wrapped_iv", WRAPPED_MATERIAL)
            .commit()
    }

    private companion object {
        /** Written once here; the production constants are private to `DatabaseEncryptionManager`. */
        const val BACKUP_PREFIX = "_bak_"
        const val PREFS_NAME = "db_security_prefs"
        const val PASSPHRASE = "ValidTest#Pass123"
        const val WRAPPED_MATERIAL = "not-a-real-ciphertext"
        /** 7 string keys + 1 boolean key, per `BACKUP_STRING_KEYS`/`BACKUP_BOOLEAN_KEYS`. */
        const val EXPECTED_BACKUP_KEYS = 8
    }
}
