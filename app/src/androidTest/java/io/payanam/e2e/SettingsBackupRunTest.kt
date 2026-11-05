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
 * The Settings Auto-Backup card's manual backup: "Run Backup Now".
 *
 * **What was uncovered.** [SettingsTogglesTest] covers the enable switch and the interval dropdown, and
 * asserts the disabled hint — but never presses "Run Backup Now". A backup action that silently does
 * nothing would look identical to one that writes a file, because the only on-screen evidence is the
 * "Last backup: …" line, which nothing asserted.
 *
 * **The journey.** Enable Auto-Backup (the run-now control exists only while it is on), press "Run Backup
 * Now", and require the card's own "Last backup: …" line to appear — the app's confirmation that a backup
 * was written. The line is formatted with a timestamp, so it is matched by its own prefix.
 *
 * Every label is the app's own copy via [E2eLabels].
 *
 * NOT in scope, with reasons (coverage matrix §12): biometric unlock (hardware), Delete All Data (restarts
 * the process and kills the run), and Export/Import (the SAF picker is system UI; the import pipeline is
 * covered at the ViewModel seam by `ImportSeamTest`).
 */
@RunWith(AndroidJUnit4::class)
class SettingsBackupRunTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-SETTINGS-BACKUP")

    /** The app's own copy in the app's current language. */
    private val l: E2eLabels.Labels get() = h.labels

    /** The device sleeps on a short timeout mid-build; a stopped Activity has no Compose tree. */
    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun runBackupNowWritesABackup() {
        FreshSetup(h).runIfNeeded()

        h.goToTab(l.settings)
        h.scrollTo(l.ui.settingsAutoBackupTitle)
        h.click(l.ui.settingsAutoBackupTitle)
        h.capture("auto-backup-card")

        // The run-now control exists only while Auto-Backup is enabled.
        h.setChecked(l.ui.settingsAutoBackupEnable, true)
        h.scrollTo(l.ui.settingsActionRunBackupNow)
        h.click(l.ui.settingsActionRunBackupNow)

        // The card's own confirmation: "Last backup: <timestamp>", matched by its prefix.
        h.assertVisibleSubstring(l.ui.settingsAutoBackupLastRun.substringBefore('%'))
        h.log("run-backup-now=done")
    }
}
