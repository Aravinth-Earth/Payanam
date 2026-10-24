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
 * The assistant **with no provider key**: the chat surface says setup happens in settings and offers
 * one pointer there, the disclosure is acknowledged exactly once, and the key/model fields live in the
 * settings editor rather than on the chat surface.
 *
 * Why these assertions exist (each one is a bug that shipped once):
 *
 *  - **two surfaces could configure the same connection** — the chat screen used to carry its own key
 *    and model form beside the settings editor, and only one of them had the provider, address and
 *    Verify check. The chat surface must now carry NO configuration control at all, so the two places
 *    cannot drift again: one pointer, and the key field and "Load models" only in settings;
 *  - **a stale model name once survived key removal** — with no key the settings screen must say
 *    "No model selected yet" and never show a remembered model id;
 *  - **"Remove key" must not appear when no key is stored** — the control only renders while one is;
 *  - **the chat surface must be unreachable without a validated key** — no input row, no model
 *    picker, no "Save & start", on the chat destination or after a visit to settings.
 *
 * WHAT IS NO LONGER PROVEN HERE: the invalid-key leg (paste a bad key → the loader resets → the chat
 * stays locked) drove the form that no longer exists. The equivalent now lives one destination over and
 * is covered by the ViewModel tests (`AssistantViewModelTest`, the models-loading and key-rejected
 * cases), which do not need a live provider.
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

        // ── 2. the setup surface: a headline, a caption, and EXACTLY ONE pointer into settings ──
        h.assertVisible(l.assistantSetupHeadline)
        h.assertVisible(l.ui.assistantSetupCaption)
        h.assertVisible(l.ui.assistantSetupPointer)
        check(h.count(l.ui.assistantSetupPointer) == 1) {
            "the setup surface must offer one pointer into settings, found ${h.count(l.ui.assistantSetupPointer)}\n" +
                h.diagnose(l.ui.assistantSetupPointer)
        }
        // Nothing on THIS surface configures anything any more — every field below is the settings
        // editor's, and a second copy here is the drift this change removed.
        h.assertNotVisible(l.assistantApiKey)
        h.assertNotVisible(l.assistantLoadModels)
        h.assertNotVisible(l.assistantModelPick)
        h.assertNotVisible(l.assistantSaveStart)
        h.assertNotVisible(l.assistantInputHint)
        h.log("setup-surface=ok")

        // ── 3. the pointer opens the settings editor, which owns the connection ──
        h.click(l.ui.assistantSetupPointer)
        h.assertVisible(l.assistantSettings)
        h.assertVisible(l.ui.assistantProviderWhere)
        h.assertVisible(l.ui.assistantProviderLocal)
        h.assertVisible(l.ui.assistantProviderCloud)
        h.assertVisible(l.assistantNoModel)
        h.assertNotVisible(STALE_MODEL)
        h.assertNotVisible(l.assistantRemoveKey)
        h.log("settings-entry=ok")

        // ── 4. the key field and "Load models" live HERE, below the "where does it run" choice ──
        // (the connection fields are deliberately not rendered until a backend exists: there is no
        // address to prefill and nothing to key a request to before that)
        h.click(l.ui.assistantProviderLocal)
        h.assertVisible(l.assistantApiKey)
        h.assertVisible(l.assistantLoadModels)
        h.assertNotVisible(l.assistantRemoveKey)
        h.log("connection-fields=ok")

        // ── 5. back on the chat surface: still unconfigured, and still no configuration control ──
        h.back()
        h.assertVisible(l.assistantSetupHeadline)
        h.assertNotVisible(l.assistantApiKey)
        h.assertNotVisible(l.assistantLoadModels)
        h.assertNotVisible(l.assistantInputHint)
        h.log("chat-still-unconfigured=ok")

        // ── 6. leaving and returning: notice stays acknowledged, key was never persisted ──
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
        /** The model id that used to be effectively pre-selected and lingered after removal. */
        const val STALE_MODEL = "deepseek-v4.1-flash"
    }
}
