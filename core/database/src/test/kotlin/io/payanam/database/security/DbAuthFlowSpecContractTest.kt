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
 * DbAuthFlowSpecContractTest.
 */
class DbAuthFlowSpecContractTest {
    private lateinit var logger: UnifiedLogger
    private lateinit var specJson: String

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
        specJson = loadSpecJson()
        logger.d(
            "DbAuthFlowSpecContractTest.setUp",
            "Loaded DB flow spec for security assertions",
            /** Map of. */
            mapOf("length" to specJson.length),
        )
    }

    @Test
    /**
     * Disable biometric flow routes directly to cleanup without passphrase step.
     */
    fun disableBiometricFlow_routesDirectlyToCleanupWithoutPassphraseStep() {
        /** Assert that. */
        assertThat(specJson).contains("\"id\": \"TBIOSEC013\"")
        /** Assert that. */
        assertThat(specJson).contains("\"to\": \"BIOSEC_DELETE_KEY\"")
        /** Assert that. */
        assertThat(specJson).doesNotContain("\"to\": \"BIOSEC_DISABLE_VERIFY\"")
        /** Assert that. */
        assertThat(specJson).doesNotContain("\"id\": \"BIOSEC_DISABLE_VERIFY\"")
    }

    @Test
    /**
     * Biometric enable contract requires strong keystore wrapping and manual unlock guard.
     */
    fun biometricEnableContract_requiresStrongKeystoreWrappingAndManualUnlockGuard() {
        /** Assert that. */
        assertThat(specJson).contains("setUserAuthenticationRequired=true")
        /** Assert that. */
        assertThat(specJson).contains("manual passphrase unlock")
        /** Assert that. */
        assertThat(specJson).contains("Biometric not enabled or not available")
    }

    private fun loadSpecJson(): String {
        /** Candidate paths. */
        val candidatePaths =
            /** List of. */
            listOf(
                "docs/db/db-flow-boot-entry-flows.json",
                "../docs/db/db-flow-boot-entry-flows.json",
                "../../docs/db/db-flow-boot-entry-flows.json",
            )
        /** Existing. */
        val existing =
            candidatePaths.map(::File).firstOrNull { it.exists() }
                ?: error("Unable to locate db-flow-boot-entry-flows.json from test working directory")
        logger.i(
            "DbAuthFlowSpecContractTest.loadSpecJson",
            "Resolved canonical DB flow spec path",
            /** Map of. */
            mapOf("path" to existing.path),
        )
        return existing.readText()
    }
}
