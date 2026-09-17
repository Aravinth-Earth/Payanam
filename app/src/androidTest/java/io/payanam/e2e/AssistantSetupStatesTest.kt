//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.payanam.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The AI Assistance **setup surface with no provider key**: the entry exists, the disclosure is
 * acknowledged exactly once, an invalid key is rejected without ever unlocking the chat surface,
 * and the loader never lies about being busy.
 *
 * Why these assertions exist (each one is a bug that shipped once):
 *
 *  - **the model list is unauthenticated** — the provider returns its catalogue for a bogus key, so
 *    "Load models" must prove the key with a real chat call before anything is selectable;
 *  - **a rejected key once left the spinner running forever** — after the error banner appears the
 *    button must be back to "Load models", not stuck on "Loading models…";
 *  - **a stale model name once survived key removal** — with no key the settings screen must say
 *    "No model selected yet" and never show a remembered model id;
 *  - **the chat surface must be unreachable without a validated key** — no input row, no model
 *    picker, no "Save & start chatting".
 *
 * REQUIRES A FRESH INSTALL: run with '-KeepInstalled:$false', like the other journey tests.
 */
@RunWith(AndroidJUnit4::class)
class AssistantSetupStatesTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-ASSISTANT-SETUP")

    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun setupSurfaceRequiresKeyAndModel() {
        FreshSetup(h).run()
        h.scrollTo("Verify and proceed")
        h.click("Verify and proceed")
        h.assertVisible("Per Dimension")

        h.openAssistant()

        // ── 1. the disclosure: shown on first open, acknowledged, never again ──
        h.assertVisible(NOTICE_TITLE)
        h.click("Got it")
        h.waitForGone(NOTICE_TITLE)
        h.log("notice-acknowledged=ok")

        // ── 2. the setup surface, and everything that must NOT exist yet ──
        h.assertVisible("Connect a model provider")
        h.assertVisible("API key")
        h.assertVisible("Load models")
        h.assertVisible("Advanced settings")
        h.assertNotVisible(CHAT_INPUT_HINT)
        h.assertNotVisible("Pick a model")
        h.assertNotVisible("Save & start chatting")
        h.log("setup-surface=ok")

        // ── 3. an invalid key: rejected, chat stays locked, loader resets ──
        h.type("API key", INVALID_KEY)
        h.click("Load models")
        // existence first — the banner sits near the bottom and may be below the fold
        rule.waitUntil(90_000) { h.exists(KEY_REJECTED) }
        h.scrollTo(KEY_REJECTED)
        h.assertVisible(KEY_REJECTED)
        h.waitForGone("Loading models…")
        check(h.find("Load models")) { "the button never returned to its idle label — stuck on loading" }
        h.assertNotVisible("Pick a model")
        h.assertNotVisible("Save & start chatting")
        h.assertNotVisible(CHAT_INPUT_HINT)
        h.log("invalid-key-rejected=ok")

        // The error banner dismisses like a notice, and stays dismissed.
        h.click("Got it")
        h.waitForGone(KEY_REJECTED)
        h.log("error-dismissed=ok")

        // ── 4. advanced settings with no key: no stale model, no Remove key ──
        h.click("Advanced settings")
        h.assertVisible("Assistant settings")
        h.assertVisible("No model selected yet")
        h.assertVisible("Change provider or key")
        h.assertNotVisible(STALE_MODEL)
        h.assertNotVisible("Remove key (turn the feature off)")
        h.back()

        // ── 5. leaving and returning: notice stays acknowledged, key was never persisted ──
        h.back()
        h.assertVisible("Lenses")
        h.openAssistant()
        h.assertNotVisible(NOTICE_TITLE)
        h.assertVisible("Connect a model provider")
        h.assertNotVisible(CHAT_INPUT_HINT)
        h.log("notice-persisted=ok")

        h.log("assistant-setup=done")
    }

    private companion object {
        /** Shaped like a real key but deliberately not one. */
        const val INVALID_KEY = "sk-payanam-e2e-not-a-real-key"

        const val NOTICE_TITLE = "About AI answers"
        const val KEY_REJECTED = "Key rejected — check the key and try again."
        const val CHAT_INPUT_HINT = "Ask about your data…"

        /** The model id that used to be effectively pre-selected and lingered after removal. */
        const val STALE_MODEL = "deepseek-v4.1-flash"
    }
}
