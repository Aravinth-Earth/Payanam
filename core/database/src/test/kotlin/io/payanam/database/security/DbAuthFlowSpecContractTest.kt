//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Token set shared by the import rules. `plain` is the load-bearing token: it covers both the PLAIN and the
 * PLAINTEXT spellings the spec has used, matching the ps1 guard in build-tools/scripts/build-android.ps1.
 */
private val PLAINTEXT_TOKENS = Regex("plain|unencrypt|cleartext", RegexOption.IGNORE_CASE)

/** States a refused import may land on, and which therefore carry the refusal copy. */
private val REFUSAL_STATES = setOf("IMPORT_DB_UNUSABLE", "IMPORT_DB_BAD")

/** States that only an encrypted-verified database reaches. */
private val ENCRYPTED_VERIFIED_STATES = setOf("IMPORT_ENCRYPTED_DETECTED", "IMPORT_DB_ENC_SCREEN")

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

    @Test
    /**
     * The import path admits only the encrypted Payanam database.
     */
    fun importPath_admitsOnlyTheEncryptedDatabase() {
        val spec = JSONObject(specJson)
        val states = spec.getJSONArray("states")
        val transitions = spec.getJSONArray("transitions")
        val edges = (0 until transitions.length()).map { transitions.getJSONObject(it) }
        val stateIds = (0 until states.length()).map { states.getJSONObject(it).getString("id") }.toSet()

        // No plaintext-import state survives, by name or in raw text.
        val plaintextStates = stateIds.filter { it.startsWith("IMPORT_") && PLAINTEXT_TOKENS.containsMatchIn(it) }
        assertThat(plaintextStates).isEmpty()
        assertThat(specJson).doesNotContain("IMPORT_PLAINTEXT")
        assertThat(specJson).doesNotContain("IMPORT_DB_PLAIN")

        // A plaintext-guarded step may only land on a refusal -- and these are exactly the two that do.
        val plaintextEdges = edges.filter { PLAINTEXT_TOKENS.containsMatchIn(it.optString("guard")) }
        assertThat(plaintextEdges.map { it.getString("to") }.filter { it.startsWith("IMPORT_") })
            .containsExactly("IMPORT_DB_UNUSABLE", "IMPORT_DB_BAD")

        // Each import door exists, branches only to an encrypted-verified state or a refusal, and keeps its refusal branch.
        listOf("IMPORT_FORMAT_DETECT", "IMPORT_DB_FORMAT").forEach { door ->
            assertThat(stateIds).contains(door)
            val outbound = edges.filter { it.getString("from") == door }
            val unexpectedTargets = outbound.map { it.getString("to") }.toSet() - (ENCRYPTED_VERIFIED_STATES + REFUSAL_STATES)
            assertThat(unexpectedTargets).isEmpty()
            val keepsRefusal = outbound.any { PLAINTEXT_TOKENS.containsMatchIn(it.optString("guard")) && it.getString("to") in REFUSAL_STATES }
            assertThat(keepsRefusal).isTrue()
        }

        // Every transition endpoint exists: the renderer only prints dangling references, it does not fail on them.
        val dangling = edges.flatMap { listOf(it.getString("from"), it.getString("to")) }.filterNot { it in stateIds }
        assertThat(dangling).isEmpty()
    }

    private fun loadSpecJson(): String {
        // A system property lets the guard falsifier (build-tools/scripts/db-flow-guard-falsify.ps1) run these
        // assertions against a deliberately mutated spec; without it that mutant would have to overwrite the
        // canonical spec in place, which is the leak the ps1 side's -SpecPath parameter exists to prevent.
        val override = System.getProperty("payanam.dbflow.spec")
        val candidatePaths =
            listOfNotNull(
                override,
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
