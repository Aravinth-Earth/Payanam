//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.BackupInterval
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The shared auto-backup settings section, rendered for real on the JVM target.
 *
 * This section was the last of the settings sections to hold a resource id, and the only one whose
 * behaviour was platform-bound. Both moved things are what these cases pin down.
 *
 * The COPY: every caption here was an `R.string` id resolved in `:app`, so a section that still renders can
 * draw the wrong words - and the interval labels in particular arrived through a `when (key)` table rather
 * than a property, which is the shape that silently picks the wrong branch.
 *
 * The WRITES: six of them, because they are six different operations. Enabling and changing the cadence
 * each write a preference AND reconcile a platform schedule; "Run Backup Now" writes no preference at all;
 * the two rotation rows and the dismissal each touch a different store. These assert WHICH callback fired,
 * so a single wrong one fails. `BackupScheduler`'s idempotency contract is why the caller may reconcile a
 * schedule with an interval already in force; the section never touches a scheduler itself, which is also
 * what these cases pin - nothing happens without a callback.
 */
class SettingsAutoBackupSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a disabled section draws the toggle and none of the rows beneath it`() {
        render(loaded(autoBackupEnabled = false))

        composeRule.onAllNodesWithText(strings.settingsAutoBackupEnable).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsDisabled).assertCountEquals(1)
        // Everything below the toggle is unreachable while it is off, including the button and the rotation
        // rows: a section that renders can still draw nothing, so the absence is asserted explicitly.
        composeRule.onAllNodesWithText(strings.settingsActionRunBackupNow).assertCountEquals(0)
        composeRule.onAllNodesWithText(strings.settingsBackupRotationEnable).assertCountEquals(0)
    }

    @Test
    fun `an enabled section draws each row and the selected interval as copy`() {
        render(loaded(autoBackupEnabled = true, autoBackupInterval = BackupInterval.SIX_HOURS))

        composeRule.onAllNodesWithText(strings.settingsAutoBackupEnabledHint).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsActionRunBackupNow).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsBackupRotationEnable).assertCountEquals(1)
        // The READONLY field shows the SELECTED cadence while the menu lists every option, so the selected
        // one appears once - a swapped label would render a plausible, wrong field.
        composeRule.onAllNodesWithText(strings.settingsOptionBackup6Hours).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsOptionBackup15Min).assertCountEquals(0)
    }

    @Test
    fun `choosing a cadence reports that interval and no other write`() {
        val writes = mutableListOf<String>()

        render(loaded(autoBackupEnabled = true), writes, intervalExpanded = true)

        composeRule.onNodeWithText(strings.settingsOptionBackup15Min).performClick()

        // One row, one write: collapsing the menu afterwards is hoisted UI state, not storage, so it is
        // filtered out rather than excused - a preference write hiding in there would still fail.
        assertEquals(listOf("interval:15m"), writes.filterNot { it.startsWith("expanded:") })
        assertEquals(listOf("expanded:false"), writes.filter { it.startsWith("expanded:") })
    }

    @Test
    fun `tapping the toggle reports the new value and nothing else`() {
        val writes = mutableListOf<String>()

        render(loaded(autoBackupEnabled = false), writes)

        // The label is a sibling of the switch, not part of it, so the toggle itself is the node to click.
        composeRule.onAllNodes(isToggleable()).assertCountEquals(1)
        composeRule.onAllNodes(isToggleable())[0].performClick()

        // A BOOLEAN, not a route: the section reports the row that changed, and reconciling the platform
        // schedule from it is the caller's job - the caller owns the WorkManager, not this composable.
        assertEquals(listOf("enabled:true"), writes)
    }

    @Test
    fun `run backup now is a call, not a preference write`() {
        val writes = mutableListOf<String>()

        render(loaded(autoBackupEnabled = true), writes)
        composeRule.onNodeWithText(strings.settingsActionRunBackupNow).performClick()

        assertEquals(listOf("manual"), writes)
    }

    @Test
    fun `a backup in progress disables the button and swaps its caption`() {
        val writes = mutableListOf<String>()

        render(loaded(autoBackupEnabled = true), writes, manualBackupInProgress = true)

        composeRule.onAllNodesWithText(strings.settingsManualBackupInProgress).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsActionRunBackupNow).assertCountEquals(0)
        composeRule.onAllNodesWithText(strings.settingsManualBackupInProgress)[0].assertIsNotEnabled()
        assertEquals(emptyList<String>(), writes)
    }

    @Test
    fun `the rotation rows appear only once rotation is on`() {
        render(loaded(autoBackupEnabled = true, backupRotationEnabled = false))
        composeRule.onAllNodesWithText(strings.settingsBackupRotationEnable).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsBackupRotationDisabledHint).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsBackupRotationCount).assertCountEquals(0)

        render(loaded(autoBackupEnabled = true, backupRotationEnabled = true, backupRotationCount = 50))
        // Interpolated, so the assertion is on the RENDERED copy - an uninterpolated `%1$d` would
        // otherwise satisfy a check written against the raw table value.
        composeRule
            .onAllNodesWithText("Keep last 50 backups, older ones auto-deleted")
            .assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsBackupRotationEnabledHint).assertCountEquals(0)
        composeRule.onAllNodesWithText(strings.settingsBackupRotationDisabledHint).assertCountEquals(0)
        composeRule.onAllNodesWithText(strings.settingsBackupRotationCount).assertCountEquals(1)
    }

    @Test
    fun `typing a rotation count reports the number and nothing else`() {
        val writes = mutableListOf<String>()

        render(loaded(autoBackupEnabled = true, backupRotationEnabled = true), writes)

        composeRule.onAllNodesWithText("50")[0].performTextReplacement("7")

        // The count is a NUMBER the caller validates against its own bounds; the section reports what was
        // typed, so a caller that clamped differently from the old Android path shows up here.
        assertEquals(listOf("count:7"), writes)
    }

    @Test
    fun `the last-run row interpolates the recorded time`() {
        render(loaded(autoBackupEnabled = true, autoBackupLastRun = "2026-01-02 03:04"))

        composeRule.onAllNodesWithText("Last backup: 2026-01-02 03:04").assertCountEquals(1)
        // The raw placeholder is not the copy: an uninterpolated `%1$s` is a plausible-looking row.
        composeRule.onAllNodesWithText(strings.settingsAutoBackupLastRun).assertCountEquals(0)
    }

    @Test
    fun `the enabled hint interpolates the retained count`() {
        render(loaded(autoBackupEnabled = true, backupRotationEnabled = true, backupRotationCount = 12))

        composeRule.onAllNodesWithText("Keep last 12 backups, older ones auto-deleted").assertCountEquals(1)
    }

    @Test
    fun `tamil copy is the tamil table, not the english one`() {
        val tamil = UiStrings.of(UiLanguage.TAMIL)

        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides tamil) {
                SettingsAutoBackupSection(
                    prefsState = loaded(autoBackupEnabled = true),
                    onAutoBackupEnabledChange = { },
                    onAutoBackupIntervalChange = { },
                    onManualBackupNow = { },
                    onBackupRotationEnabledChange = { },
                    onBackupRotationCountChange = { },
                    onDismissAutoBackupFailure = { },
                    manualBackupInProgress = false,
                    intervalExpanded = false,
                    onIntervalExpandedChange = { },
                )
            }
        }

        composeRule.onAllNodesWithText(tamil.settingsActionRunBackupNow).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsActionRunBackupNow).assertCountEquals(0)
    }

    private val strings: UiStrings = UiStrings.of(UiLanguage.ENGLISH)

    private fun render(
        state: AppPreferencesState,
        writes: MutableList<String> = mutableListOf(),
        manualBackupInProgress: Boolean = false,
        intervalExpanded: Boolean = false,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
                SettingsAutoBackupSection(
                    prefsState = state,
                    onAutoBackupEnabledChange = { writes.add("enabled:$it") },
                    onAutoBackupIntervalChange = { writes.add("interval:${it.key}") },
                    onManualBackupNow = { writes.add("manual") },
                    onBackupRotationEnabledChange = { writes.add("rotation:$it") },
                    onBackupRotationCountChange = { writes.add("count:$it") },
                    onDismissAutoBackupFailure = { writes.add("dismiss") },
                    manualBackupInProgress = manualBackupInProgress,
                    intervalExpanded = intervalExpanded,
                    onIntervalExpandedChange = { writes.add("expanded:$it") },
                )
            }
        }
    }

    private fun loaded(
        autoBackupEnabled: Boolean = false,
        autoBackupInterval: BackupInterval = BackupInterval.SIXTY_MIN,
        autoBackupLastRun: String? = null,
        backupRotationEnabled: Boolean = false,
        backupRotationCount: Int = 50,
    ) = AppPreferencesState(
        isLoading = false,
        autoBackupEnabled = autoBackupEnabled,
        autoBackupInterval = autoBackupInterval,
        autoBackupLastRun = autoBackupLastRun,
        backupRotationEnabled = backupRotationEnabled,
        backupRotationCount = backupRotationCount,
    )
}
