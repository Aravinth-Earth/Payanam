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
        val context = ApplicationProvider.getApplicationContext<Context>()
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
        val a1 = PassphraseLockoutPolicy.delaySecondsForAttempt(1)
        val a2 = PassphraseLockoutPolicy.delaySecondsForAttempt(2)
        val a3 = PassphraseLockoutPolicy.delaySecondsForAttempt(3)
        val a4 = PassphraseLockoutPolicy.delaySecondsForAttempt(4)
        val a5 = PassphraseLockoutPolicy.delaySecondsForAttempt(5)
        val a8 = PassphraseLockoutPolicy.delaySecondsForAttempt(8)
        logger.d(
            "PassphraseLockoutPolicyTest.delaySecondsForAttempt_progressivelyBacksOffAndCaps",
            "Computed delays",
            mapOf("a1" to a1, "a2" to a2, "a3" to a3, "a4" to a4, "a5" to a5, "a8" to a8),
        )
        assertThat(a1).isEqualTo(0L)
        assertThat(a2).isEqualTo(0L)
        assertThat(a3).isEqualTo(30L)
        assertThat(a4).isEqualTo(60L)
        assertThat(a5).isEqualTo(120L)
        assertThat(a8).isEqualTo(300L)
    }
}
