//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.payanam.MainActivity
import io.payanam.common.logging.UnifiedLogger
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * The "Change provider or key" reconfigure contract, end to end (CodeRabbit r4019261159).
 *
 * Starts from a SEEDED configured state (see [AssistantSeedRule]) and proves the whole loop:
 * chat surface → settings → reconfigure → the assistant destination resolves to the setup
 * surface with the stored key retained, and the refresh itself logged the pending reconfigure
 * flag (the flag-specific evidence; the log also must not carry the seeded key).
 *
 * The completion half (saveAll clearing the flag, and a post-clear refresh resolving configured
 * again) needs a live provider key, so it is covered by the JVM tests instead.
 *
 * REQUIRES A FRESH INSTALL: run with '-KeepInstalled:$false'.
 */
@RunWith(AndroidJUnit4::class)
class AssistantReconfigureTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(AssistantSeedRule()).around(composeRule)

    private val h = E2eHarness(composeRule, tag = "E2E-ASSISTANT-RECONFIGURE")

    @Before
    fun keepScreenAwake() {
        keepScreenOn(composeRule.activity)
    }

    @Test
    fun changeProviderReturnsToSetupSurfaceWithStoredKey() {
        h.awaitRoot()

        // ── the seeded database is closed at launch: the normal unlock gate applies ──
        if (h.isOnScreen("Unlock")) {
            h.type("Passphrase", FreshSetup.TEST_DB_PASSPHRASE)
            h.click("Unlock")
        }
        h.assertVisible("Lenses")
        h.log("unlock=ok")

        h.openAssistant()

        // ── 1. the seeded configured state: the chat surface is live ──
        h.assertVisible(CHAT_INPUT_HINT)
        h.log("configured-chat=ok")

        // ── 2. reconfigure from settings (tag, not text: the label carries the model suffix) ──
        h.clickTag("assistant_settings_button")
        h.assertVisible("Assistant settings")
        h.clickTag("assistant_change_provider_button")

        // ── 3. the assistant destination resolves to the setup surface; the key is retained ──
        h.assertVisible("Connect a model provider")
        h.assertVisible(STORED_KEY_HINT)
        check(h.isEnabled("Load models")) { "'Load models' must be enabled with a stored key" }
        h.assertNotVisible(CHAT_INPUT_HINT)
        h.assertNotVisible(AssistantSeedRule.SEED_KEY)
        h.log("setup-surface-after-reconfigure=ok")

        // ── 4. flag evidence: the refresh reported the pending reconfigure itself. The log writer
        //       buffers, so flush + bounded poll over an explicit window instead of reading once. ──
        check(awaitLogContaining("Setup required flag set")) {
            "the refresh never logged the pending reconfigure flag"
        }
        check(!recentLogs().contains(AssistantSeedRule.SEED_KEY)) {
            "the seeded key appeared in the session log"
        }
        h.log("flag-evidence=ok")

        h.log("assistant-reconfigure=done")
    }

    private fun recentLogs(): String = runCatching { UnifiedLogger.getInstance().getRecentLogs(500) }.getOrDefault("")

    private fun awaitLogContaining(text: String, timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            runCatching { UnifiedLogger.getInstance().flush() }
            if (recentLogs().contains(text)) return true
            Thread.sleep(250)
        }
        return false
    }

    private companion object {
        const val CHAT_INPUT_HINT = "Ask about your data…"
        const val STORED_KEY_HINT = "A stored key is in use — paste a new key to replace it."
    }
}
