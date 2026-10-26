//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.settings

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.presentation.settings.SettingsUiState
import io.payanam.shared.security.BiometricAvailability
import io.payanam.shared.security.BiometricUnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The shared database-security settings section, rendered for real on the JVM target.
 *
 * What this move can silently break is the SECURITY CLAIM, so that is what these cases assert rather than
 * a screenshot of copy. Two things moved: the copy (15 keys, all already in `:core:strings` with EN+TA
 * parity, now read from [LocalUiStrings] instead of `stringResource`), and the biometric control's
 * platform half (a `BiometricManager` check, a `FragmentActivity`-hosted verification and an enrollment
 * `Intent` chain, now behind [DatabaseSecurityBiometricActions]).
 *
 * The decision these cases pin down is that a null [DatabaseSecurityBiometricActions] renders NO
 * biometric control at all. A switch that saved a preference on a platform where nothing will ever verify
 * the user would claim a security property this platform lacks, so the count assertions ARE the
 * assertions: zero toggles and zero biometric copy, while the timeout rows still render - and the same
 * fake proves the marker is findable, because it finds it in every other case, so the zero is an absence
 * and not a string nobody can render.
 *
 * The WRITES are asserted as CALLS: binding them is the caller's job (`:app` binds them to
 * `SettingsViewModel`, the desktop to its own writer), so the shared contract is that the section reports
 * which write the user asked for and leaves storage alone. The timeout is asserted through the SAME list
 * the menu draws, because a row that displays one preference and writes another is the defect a
 * nine-callback shape would invite.
 */
class SettingsDatabaseSecuritySectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val strings: UiStrings = UiStrings.of(UiLanguage.ENGLISH)

    @Test
    fun `a platform with no biometric control renders no biometric row at all`() {
        render(unlockTimeoutMinutes = 10) { section(recorder = Recorder(), biometricActions = null) }

        // The timeout half still renders: this is an omission of the biometric control, not of the section.
        composeRule.onAllNodesWithText(strings.settingsDbUnlockTimeoutTitle).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsDbUnlockTimeoutHelp).assertCountEquals(1)
        // And nothing that could save a preference nothing on this platform can ever verify.
        composeRule.onAllNodes(isToggleable()).assertCountEquals(0)
        composeRule.onAllNodesWithText(strings.dbPassphraseUnlockBiometricTitle).assertCountEquals(0)
        composeRule.onAllNodesWithText(strings.settingsDbBiometricUnlockHelp).assertCountEquals(0)
        composeRule.onAllNodesWithText(strings.settingsDbBiometricUnlockUnavailableHelp).assertCountEquals(0)
        composeRule.onAllNodesWithText(ENROLLMENT_MARKER).assertCountEquals(0)
        // The enable dialog is unreachable, and its title must not leak into the composition.
        composeRule.onAllNodesWithText(strings.dbPassphraseUnlockBiometricEnableAction).assertCountEquals(0)
    }

    @Test
    fun `an enrolled sensor draws the enabled row with the available copy`() {
        val recorder = Recorder()
        render(unlockTimeoutMinutes = 10) {
            section(recorder = recorder, biometricActions = recorder.fake(BiometricAvailability.Available))
        }

        composeRule.onAllNodesWithText(strings.dbPassphraseUnlockBiometricTitle).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsDbBiometricUnlockHelp).assertCountEquals(1)
        // An enrolled sensor has nothing to enrol, so the platform's setup affordance stays hidden.
        composeRule.onAllNodesWithText(strings.settingsDbBiometricUnlockUnavailableHelp).assertCountEquals(0)
        composeRule.onAllNodesWithText(ENROLLMENT_MARKER).assertCountEquals(0)
        composeRule.onAllNodes(isToggleable()).assertCountEquals(1)
    }

    @Test
    fun `an unsatisfied sensor disables the row and shows the platform setup affordance`() {
        val recorder = Recorder()
        render(unlockTimeoutMinutes = 10) {
            section(
                recorder = recorder,
                biometricActions =
                    recorder.fake(BiometricAvailability.Unavailable(BiometricUnavailableReason.NOT_ENROLLED)),
            )
        }

        composeRule.onAllNodesWithText(strings.settingsDbBiometricUnlockUnavailableHelp).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsDbBiometricUnlockHelp).assertCountEquals(0)
        composeRule.onAllNodesWithText(ENROLLMENT_MARKER).assertCountEquals(1)
        composeRule.onAllNodes(isToggleable())[0].assertIsNotEnabled()

        composeRule.onNodeWithText(ENROLLMENT_MARKER).performClick()
        assertEquals(1, recorder.enrollmentTaps)
    }

    @Test
    fun `a platform that cannot host a prompt never opens the enable dialog`() {
        val recorder = Recorder()
        render(unlockTimeoutMinutes = 10) {
            section(
                recorder = recorder,
                biometricActions = recorder.fake(BiometricAvailability.Available, canRequestEnable = false),
            )
        }

        composeRule.onAllNodes(isToggleable())[0].performClick()

        // The row is enabled because the sensor IS usable - but the enable path needs a host activity this
        // session does not have, so nothing opens and no verification is requested.
        composeRule.onAllNodesWithText(strings.dbPassphraseUnlockBiometricEnableAction).assertCountEquals(0)
        assertEquals(0, recorder.requestedPassphrases.size)
    }

    @Test
    fun `a refusal keeps the dialog open and its error visible`() {
        val recorder = Recorder()
        render(unlockTimeoutMinutes = 10) {
            section(recorder = recorder, biometricActions = recorder.fake(verify = { false }))
        }

        openEnableDialog()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextReplacement("not the passphrase")
        composeRule.onNodeWithText(strings.settingsBiometricEnablePassphraseConfirm).performClick()

        assertEquals(listOf("not the passphrase"), recorder.requestedPassphrases)
        composeRule.onAllNodesWithText(strings.settingsBiometricEnablePassphraseError).assertCountEquals(1)
        // A refusal must NOT read as a closed dialog: the passphrase is still there to correct.
        composeRule.onAllNodesWithText(strings.dbPassphraseUnlockBiometricEnableAction).assertCountEquals(1)
        assertEquals(0, recorder.disableCalls)
    }

    @Test
    fun `a verified passphrase closes the dialog`() {
        val recorder = Recorder()
        render(unlockTimeoutMinutes = 10) {
            section(recorder = recorder, biometricActions = recorder.fake(verify = { true }))
        }

        openEnableDialog()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextReplacement("the passphrase")
        composeRule.onNodeWithText(strings.settingsBiometricEnablePassphraseConfirm).performClick()

        assertEquals(listOf("the passphrase"), recorder.requestedPassphrases)
        composeRule.onAllNodesWithText(strings.dbPassphraseUnlockBiometricEnableAction).assertCountEquals(0)
        composeRule.onAllNodesWithText(strings.settingsBiometricEnablePassphraseError).assertCountEquals(0)
        assertEquals(0, recorder.disableCalls)
    }

    @Test
    fun `turning it back off is its own write`() {
        val recorder = Recorder()
        render(unlockTimeoutMinutes = 10, biometricUnlockEnabled = true) {
            section(recorder = recorder, biometricActions = recorder.fake())
        }

        composeRule.onAllNodes(isToggleable())[0].performClick()

        assertEquals(1, recorder.disableCalls)
        // A teardown is not a verification: nothing may be sent to the platform to prove the user.
        assertEquals(0, recorder.requestedPassphrases.size)
    }

    @Test
    fun `a blank passphrase never reaches the platform`() {
        val recorder = Recorder()
        render(unlockTimeoutMinutes = 10) {
            section(recorder = recorder, biometricActions = recorder.fake(verify = { true }))
        }

        openEnableDialog()
        composeRule.onNodeWithText(strings.settingsBiometricEnablePassphraseConfirm).performClick()

        assertEquals(0, recorder.requestedPassphrases.size)
        composeRule.onAllNodesWithText(strings.settingsBiometricEnablePassphraseError).assertCountEquals(1)
    }

    @Test
    fun `the menu offers exactly the choices the shared list names`() {
        // 7 is deliberately NOT one of UNLOCK_TIMEOUT_CHOICES, so the field's own label cannot collide with
        // any menu row and each of the nine assertions below can only be satisfied by the menu itself.
        render(unlockTimeoutMinutes = 7) { section(recorder = Recorder(), biometricActions = null) }

        composeRule.onAllNodesWithText(formatUnlockTimeoutLabel(7, strings)).assertCountEquals(1)
        composeRule.onNodeWithText(formatUnlockTimeoutLabel(7, strings)).performClick()
        UNLOCK_TIMEOUT_CHOICES.forEach { minutes ->
            composeRule.onAllNodesWithText(formatUnlockTimeoutLabel(minutes, strings)).assertCountEquals(1)
        }
    }

    @Test
    fun `choosing a row writes that row's own value`() {
        val recorder = Recorder()
        render(unlockTimeoutMinutes = 7) { section(recorder = recorder, biometricActions = null) }

        composeRule.onNodeWithText(formatUnlockTimeoutLabel(7, strings)).performClick()
        composeRule.onNodeWithText(formatUnlockTimeoutLabel(120, strings)).performClick()

        assertEquals(listOf(120), recorder.timeoutWrites)
    }

    @Test
    fun `the displayed timeout formats through the one function the write reads`() {
        // The branches themselves: plain minutes, whole hours, hours with the remainder.
        assertEquals("45 min", formatUnlockTimeoutLabel(45, strings))
        assertEquals("2 hr", formatUnlockTimeoutLabel(120, strings))
        assertEquals("2 hr 30 min", formatUnlockTimeoutLabel(150, strings))

        // And the field itself, for a value the menu does not offer and for one it does.
        listOf(7, 45, 60, 90, 120, 240).forEach { minutes ->
            render(unlockTimeoutMinutes = minutes) { section(recorder = Recorder(), biometricActions = null) }
            composeRule
                .onAllNodesWithText(formatUnlockTimeoutLabel(minutes, strings))
                .assertCountEquals(1)
        }
    }

    @Test
    fun `the copy reads the same in Tamil`() {
        val tamil = UiStrings.of(UiLanguage.TAMIL)
        val recorder = Recorder()
        render(unlockTimeoutMinutes = 10, strings = tamil) {
            section(recorder = recorder, biometricActions = recorder.fake(BiometricAvailability.Available), strings = tamil)
        }

        composeRule.onAllNodesWithText(tamil.settingsDbUnlockTimeoutTitle).assertCountEquals(1)
        composeRule.onAllNodesWithText(tamil.dbPassphraseUnlockBiometricTitle).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsDbUnlockTimeoutTitle).assertCountEquals(0)
        assertTrue(tamil.settingsDbBiometricUnlockHelp != strings.settingsDbBiometricUnlockHelp)
    }

    private fun openEnableDialog() {
        composeRule.onAllNodes(isToggleable())[0].performClick()
        composeRule.onAllNodesWithText(strings.dbPassphraseUnlockBiometricEnableAction).assertCountEquals(1)
    }

    @Composable
    private fun section(
        recorder: Recorder,
        biometricActions: DatabaseSecurityBiometricActions?,
        strings: UiStrings = this.strings,
    ) {
        SettingsDatabaseSecuritySection(
            uiState = settingsState,
            onUpdateUnlockTimeout = { recorder.timeoutWrites += it },
            onDisableBiometricEnabled = { recorder.disableCalls++ },
            onEnableBiometricRequested = { passphrase, onComplete ->
                biometricActions?.requestEnable(passphrase, onComplete) ?: onComplete(false)
            },
            biometricActions = biometricActions,
        )
    }

    private fun render(
        unlockTimeoutMinutes: Int,
        biometricUnlockEnabled: Boolean = false,
        strings: UiStrings = this.strings,
        content: @Composable () -> Unit,
    ) {
        settingsState =
            SettingsUiState(
                currentDatabaseSchemaVersion = 1,
                minimumSupportedSchemaVersion = 1,
                appVersion = "0.0.0",
                buildNumber = 1,
                unlockSessionTimeoutMinutes = unlockTimeoutMinutes,
                biometricUnlockEnabled = biometricUnlockEnabled,
            )
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings, content = content)
        }
    }

    /** A platform half that records what the section asked for, and nothing more. */
    private class Recorder {
        val timeoutWrites = mutableListOf<Int>()
        val requestedPassphrases = mutableListOf<String>()
        var enrollmentTaps = 0
        var disableCalls = 0

        /**
         * Defaults to an AVAILABLE sensor, because these fakes exist to be interacted with and a disabled
         * switch cannot be clicked. The unsatisfied case passes its own reason explicitly - a fake whose
         * default contradicts the interaction under test is the fixture bug this replaced.
         */
        fun fake(
            availability: BiometricAvailability = BiometricAvailability.Available,
            canRequestEnable: Boolean = true,
            verify: (String) -> Boolean = { true },
        ): DatabaseSecurityBiometricActions =
            object : DatabaseSecurityBiometricActions {
                override val availability: BiometricAvailability = availability

                override val canRequestEnable: Boolean = canRequestEnable

                override val enrollmentAction: @Composable () -> Unit = @Composable {
                    TextButton(onClick = { enrollmentTaps++ }) {
                        Text(ENROLLMENT_MARKER)
                    }
                }

                override fun requestEnable(
                    passphrase: String,
                    onComplete: (Boolean) -> Unit,
                ) {
                    requestedPassphrases += passphrase
                    onComplete(verify(passphrase))
                }
            }
    }

    private companion object {
        /** The platform's OWN setup affordance, which the shared section only places. */
        const val ENROLLMENT_MARKER = "Set up biometric on device"
    }

    private var settingsState: SettingsUiState =
        SettingsUiState(
            currentDatabaseSchemaVersion = 1,
            minimumSupportedSchemaVersion = 1,
            appVersion = "0.0.0",
            buildNumber = 1,
        )
}
