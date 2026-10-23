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
 * SHARED ROUTE: it calls [FreshSetup.runIfNeeded], so it provisions on a clean install and REUSES the
 * app an earlier class in the same invocation provisioned - one install and one setup for the whole
 * route. Every class that creates list data deletes it, which is what makes sharing the app safe.
 */
@RunWith(AndroidJUnit4::class)
class AssistantSetupStatesTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-ASSISTANT-SETUP")

    /** Every label below comes from [E2eLabels], so a ta-IN device resolves it too. */
    private val l: E2eLabels.Labels get() = h.labels

    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun setupSurfaceRequiresKeyAndModel() {
        FreshSetup(h).runIfNeeded()

        h.openAssistant()

        // ── 1. the disclosure: shown on first open, acknowledged, never again ──
        h.assertVisible(l.assistantNoticeTitle)
        h.click(l.assistantGotIt)
        h.waitForGone(l.assistantNoticeTitle)
        h.log("notice-acknowledged=ok")

        // ── 2. the setup surface, and everything that must NOT exist yet ──
        h.assertVisible(l.assistantSetupHeadline)
        h.assertVisible(l.assistantApiKey)
        h.assertVisible(l.assistantLoadModels)
        h.assertVisible(l.assistantAdvanced)
        h.assertNotVisible(l.assistantInputHint)
        h.assertNotVisible(l.assistantModelPick)
        h.assertNotVisible(l.assistantSaveStart)
        h.log("setup-surface=ok")

        // ── 3. an invalid key: rejected, chat stays locked, loader resets ──
        h.type(l.assistantApiKey, INVALID_KEY)
        h.click(l.assistantLoadModels)
        // existence first — the banner sits near the bottom and may be below the fold
        rule.waitUntil(90_000) { h.exists(l.assistantErrorKeyRejected) }
        h.scrollTo(l.assistantErrorKeyRejected)
        h.assertVisible(l.assistantErrorKeyRejected)
        h.waitForGone(l.assistantLoadingModels)
        check(h.find(l.assistantLoadModels)) { "the button never returned to its idle label — stuck on loading" }
        h.assertNotVisible(l.assistantModelPick)
        h.assertNotVisible(l.assistantSaveStart)
        h.assertNotVisible(l.assistantInputHint)
        h.log("invalid-key-rejected=ok")

        // The error banner dismisses through its OWN control, not through the notice's "Got it" —
        // `assistant_error_dismiss` is a different string (measured 2026-10-16). The dismissal is proven
        // on that control's own label: the banner's MESSAGE text is also the API-key field's
        // `supportingText`, which outlives the banner, so waiting for the message to go can never be
        // satisfied (measured: 30s timeout while the banner was already gone).
        h.click(l.assistantErrorDismiss)
        h.waitForGoneExact(l.assistantErrorDismiss)
        h.log("error-dismissed=ok")

        // ── 4. advanced settings with no key: no stale model, no Remove key ──
        h.click(l.assistantAdvanced)
        h.assertVisible(l.assistantSettings)
        h.assertVisible(l.assistantNoModel)
        h.assertVisible(l.assistantChangeProvider)
        h.assertNotVisible(STALE_MODEL)
        h.assertNotVisible(l.assistantRemoveKey)
        h.back()

        // ── 5. leaving and returning: notice stays acknowledged, key was never persisted ──
        h.back()
        h.assertVisible(l.lenses)
        h.openAssistant()
        h.assertNotVisible(l.assistantNoticeTitle)
        h.assertVisible(l.assistantSetupHeadline)
        h.assertNotVisible(l.assistantInputHint)
        h.log("notice-persisted=ok")

        h.log("assistant-setup=done")
    }

    private companion object {
        /** Shaped like a real key but deliberately not one. */
        const val INVALID_KEY = "sk-payanam-e2e-not-a-real-key"

        /** The model id that used to be effectively pre-selected and lingered after removal. */
        const val STALE_MODEL = "deepseek-v4.1-flash"
    }
}
