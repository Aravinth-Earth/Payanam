//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.payanam.MainActivity
import io.payanam.core.ui.text.formatArgs
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings-surface toggle coverage: every switch, dropdown and slider the Settings screen offers,
 * exercised through the app's own rendered state rather than "a tap happened".
 *
 * Same rules as the rest of the tier: behaviour-driven only (no mocks, no internal state, no DB
 * manipulation), zero coordinate taps, every action once, sequential navigation. REQUIRES A FRESH
 * INSTALL — run through `build-tools/scripts/run-inprocess-tests.ps1` with `-KeepInstalled $false`.
 *
 * Known gaps: the biometric half of the Security section is not exercised (it needs an OS identity
 * check the test device may not have), and the scoring-config save is asserted as "the change
 * registered and the screen returned", not as a persisted value re-read on a later launch.
 */
@RunWith(AndroidJUnit4::class)
class SettingsTogglesTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-SETTINGS")

    /** The app's own copy in the app's current language. */
    private val l: E2eLabels.Labels get() = h.labels

    /** The device sleeps on a short timeout mid-build; a stopped Activity has no Compose tree. */
    @Before
    fun keepScreenOn() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun settingsToggles() {
        FreshSetup(h).runIfNeeded()

        h.clickNav(l.settings)
        h.assertVisible(l.ui.settingsAppearanceTitle)

        debugLogging()
        autoBackup()
        autoTrackHabitTime()
        unlockTimeout()
        scoringConfig()
    }

    /**
     * The Debug card's logging switch: read the state, flip it on, assert the enabled hint, flip it
     * back off and assert the disabled hint — the hint is the app's own rendered confirmation that
     * the preference took.
     */
    private fun debugLogging() {
        h.scrollTo(l.debug)
        h.click(l.debug)

        val toggle = l.ui.settingsDebugEnable
        // The STARTING state is deliberately not asserted. This class shares one instrumentation invocation
        // with the rest of the feature journeys, so an earlier class can have left the switch either way -
        // and the app's own default for a debug build is not what this journey is about. What IS asserted is
        // that the switch FLIPS and that the app's own hint follows it, which is the behaviour.
        h.setChecked(toggle, true)
        check(h.isChecked(toggle) == true) { "debug logging switch did not turn on" }
        h.assertVisible(l.ui.settingsDebugEnabledHint)
        h.setChecked(toggle, false)
        check(h.isChecked(toggle) == false) { "debug logging switch did not turn back off" }
        h.assertVisible(l.ui.settingsDebugDisabledHint)
        h.log("debugLogging=done")
    }

    /**
     * The Auto-Backup card: enable it, move the interval to a different option and assert the
     * anchor shows the new one, turn on rotation and set the count (the hint re-renders with the
     * number that was persisted), then disable auto-backup again and assert the conditional rows
     * are gone.
     */
    private fun autoBackup() {
        h.scrollTo(l.ui.settingsAutoBackupTitle)
        h.click(l.ui.settingsAutoBackupTitle)

        val enable = l.ui.settingsAutoBackupEnable
        h.setChecked(enable, true)
        h.assertVisible(l.ui.settingsAutoBackupEnabledHint)

        // ── interval dropdown: pick whichever option the anchor is NOT showing ──
        h.scrollTo(l.ui.settingsAutoBackupInterval)
        val intervalLabels =
            listOf(
                l.ui.settingsOptionBackup15Min,
                l.ui.settingsOptionBackup30Min,
                l.ui.settingsOptionBackup1Hour,
                l.ui.settingsOptionBackup2Hours,
                l.ui.settingsOptionBackup6Hours,
                l.ui.settingsOptionBackup12Hours,
                l.ui.settingsOptionBackupDaily,
            )
        val currentInterval = intervalLabels.firstOrNull { h.isOnScreen(it) }
        check(currentInterval != null) {
            "the backup-interval anchor shows none of the known interval labels"
        }
        val targetInterval = intervalLabels.first { it != currentInterval }
        h.clickTag("settings_backup_interval")
        h.click(targetInterval)
        h.assertVisible(targetInterval)

        // ── rotation: the count field only exists while rotation is on ──
        h.setChecked(l.ui.settingsBackupRotationEnable, true)
        h.scrollTo(l.ui.settingsBackupRotationCount)
        rule.onAllNodesWithTag("settings_backup_rotation_count").onFirst()
            .performTextReplacement(ROTATION_COUNT.toString())
        h.assertVisible(l.ui.settingsBackupRotationEnabledHint.formatArgs(ROTATION_COUNT))

        // ── disable at the end: the whole conditional block must leave the tree ──
        h.setChecked(enable, false)
        h.assertNotVisible(l.ui.settingsBackupRotationEnable)
        h.log("autoBackup=done")
    }

    /**
     * The Auto-Track Habit Time card's global switch: flip it on and assert the per-dimension
     * controls appear (they are rendered only while the global switch is on), then flip it back.
     */
    private fun autoTrackHabitTime() {
        h.scrollTo(l.ui.settingsAutoTrackHabitTimeTitle)
        h.click(l.ui.settingsAutoTrackHabitTimeTitle)

        val global = l.ui.settingsAutoTrackHabitTimeGlobal
        h.setChecked(global, true)
        check(h.isChecked(global) == true) { "global auto-track switch did not turn on" }
        h.assertVisible(l.ui.settingsAutoTrackPerDimension)
        h.setChecked(global, false)
        check(h.isChecked(global) == false) { "global auto-track switch did not turn back off" }
        h.log("autoTrackHabitTime=done")
    }

    /**
     * The Security card's unlock-timeout dropdown: pick a different rung of the ladder and assert
     * the anchor shows the new label. The label is computed here from the same copy table the app
     * formats with, so the assertion holds in whatever language the app is rendering.
     */
    private fun unlockTimeout() {
        h.scrollTo(l.ui.settingsSecurityTitle)
        h.click(l.ui.settingsSecurityTitle)
        h.scrollTo(l.ui.settingsDbUnlockTimeoutTitle)

        val labels = TIMEOUT_LADDER.map { timeoutLabel(it) }
        val current = labels.firstOrNull { h.isOnScreen(it) }
        check(current != null) { "the unlock-timeout anchor shows none of the known timeout labels" }
        val target = labels.first { it != current }
        h.clickTag("settings_unlock_timeout")
        h.click(target)
        h.assertVisible(target)
        h.log("unlockTimeout=done")
    }

    /**
     * The Scoring card: open the scoring-configuration screen, move the first weight slider, and
     * assert the change registered — Save is enabled only while there are unsaved changes — then
     * tap Save and assert the app is back on the Settings screen.
     */
    private fun scoringConfig() {
        h.scrollTo(l.scoringConfiguration)
        h.click(l.scoringConfiguration)
        h.click(l.ui.settingsActionConfigureScoring)
        h.assertVisible(l.ui.locFactorWeights)

        // The first slider in tree order is the dimension-weight slider (range 0.1..3.0).
        rule.onAllNodesWithTag("scoring_config_slider").onFirst()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.9f) }

        // The value text is locale-formatted (String.format), so the locale-independent state
        // assertion is the Save button becoming enabled — it is gated on hasChanges.
        val saveEnabled = runCatching { rule.waitUntil(15_000) { h.isEnabled(l.ui.locSave) } }.isSuccess
        check(saveEnabled) { "Save stayed disabled after a weight change — the change did not register" }
        h.click(l.ui.locSave)
        h.assertVisible(l.ui.settingsDebugTitle)
        h.log("scoringConfig=done")
    }

    /**
     * Formats a timeout in minutes the way the app's own label formatter does, from the same copy
     * table: plain minutes below an hour, whole hours on the hour, hours with the remainder above.
     */
    private fun timeoutLabel(minutes: Int): String {
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0 -> l.ui.settingsDbUnlockTimeoutValueMinutes.formatArgs(minutes)
            rest == 0 -> l.ui.settingsDbUnlockTimeoutValueHours.formatArgs(hours)
            else -> l.ui.settingsDbUnlockTimeoutValueHoursMinutes.formatArgs(hours, rest)
        }
    }

    private companion object {
        /**
         * The unlock-timeout ladder, in minutes — mirrors the app's own option list in the
         * database-security section, so the test can offer "a different value" without hardcoding
         * which rung the device is currently on.
         */
        val TIMEOUT_LADDER = listOf(1, 2, 5, 10, 15, 30, 60, 120, 240)

        /** The rotation count this test types into the field — data the test chose, not app copy. */
        const val ROTATION_COUNT = 25
    }
}
