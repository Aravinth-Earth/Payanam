//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.payanam.core.ui.assistant.ASSISTANT_SETTINGS_BUTTON
import io.payanam.core.ui.assistant.AssistantConsentDialog
import io.payanam.core.ui.assistant.AssistantScreenActions
import io.payanam.core.ui.assistant.AssistantScreenContent
import io.payanam.core.ui.assistant.AssistantSettingsScreenActions
import io.payanam.core.ui.assistant.AssistantSettingsScreenContent
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.text.formatArgs
import io.payanam.core.ui.theme.PayanamTheme
import io.payanam.domain.assistant.AssistantAboutMe
import io.payanam.domain.assistant.AssistantBusy
import io.payanam.domain.assistant.AssistantChatMessage
import io.payanam.domain.assistant.AssistantDefaults
import io.payanam.domain.assistant.AssistantLength
import io.payanam.domain.assistant.AssistantProvider
import io.payanam.domain.assistant.AssistantSettings
import io.payanam.presentation.assistant.AssistantUiState
import org.junit.Rule
import org.junit.Test

/**
 * Do the shared assistant screens COMPOSE on the desktop target - asked over a hand-built state, with no
 * store, no ViewModel and no database.
 *
 * **The question this file is the only thing in the repo that asked.** `AssistantScreen.kt` (662 lines) and
 * `AssistantSettingsScreen.kt` (401) are 1,063 lines of `commonMain` that `desktop/` already COMPILES - it
 * depends on `:core:ui`, whose desktop artifact carries them - yet nothing ever COMPOSED one of them. Every
 * case here mounts a composable DIRECTLY, with no graph and no ViewModel, and that isolation is the point:
 * "it is shared code that builds" and "it composes here" are independent claims, and only the second one
 * was unmeasured.
 *
 * ## WHAT CHANGED WHEN THE DESTINATIONS WERE WIRED, AND WHAT DID NOT
 *
 * This file once justified itself by naming the gap it could not close: the `assistant` and
 * `assistantSettings` desktop destinations raised `desktopDestinationNotMigrated`, so nothing in `:desktop`
 * ever mounted either screen. That gap is closed - both destinations now render these SAME composables over
 * the shared ViewModel and the desktop configuration store, and `DesktopNavHostSharedScreenRenderTest`
 * walks both routes through the real graph. Nothing here had to change for that, which is exactly the
 * point: these cases compose the screens over a hand-built state, so a change to the destinations' wiring
 * cannot disturb them, and a change to the SCREENS still fails them.
 *
 * **What these cases deliberately do NOT prove, stated as a named gap.**
 *  - Nothing here reads or writes the stored key, model or settings, so the store/ViewModel path is not
 *    measured by this file. `DesktopAssistantDestinationWiringTest` owns that half: it drives the real
 *    shared ViewModel through the real `AssistantConfigurationStoreJvm` and asserts the ROWS the screen
 *    would show. Read this file for "does the screen compose"; read that one for "does the wiring store".
 *  - `AssistantConsentDialog` is mounted because it is one of the same four files and reads the same
 *    `LocalUiStrings`, but it is reachable from the Lenses entry card rather than from these two routes;
 *    it is compose evidence, not evidence that either route shows it.
 *  - Every case asserts the shared string table's ENGLISH values, which is what makes each assertion about
 *    the SCREEN's own copy rather than about hardcoded text. It says nothing about the Tamil table.
 */
class DesktopAssistantSharedScreenRenderTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val strings = UiStrings.of(UiLanguage.ENGLISH)

    /**
     * The unconfigured chat screen is a POINTER to settings, not a place to configure anything.
     *
     * Setup used to be carried by this screen as its own key and model form, which meant two surfaces
     * could configure the same connection and only one of them had the provider, the address and the
     * Verify check. That form is gone: `SetupRequiredContent` says what is missing and offers ONE button
     * into the settings editor, which owns the connection fields. This case is the two-sided claim -
     * the headline, the caption and that single pointer button are present, and the controls that belong
     * to the settings editor's connection section ("API key", "Load models") are NOT drawn here, so setup
     * cannot quietly reappear on chat. Every string asserted is the screen's OWN copy from the shared
     * table, so a label swapped for a literal would fail here.
     */
    @Test
    fun `the unconfigured chat screen points at settings instead of carrying a setup form`() {
        // `loading = false` with no key and no model is the state the ViewModel publishes for a user who
        // has never configured the feature: `isConfigured` is false, so `SetupRequiredContent` composes.
        renderAssistant(uiState = AssistantUiState(loading = false, hasKey = false))

        composeRule.onNodeWithText(strings.assistantTitle).assertExists()
        composeRule.onNodeWithText(strings.assistantSetupHeadline).assertExists()
        composeRule.onNodeWithText(strings.assistantSetupCaption).assertExists()
        // EXACTLY one node, so this is a claim about the single pointer button rather than about the
        // copy appearing somewhere: a second control here would be a second way into configuration.
        composeRule.onAllNodesWithText(strings.assistantSetupPointer).assertCountEquals(1)
        // The two controls that moved to the settings editor and must not be duplicated back onto chat.
        // `assistant_key_label` carries the same text as `assistant_api_key` ("API key"), and the settings
        // field uses the latter, so the field's own label is what is asserted absent.
        composeRule.onAllNodesWithText(strings.assistantApiKey).assertCountEquals(0)
        composeRule.onAllNodesWithText(strings.assistantKeyLoadModels).assertCountEquals(0)
    }

    /**
     * The chat screen renders an EMPTY conversation as a deliberate starter surface, not as an empty list.
     *
     * The distinction is the whole claim: `ChatContent` branches on `messages.isEmpty() && busy == null` and
     * renders either the suggestion chips or the empty prompt. A screen that simply drew nothing would pass
     * an assertion like "no crash", and would be a broken feature with no exception to show for it.
     */
    @Test
    fun `the chat screen renders an empty conversation as the starter prompt rather than a blank surface`() {
        renderAssistant(
            uiState =
                AssistantUiState(
                    loading = false,
                    hasKey = true,
                    settings = configuredSettings(),
                ),
        )

        // `chipsEnabled` defaults TRUE, so the empty state is the hint plus the chip row. Both are asserted:
        // the hint is the sentence that explains the surface, and the chips are the thirteen copy keys the
        // screen names, so a chip list that silently emptied would still leave the hint behind.
        composeRule.onNodeWithText(strings.assistantEmptyHint).assertExists()
        composeRule.onAllNodesWithText(strings.sugStreaks).assertCountEquals(1)
        // The input row is always present, so its placeholder is the third thing that distinguishes this
        // from a screen that merely failed to throw.
        composeRule.onNodeWithText(strings.assistantInputHint).assertExists()
        // The configured model is rendered in the top bar under the title, which is why this state also
        // proves the title and the settings gear coexist rather than one replacing the other.
        composeRule.onNodeWithText(TEST_MODEL).assertExists()
    }

    /**
     * A state that renders but is EMPTY is a spinner, not a crash and not a blank frame.
     *
     * `AssistantUiState.loading` DEFAULTS TO TRUE, which is the shape a fresh destination gets before the
     * store answers. On the desktop the store does not exist yet, so this branch is what a user would meet
     * if the destination were wired naively - and a screen whose loading branch threw or drew nothing would
     * be indistinguishable from one that worked until the store landed. `ProgressBarRangeInfo` is how the
     * assertion is made about the SPINNER rather than about the absence of text: an indeterminate
     * `CircularProgressIndicator` publishes `Indeterminate`, and this is measured to be a real node in the
     * desktop semantics tree rather than a decoration.
     */
    @Test
    fun `the chat screen renders a rendered-but-empty state as a spinner rather than crashing`() {
        // Every field defaulted, i.e. the state a destination hands the screen before anything loads.
        renderAssistant(uiState = AssistantUiState())

        // The spinner, located by the semantics property Material3's own indeterminate indicator sets.
        // Named rather than "no setup form": a spinner that rendered nothing and a spinner that rendered the
        // wrong thing differ only in this property, and it is the one Material3 sets on its own.
        val indeterminateSpinner =
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)
        composeRule.onAllNodes(indeterminateSpinner).assertCountEquals(1)
        // The top bar renders above the loading branch, so the screen is not merely a bare spinner either -
        // this is the titled chrome with the spinner where the body goes.
        composeRule.onNodeWithText(strings.assistantTitle).assertExists()
        // And the setup form is NOT drawn, which is what makes the loading branch a branch rather than the
        // setup form showing through behind a spinner.
        composeRule.onAllNodesWithText(strings.assistantSetupHeadline).assertCountEquals(0)
    }

    /**
     * A conversation with messages, a receipt, and a mid-turn busy row.
     *
     * The receipt line is the assertion that matters: it is the one place the screen substitutes three
     * values into shared copy through `formatArgs`, and a screen that dropped the substitution would render
     * a literal `%1$s` that still looks like a receipt. The two bubble shapes are asserted separately
     * because they are separate branches in `MessageBubble`.
     *
     * No error banner is asserted here, because this screen's error banner takes its copy from a `when`
     * over `AssistantErrorKind` that needs a state field no branch above populates; asserting it here would
     * be asserting a path this file does not drive.
     */
    @Test
    fun `the chat screen composes a conversation with a receipt and a busy row`() {
        // One assistant message with a receipt and one user message: the two bubble shapes. The receipt
        // line is asserted below, because it is the one place the screen formats three values into one
        // string through the shared `formatArgs`.
        renderAssistant(
            uiState =
                AssistantUiState(
                    loading = false,
                    hasKey = true,
                    settings = configuredSettings(),
                    messages =
                        listOf(
                            AssistantChatMessage(
                                role = AssistantChatMessage.ROLE_USER,
                                text = "Which habits slipped last week?",
                                id = 1L,
                            ),
                            AssistantChatMessage(
                                role = AssistantChatMessage.ROLE_ASSISTANT,
                                text = "Three of them.",
                                model = TEST_MODEL,
                                queryCount = 2,
                                rowCount = 7,
                                id = 2L,
                            ),
                        ),
                ),
        )

        composeRule.onNodeWithText("Which habits slipped last week?").assertExists()
        composeRule.onNodeWithText("Three of them.").assertExists()
        // The receipt, formatted exactly as the shared table + `formatArgs` would render it.
        composeRule
            .onNodeWithText(
                strings.assistantReceipt.formatArgs(TEST_MODEL, 2, 7) + " · " + strings.assistantAiGenerated,
            ).assertExists()
        // The busy row is what a mid-turn chat shows, and its label is the only copy there for that state.
        renderAssistant(
            uiState =
                AssistantUiState(
                    loading = false,
                    hasKey = true,
                    settings = configuredSettings(),
                    busy = AssistantBusy.Thinking,
                ),
        )
        composeRule.onNodeWithText(strings.assistantBusyThinking).assertExists()
    }

    @Test
    fun `the settings screen composes every section over a hand-built state with no store`() {
        renderSettings(uiState = AssistantUiState(loading = false, hasKey = true, settings = configuredSettings()))

        composeRule.onNodeWithText(strings.assistantSettingsTitle).assertExists()
        // The saved/privacy notes are the screen's first two rows and the promise the rest of the screen
        // makes to the user, so they are asserted before any control.
        composeRule.onNodeWithText(strings.assistantSettingsSavedNote).assertExists()
        composeRule.onNodeWithText(strings.assistantSettingsPrivacyNote).assertExists()
        // The three length presets render as radio rows, and each preset's LABEL is a `when` over the enum:
        // all three asserted, because a missing branch would render an empty row rather than fail a compile.
        composeRule.onNodeWithText(strings.assistantSettingsLengthTitle).assertExists()
        composeRule.onNodeWithText(strings.assistantLengthShort).assertExists()
        composeRule.onNodeWithText(strings.assistantLengthBalanced).assertExists()
        composeRule.onNodeWithText(strings.assistantLengthElaborated).assertExists()
        // The two switches and the three numeric caps.
        composeRule.onNodeWithText(strings.assistantSettingsChips).assertExists()
        composeRule.onNodeWithText(strings.assistantSettingsShowMethod).assertExists()
        composeRule.onNodeWithText(strings.assistantSettingsMaxRows).assertExists()
        // The prompt editor's budget line is the second `formatArgs` call in these two screens.
        composeRule
            .onNodeWithText(
                strings.assistantSettingsPromptBudget.formatArgs(
                    0,
                    AssistantDefaults.MAX_PROMPT_ADDITIONS,
                ),
            ).assertExists()
        // The About-me section and its first field label.
        composeRule.onNodeWithText(strings.assistantSettingsAboutTitle).assertExists()
        composeRule.onNodeWithText(strings.assistantAboutGoals).assertExists()
    }

    /**
     * The settings screen renders the CONNECTION section - the one place the assistant is configured.
     *
     * This replaces an assertion on "Change provider", which was a row on this screen and disappeared with
     * the chat-surface form: the section is what is left, and it is now the only surface that can name a
     * host for the stored key. So the claim is about real controls, not about the screen composing:
     * `assistantProviderWhere` and one radio row per `AssistantBackend` entry (labels from a `when`, so a
     * missing branch would render an empty row rather than fail a compile), then the provider field and
     * the address field below them.
     *
     * The state is a chosen provider with NO key and NO model, which is a real moment in setup: the fields
     * below the backend choice only render once a provider exists (`ConnectionSection` returns early when
     * `uiState.provider` is null, because there is nothing to check an address or a key against), and the
     * unconfigured lines are asserted alongside so this cannot pass on a default-constructed state.
     */
    @Test
    fun `the settings screen renders the connection section's backend choices and provider fields`() {
        renderSettings(
            uiState =
                AssistantUiState(
                    loading = false,
                    hasKey = false,
                    // Chosen but not saved. The backend radios hold no state of their own - they read the
                    // chosen provider's group - so this one field is what makes the rest of the section
                    // render at all.
                    provider = AssistantProvider.OLLAMA,
                ),
        )

        // Where the model runs: the section title and both backend labels.
        composeRule.onNodeWithText(strings.assistantProviderWhere).assertExists()
        composeRule.onNodeWithText(strings.assistantProviderLocal).assertExists()
        composeRule.onNodeWithText(strings.assistantProviderCloud).assertExists()
        // The provider dropdown's own label, and the address field - located by the `contentDescription`
        // the screen puts on it, which is a stable hook rather than a second copy of the label.
        composeRule.onNodeWithText(strings.assistantProvider).assertExists()
        // Located by its visible label: the field no longer repeats it as a content description, which
        // made a screen reader announce "Address, Address".
        composeRule.onNodeWithText(strings.assistantAddress).assertExists()
        // The unconfigured half still reads the state rather than a default: "no model selected yet" present,
        // and the key-removal row, which is gated on `hasKey`, absent.
        composeRule.onNodeWithText(strings.assistantNoModel).assertExists()
        composeRule.onAllNodesWithText(strings.assistantRemoveKey).assertCountEquals(0)
    }

    /** The consent gate: the same table, the same file family, mounted with only its two callbacks. */
    @Test
    fun `the consent dialog composes its own copy over two callbacks`() {
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
                PayanamTheme(darkTheme = false) {
                    AssistantConsentDialog(
                        onProceed = { },
                        onCancel = { },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(strings.assistantConsentTitle).assertExists()
        composeRule.onNodeWithText(strings.assistantConsentProceed).assertExists()
    }

    /**
     * Both screens' state-dependent affordances are driven by the state, not by a rendered-by-default row.
     *
     * A screen that ignored its state and drew everything unconditionally would pass every string assertion
     * above, because every string in this file is unconditional copy. So this case pins the three
     * conditionals the two screens actually branch on, in both directions where both directions exist:
     *  - the chat screen's top-bar settings gear is gated on `uiState.hasKey`;
     *  - the settings screen's key-removal row is gated on `uiState.hasKey`;
     *  - the settings screen's "no model selected" line is gated on NOT being configured, i.e. it appears
     *    for `hasKey = false` and disappears once a key AND a model are present.
     *
     * The gear is located by its `ASSISTANT_SETTINGS_BUTTON` test tag rather than by its content
     * description, because the settings screen carries the same `settingsTitle` label in its own chrome -
     * the tag is the only thing that distinguishes the chat screen's gear from it, and it is a constant the
     * shared code exports precisely so a test need not repeat the literal.
     */
    @Test
    fun `both screens gate their conditional affordances on the state rather than rendering them always`() {
        // Configured: a key AND a model. The gear and the remove-key row are present; the "no model" line,
        // which is the branch for the unconfigured half, is not.
        renderAssistant(uiState = AssistantUiState(loading = false, hasKey = true, settings = configuredSettings()))
        composeRule.onNodeWithTag(ASSISTANT_SETTINGS_BUTTON).assertExists()

        renderSettings(uiState = AssistantUiState(loading = false, hasKey = true, settings = configuredSettings()))
        composeRule.onNodeWithText(strings.assistantRemoveKey).assertExists()
        composeRule.onAllNodesWithText(strings.assistantNoModel).assertCountEquals(0)

        // Unconfigured: the same screens, one state field different. Each conditional flips, which is what
        // makes these assertions about the state rather than about the copy.
        renderAssistant(uiState = AssistantUiState(loading = false, hasKey = false))
        composeRule.onAllNodesWithTag(ASSISTANT_SETTINGS_BUTTON).assertCountEquals(0)

        renderSettings(uiState = AssistantUiState(loading = false, hasKey = false))
        composeRule.onAllNodesWithText(strings.assistantRemoveKey).assertCountEquals(0)
        composeRule.onNodeWithText(strings.assistantNoModel).assertExists()
    }

    /** A configured state: a key plus a chosen model, which is what makes the chat surface openable. */
    private fun configuredSettings(): AssistantSettings =
        AssistantSettings(
            model = TEST_MODEL,
            length = AssistantLength.BALANCED,
            chipsEnabled = true,
            aboutMe = AssistantAboutMe(goals = "Finish the desktop build"),
        )

    /**
     * Mounts the chat screen's content over [uiState], with the shared theme and the shared string table.
     *
     * `LocalUiStrings` has NO default - a read without a provider fails loudly by design - so the provider
     * is installed here rather than inside the screen, exactly as `NotesScreenTest` and
     * `SettingsSectionsTest` do in `:core:ui`. `PayanamTheme` is present because these are Material3
     * screens whose `MaterialTheme` reads would otherwise resolve against nothing.
     */
    private fun renderAssistant(uiState: AssistantUiState) {
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
                PayanamTheme(darkTheme = false) {
                    AssistantScreenContent(
                        uiState = uiState,
                        actions = AssistantScreenActions(),
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** [renderAssistant] for the settings editor, whose action holder is a separate type by design. */
    private fun renderSettings(uiState: AssistantUiState) {
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
                PayanamTheme(darkTheme = false) {
                    AssistantSettingsScreenContent(
                        uiState = uiState,
                        actions = AssistantSettingsScreenActions(),
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        /**
         * A model name that is not copy, so a receipt line is distinguishable from the rest of the tree.
         *
         * Real-looking rather than a sentinel like `MODEL`, because the screen renders this string through
         * `formatArgs` and into a `TopAppBar` title - a value with a space exercises the same path a stored
         * model name takes.
         */
        private const val TEST_MODEL = "test-model-id"
    }
}
