//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui.settings

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.presentation.settings.SettingsUiState
import io.payanam.presentation.updates.UpdateChannel
import io.payanam.shared.security.BiometricAvailability
import org.junit.Rule
import org.junit.Test

/**
 * The passphrase reveal toggle inside the shared database-security section's enable dialog, rendered for
 * real on the JVM target.
 *
 * WHY THIS NEEDS ITS OWN FILE. [SettingsDatabaseSecuritySectionTest] already proves what the dialog DOES -
 * that a refusal keeps it open, that a verified passphrase closes it. None of those cases can see whether
 * the reveal toggle has a NAME, because a button with no description behaves identically to a button with
 * one: both accept the click and both flip the field. The gap is only visible in the rendered semantics
 * tree, so it belongs beside the rendered section rather than inside a case about the write.
 *
 * THE GAP, stated as the regression it detects. This control is a trailing icon whose entire content is an
 * eye, and its `Icon` passed `contentDescription = null` - so the accessibility tree held one more unnamed
 * button in a dialog whose whole purpose is asking for a secret the user is trying to READ BACK. A screen
 * reader announces "button", and the two words that say what pressing it does ("Show passphrase" / "Hide
 * passphrase") appear nowhere. This cannot be caught by a grep for missing strings: `null` is precisely what
 * the Compose API takes to mean "decorative, deliberately unlabelled", and the identical control is labelled
 * at nine other call sites in this same module - so the outlier is invisible except by comparison of the
 * rendered tree against what those sites render.
 *
 * The fix is the two EXISTING copy keys ([UiStrings.dbPassphraseShowToggle] /
 * [UiStrings.dbPassphraseHideToggle]), already present in both shipped languages; no string was invented for
 * this control, and the test asserts by VALUE from the table so a literal cannot be substituted for it.
 *
 * `LocalUiStrings` has no default - a read without a provider fails loudly by design - which is what forces
 * the table to be installed here and keeps these assertions about the SCREEN rather than about whichever
 * default a test happened to inherit.
 */
class SettingsDatabaseSecuritySectionSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val strings: UiStrings = UiStrings.of(UiLanguage.ENGLISH)

    @Test
    fun `the masked passphrase's reveal toggle is named from the shared copy table`() {
        renderDialog()

        // Asserted by VALUE, not by presence of some string: a toggle labelled with an unrelated word would
        // satisfy a bare existence check while telling the user nothing about what pressing it does.
        composeRule.onNodeWithContentDescription(strings.dbPassphraseShowToggle).assertIsDisplayed()
        // And exactly one such control - a second copy would be the same defect in a different place.
        composeRule.onAllNodesWithContentDescription(strings.dbPassphraseShowToggle).assertCountEquals(1)
    }

    @Test
    fun `the reveal toggle swaps to the hide label and the field really changes`() {
        renderDialog()

        composeRule.onNodeWithContentDescription(strings.dbPassphraseShowToggle).performClick()
        composeRule.waitForIdle()

        // The swap is the point: a fixed label would leave the control announcing the action it just
        // performed. Counted in both directions so a control that kept BOTH labels cannot pass either.
        composeRule.onAllNodesWithContentDescription(strings.dbPassphraseShowToggle).assertCountEquals(0)
        composeRule.onNodeWithContentDescription(strings.dbPassphraseHideToggle).assertIsDisplayed()

        // ...and the label is not all that changed. This is why the assertions are on the description rather
        // than on the masked text: Compose publishes an editable field's value through `EditableText` in both
        // states, and the `Text` semantics property stays null in BOTH - so `assertTextEquals`, which requires
        // `Text` AND `EditableText` to agree, can never pass here no matter what the user sees. Reading the
        // editable value is what distinguishes a toggle that is merely relabelled from one that still works:
        // a relabelled-but-inert control leaves the field masked, and a masked field is exactly the state this
        // assertion would have to fail on.
        composeRule.onAllNodes(hasSetTextAction())[0].assertTextContains(PROBE_VALUE)
    }

    @Test
    fun `a reveal toggle beside an unconfirmed passphrase is still named`() {
        // The dialog's confirm button greys out while verification runs; a control that loses its name when
        // the rest of the dialog stops being actionable is exactly when a screen-reader user needs to know
        // what is on screen. The trailing icon itself stays enabled here, so this asserts the honest thing:
        // the control remains PRESENT and NAMED, in every state this render path can reach.
        //
        // The disabled-confirm state is NOT reachable through this path: the confirm `Button` is constructed
        // with no `enabled` argument at all, so it is enabled in every state the dialog can be rendered in
        // and no input to this fixture can produce a greyed one. There is therefore no not-enabled assertion
        // to make here - do not add one without a production change that makes the state reachable.
        // Rendered with NO passphrase typed: this is the state a user reaches the dialog in, and the
        // control's name must not depend on the field being filled.
        renderDialog(passphraseTyped = false)

        composeRule.onNodeWithContentDescription(strings.dbPassphraseShowToggle).assertIsEnabled()
        composeRule
            .onAllNodesWithContentDescription(strings.dbPassphraseShowToggle)
            .assertCountEquals(1)
    }

    /** The exact [UiStrings] value this fixture typed in; asserted so a relabelled-but-inert control fails. */
    private companion object {
        const val PROBE_VALUE = "correct horse battery"
    }

    private fun renderDialog(passphraseTyped: Boolean = true) {
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
                SettingsDatabaseSecuritySection(
                    uiState = settingsState(),
                    onUpdateUnlockTimeout = {},
                    onDisableBiometricEnabled = {},
                    onEnableBiometricRequested = { _, onComplete -> onComplete(true) },
                    biometricActions = fakeActions(),
                )
            }
        }
        // The reveal toggle lives inside the dialog, which only exists once the enable path is taken, so the
        // section is put through the same click a user makes.
        composeRule.onAllNodes(isToggleable())[0].performClick()
        composeRule.onAllNodesWithText(strings.dbPassphraseUnlockBiometricEnableAction).assertCountEquals(1)
        if (passphraseTyped) {
            composeRule.onAllNodes(hasSetTextAction())[0].performTextReplacement(PROBE_VALUE)
        }
    }

    private fun settingsState(): SettingsUiState =
        SettingsUiState(
            currentDatabaseSchemaVersion = 1,
            minimumSupportedSchemaVersion = 1,
            appVersion = "0.0.0",
            buildNumber = 1,
            updateChannel = UpdateChannel.STABLE,
            unlockSessionTimeoutMinutes = 10,
        )

    /**
     * An AVAILABLE sensor, because the dialog is unreachable without one and a fake that contradicts the
     * interaction under test is the fixture bug. Only the enable verification is interesting here and it
     * succeeds, so the dialog stays open across the reveal.
     */
    private fun fakeActions(): DatabaseSecurityBiometricActions =
        object : DatabaseSecurityBiometricActions {
            override val availability: BiometricAvailability = BiometricAvailability.Available

            override val canRequestEnable: Boolean = true

            override val enrollmentAction: @Composable () -> Unit = @Composable {
                TextButton(onClick = {}) { Text("Set up biometric on device") }
            }

            override fun requestEnable(
                passphrase: String,
                onComplete: (Boolean) -> Unit,
            ) {
                onComplete(true)
            }
        }
}
