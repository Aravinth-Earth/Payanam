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
import java.io.File

@RunWith(RobolectricTestRunner::class)
/**
 * Provides the db auth flow spec contract test.
 */
class DbAuthFlowSpecContractTest {
    private lateinit var logger: UnifiedLogger
    private lateinit var specJson: String

    @Before
    /**
     * Updates the set up.
     */
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        logger = UnifiedLogger.getInstance()
        specJson = loadSpecJson()
        logger.d(
            "DbAuthFlowSpecContractTest.setUp",
            "Loaded DB flow spec for security assertions",
            mapOf("length" to specJson.length),
        )
    }

    @Test
    /**
     * Disable biometric flow routes directly to cleanup without passphrase step.
     */
    fun disableBiometricFlow_routesDirectlyToCleanupWithoutPassphraseStep() {
        assertThat(specJson).contains("\"id\": \"TBIOSEC013\"")
        assertThat(specJson).contains("\"to\": \"BIOSEC_DELETE_KEY\"")
        assertThat(specJson).doesNotContain("\"to\": \"BIOSEC_DISABLE_VERIFY\"")
        assertThat(specJson).doesNotContain("\"id\": \"BIOSEC_DISABLE_VERIFY\"")
    }

    @Test
    /**
     * Biometric enable contract requires strong keystore wrapping and manual unlock guard.
     */
    fun biometricEnableContract_requiresStrongKeystoreWrappingAndManualUnlockGuard() {
        assertThat(specJson).contains("setUserAuthenticationRequired=true")
        assertThat(specJson).contains("manual passphrase unlock")
        assertThat(specJson).contains("Biometric not enabled or not available")
    }

    private fun loadSpecJson(): String {
        val candidatePaths =
            listOf(
                "docs/db/db-flow-boot-entry-flows.json",
                "../docs/db/db-flow-boot-entry-flows.json",
                "../../docs/db/db-flow-boot-entry-flows.json",
            )
        val existing =
            candidatePaths.map(::File).firstOrNull { it.exists() }
                ?: error("Unable to locate db-flow-boot-entry-flows.json from test working directory")
        logger.i(
            "DbAuthFlowSpecContractTest.loadSpecJson",
            "Resolved canonical DB flow spec path",
            mapOf("path" to existing.path),
        )
        return existing.readText()
    }
}
