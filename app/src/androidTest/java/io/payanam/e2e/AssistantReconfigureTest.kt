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
 * The **endpoint-change key-removal contract**, end to end.
 *
 * Starts from a SEEDED configured state (see [AssistantSeedRule]) and proves the whole loop:
 * chat surface → settings → change the provider while a key is stored → saving that change ASKS
 * first → confirming removes the key → the chat destination resolves to the setup surface again.
 *
 * WHY THIS REPLACED THE OLD "CHANGE PROVIDER OR KEY" JOURNEY: that flow pointed the assistant
 * destination at the setup surface with the stored key retained, and the whole setup form lived on the
 * chat screen. Setup now lives only in settings, so the only way an endpoint can change under a stored
 * key is saving it from the settings editor, and the contract that matters is the confirmation: the
 * stored key must never be presented to a new endpoint silently.
 *
 * The log half is kept because the UI cannot tell "asked because the endpoint changed" from "asked for
 * some other reason": the ViewModel writes its own decision before showing the prompt, and that line
 * is what proves the gate fired for this change.
 *
 * REQUIRES A FRESH INSTALL: run with '-KeepInstalled:$false'.
 */
@RunWith(AndroidJUnit4::class)
class AssistantReconfigureTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(AssistantSeedRule()).around(composeRule)

    private val h = E2eHarness(composeRule, tag = "E2E-ASSISTANT-RECONFIGURE")

    /** Every label below comes from [E2eLabels], so a ta-IN device resolves it too. */
    private val l: E2eLabels.Labels get() = h.labels

    @Before
    fun keepScreenAwake() {
        keepScreenOn(composeRule.activity)
    }

    @Test
    fun changingTheEndpointAsksBeforeDroppingTheStoredKey() {
        h.awaitRoot()

        // ── the seeded database is closed at launch: the normal unlock gate applies ──
        if (h.isOnScreen(l.unlock)) {
            h.type(l.passphraseField, FreshSetup.TEST_DB_PASSPHRASE)
            h.click(l.unlock)
        }
        h.assertVisible(l.lenses)
        h.log("unlock=ok")

        h.openAssistant()

        // ── 1. the seeded configured state: the chat surface is live ──
        h.assertVisible(l.assistantInputHint)
        h.log("configured-chat=ok")

        // ── 2. settings from the chat surface's gear (tag, not text: the gear is an icon) ──
        h.clickTag("assistant_settings_button")
        h.assertVisible(l.assistantSettings)

        // ── 3. the connection section, with the stored key still in use ──
        h.assertVisible(l.ui.assistantProviderWhere)
        h.assertVisible(l.ui.assistantProviderCloud)
        h.assertVisible(l.assistantKeyStoredHint)
        h.assertVisible(l.assistantRemoveKey)
        check(h.isEnabled(l.assistantLoadModels)) { "'Load models' must be enabled with a stored key" }
        h.log("connection-section=ok")

        // ── 4. change the endpoint: a different backend is a different provider AND address, and the
        //       key field stays empty — so the key on file is the one that would be presented to it ──
        h.click(l.ui.assistantProviderLocal)
        h.log("provider-changed=ok")

        // ── 5. saving that change asks BEFORE it drops anything ──
        h.click(l.assistantSaveStart)
        h.assertVisible(l.ui.assistantKeyRemovalTitle)
        h.assertVisible(l.ui.assistantKeyRemovalBody)
        // Presence, not visibility: the settings screen sits behind the dialog, and "nothing was
        // dropped yet" is a statement about the tree, not about what the dialog lets you see.
        check(h.exists(l.assistantRemoveKey)) {
            "'Remove key' disappeared before the confirmation was answered\n${h.diagnose(l.assistantRemoveKey)}"
        }
        h.log("removal-confirmation=ok")

        // ── 6. confirming removes the key: "Remove key" only renders while one is stored ──
        h.click(l.ui.assistantKeyRemovalConfirm)
        h.waitForGoneExact(l.ui.assistantKeyRemovalTitle)
        h.waitForGone(l.assistantRemoveKey)
        h.log("key-removal-confirmed=ok")

        // ── 7. the removal reached the chat destination: it is the setup surface again ──
        h.back()
        h.assertVisible(l.assistantSetupHeadline)
        h.assertNotVisible(l.assistantInputHint)
        h.assertNotVisible(AssistantSeedRule.SEED_KEY)
        h.log("setup-surface-after-removal=ok")

        // ── 8. evidence: the gate fired for the ENDPOINT CHANGE, the accepted removal reached the
        //       store write, and the key never entered the session log. The log writer buffers, so
        //       flush + bounded poll over an explicit window instead of reading once. ──
        check(awaitLogContaining("Endpoint change would drop the stored key")) {
            "the save never reported that the endpoint change would drop the stored key"
        }
        check(awaitLogContaining("keyDropped=true")) {
            "the accepted removal never reached the store write"
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
    }
}
