//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the retry numbers promoted from the Android coordinator per #691 slice 1.
 *
 * A later refactor must not silently change retry behaviour, so each trigger's
 * [BackupRetryPolicy.maxAttempts] and [BackupRetryPolicy.retryDelayMs] are asserted
 * exactly as they exist in the promoted source.
 */
class BackupRetryPolicyTest {
    @Test
    fun `auto trigger allows ten attempts with a three second delay`() {
        val policy = retryPolicyFor(BackupTrigger.AUTO)

        assertThat(policy.maxAttempts).isEqualTo(10)
        assertThat(policy.retryDelayMs).isEqualTo(3_000L)
    }

    @Test
    fun `manual trigger allows five attempts with a one and a half second delay`() {
        val policy = retryPolicyFor(BackupTrigger.MANUAL)

        assertThat(policy.maxAttempts).isEqualTo(5)
        assertThat(policy.retryDelayMs).isEqualTo(1_500L)
    }

    @Test
    fun `export trigger allows five attempts with a one and a half second delay`() {
        val policy = retryPolicyFor(BackupTrigger.EXPORT)

        assertThat(policy.maxAttempts).isEqualTo(5)
        assertThat(policy.retryDelayMs).isEqualTo(1_500L)
    }
}
