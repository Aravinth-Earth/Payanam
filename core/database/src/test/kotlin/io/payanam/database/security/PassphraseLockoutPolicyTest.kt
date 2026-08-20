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
 * PassphraseLockoutPolicyTest.
 */
class PassphraseLockoutPolicyTest {
    private lateinit var logger: UnifiedLogger

    @Before
    /**
     * Set up.
     */
    fun setUp() {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()
        /** If. */
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        logger = UnifiedLogger.getInstance()
        logger.d("PassphraseLockoutPolicyTest.setUp", "Logger initialized")
    }

    @Test
    /**
     * Delay seconds for attempt progressively backs off and caps.
     */
    fun delaySecondsForAttempt_progressivelyBacksOffAndCaps() {
        /** A1. */
        val a1 = PassphraseLockoutPolicy.delaySecondsForAttempt(1)
        /** A2. */
        val a2 = PassphraseLockoutPolicy.delaySecondsForAttempt(2)
        /** A3. */
        val a3 = PassphraseLockoutPolicy.delaySecondsForAttempt(3)
        /** A4. */
        val a4 = PassphraseLockoutPolicy.delaySecondsForAttempt(4)
        /** A5. */
        val a5 = PassphraseLockoutPolicy.delaySecondsForAttempt(5)
        /** A8. */
        val a8 = PassphraseLockoutPolicy.delaySecondsForAttempt(8)
        logger.d(
            "PassphraseLockoutPolicyTest.delaySecondsForAttempt_progressivelyBacksOffAndCaps",
            "Computed delays",
            /** Map of. */
            mapOf("a1" to a1, "a2" to a2, "a3" to a3, "a4" to a4, "a5" to a5, "a8" to a8),
        )
        /** Assert that. */
        assertThat(a1).isEqualTo(0L)
        /** Assert that. */
        assertThat(a2).isEqualTo(0L)
        /** Assert that. */
        assertThat(a3).isEqualTo(30L)
        /** Assert that. */
        assertThat(a4).isEqualTo(60L)
        /** Assert that. */
        assertThat(a5).isEqualTo(120L)
        /** Assert that. */
        assertThat(a8).isEqualTo(300L)
    }
}
