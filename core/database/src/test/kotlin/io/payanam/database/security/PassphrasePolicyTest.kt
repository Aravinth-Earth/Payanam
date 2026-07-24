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
class PassphrasePolicyTest {
    private lateinit var logger: UnifiedLogger

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        logger = UnifiedLogger.getInstance()
        logger.d("PassphrasePolicyTest.setUp", "Logger initialized")
    }

    @Test
    fun validate_rejectsShortPassphrase() {
        val result = PassphrasePolicy.validate("Ab1!")
        logger.d("PassphrasePolicyTest.validate_rejectsShortPassphrase", "Validation result", mapOf("isValid" to result.isValid))
        assertThat(result.isValid).isFalse()
        assertThat(result.reasonCode).isEqualTo("min_length")
    }

    @Test
    fun validate_rejectsMissingUppercase() {
        val result = PassphrasePolicy.validate("lowercase12!")
        logger.d("PassphrasePolicyTest.validate_rejectsMissingUppercase", "Validation result", mapOf("isValid" to result.isValid))
        assertThat(result.isValid).isFalse()
        assertThat(result.reasonCode).isEqualTo("missing_uppercase")
    }

    @Test
    fun validate_acceptsStrongPassphrase() {
        val result = PassphrasePolicy.validate("S3cure!Passphrase")
        logger.d("PassphrasePolicyTest.validate_acceptsStrongPassphrase", "Validation result", mapOf("isValid" to result.isValid))
        assertThat(result.isValid).isTrue()
        assertThat(result.reasonCode).isNull()
    }
}
