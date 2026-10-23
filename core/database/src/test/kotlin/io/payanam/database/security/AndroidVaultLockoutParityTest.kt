//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.shared.security.SharedPassphraseLockoutPolicy
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The parity holder lives in `AndroidPassphraseVault.kt`, beside classes whose static initialiser needs an
// Android runtime, so a plain-JVM test of it dies in `ExceptionInInitializerError` before any assertion runs.
// Robolectric is what this module's other security tests already use for exactly that.
@RunWith(RobolectricTestRunner::class)
/**
 * Pins Android's lockout table to the shared one.
 *
 * WHY THIS NEEDS A TEST AT ALL: the shared policy in `:core:shared` is the cross-platform authority, and
 * `PassphraseLockoutPolicy` in this module is a private copy of the same table that
 * [DatabaseEncryptionManager.recordFailedUnlockAttempt] actually consults. Nothing in the type system connects
 * them, so a change to the shared file would leave Android quietly enforcing an older schedule while the
 * desktop enforced the new one — and the user would be locked out on different attempts depending on which
 * device they typed the wrong passphrase on. This is the test that notices.
 *
 * The thresholds the shared policy documents are asserted by name as well, because a test comparing two
 * functions to each other passes happily if BOTH are wrong.
 */
class AndroidVaultLockoutParityTest {
    /**
     * Installs the logger BEFORE anything reads the policy.
     *
     * `PassphraseLockoutPolicy` resolves `UnifiedLogger.getInstance()` in its own object initialiser, so a
     * test that touches the table with no logger installed does not merely fail - it poisons the class for
     * every later test in the same classloader (`NoClassDefFoundError: Could not initialize class ...`),
     * which is exactly what happened when this test first ran without this method. The sibling
     * `PassphraseLockoutPolicyTest` does the same thing for the same reason.
     */
    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
    }

    @Test
    fun `android's lockout table agrees with the shared policy on every attempt`() {
        assertThat(AndroidVaultLockoutParity.parityIsHeld()).isTrue()
    }

    @Test
    fun `the shared policy still costs attempt 3 thirty seconds and attempt 6 three hundred`() {
        // The two thresholds the shared unlock gate routes through the vault by, pinned directly.
        assertThat(SharedPassphraseLockoutPolicy.delaySecondsForAttempt(3))
            .isEqualTo(AndroidVaultLockoutParity.THIRD_ATTEMPT_SECONDS)
        assertThat(SharedPassphraseLockoutPolicy.delaySecondsForAttempt(6))
            .isEqualTo(AndroidVaultLockoutParity.SIXTH_ATTEMPT_SECONDS)
        // And the constants are the numbers the contract documents, not whatever the function currently says.
        assertThat(AndroidVaultLockoutParity.THIRD_ATTEMPT_SECONDS).isEqualTo(30L)
        assertThat(AndroidVaultLockoutParity.SIXTH_ATTEMPT_SECONDS).isEqualTo(300L)
    }
}
