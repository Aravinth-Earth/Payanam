//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.payanam.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * External (widget) navigation commands — the flows the unified-log lines prove end to end.
 *
 * Part 1 — quick-start from a first-run cold start: the command arrives while the app is still on
 * the onboarding gates, so it must stay pending until the shell is ready and then be retried. The
 * app must end on Time with the start-tracking dialog opened *from the command* — never dropped on
 * the floor inside the preferences-loading window.
 *
 * Part 2 — a disabled module is respected: with the Time tab switched off (manual override), the
 * command must NOT open Time; it is dropped with a warning instead of bypassing the user's choice.
 *
 * REQUIRES A FRESH INSTALL — part 1 depends on the first-run gates. Run through
 * `build-tools/scripts/run-inprocess-tests.ps1` with `-KeepInstalled $false`, one class per
 * invocation like the rest of the fresh-install tier.
 */
@RunWith(AndroidJUnit4::class)
class WidgetCommandTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E")

    /** The device sleeps on a short timeout mid-build; a stopped Activity has no Compose tree. */
    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun widgetQuickStartAndDisabledModule() {
        // ── part 1: the command arrives mid-first-run and must survive until ready ─────────────
        fireWidgetQuickStart()

        val setup = FreshSetup(h)
        setup.run()

        h.assertVisible("Set up your life dimensions")
        h.click("Verify and proceed")

        // The retried command must land on Time and open the start dialog from the command.
        h.assertVisible("Start Tracking")
        h.assertVisible("Life Dimension")
        assertLogContains("Handled external navigation command")
        assertLogContains("Opened start tracking dialog from external command")
        h.log("quickStartFromWidget=ok")
        h.log("deferredSeen=" + readAppLog().contains("deferred until the navigation graph is ready"))

        // Dismiss the dialog so the bottom navigation is tappable again.
        h.back()
        h.waitForGone("Life Dimension")

        // ── part 2: a disabled Time module must not be opened by the command ───────────────────
        h.clickNav("Settings")
        h.scrollTo("Focus Mode")
        h.click("Focus Mode")
        h.scrollTo("Tab Visibility")
        h.setChecked("Time", false)

        fireWidgetQuickStart()
        assertLogContains("External navigation command blocked")
        // Still on Settings: the command was dropped, not navigated.
        h.assertVisible("Tab Visibility")
        h.log("disabledModuleRespected=ok")

        // Restore for idempotence.
        h.setChecked("Time", true)
    }

    /**
     * Delivers the same intent the home-screen widget sends.
     *
     * The widget's intent carries `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP`, which —
     * with the app already on top — tears down and recreates this activity. An ActivityScenario
     * cannot host its activity being rebuilt underneath it, and both delivery routes funnel into
     * `MainActivity.handleExternalNavigationIntent`, so the live instance is notified directly via
     * the same callback the system would use. Only the delivery courier differs, not the handling.
     *
     * The intent is filter-equal to the launcher intent the scenario started with (action,
     * category, component — extras and flags are not part of `Intent.filterEquals`):
     * `onNewIntent` calls `setIntent`, and an activity whose stored intent stops matching the
     * scenario's would have all its later lifecycle events ignored — including the DESTROYED
     * event teardown waits for.
     */
    private fun fireWidgetQuickStart() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(rule.activity, MainActivity::class.java)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_NAVIGATE_TO, MainActivity.NAV_TARGET_TIME)
            putExtra(MainActivity.EXTRA_NAV_SOURCE, "e2e")
            putExtra(MainActivity.EXTRA_OPEN_TIME_QUICK_START, true)
        }
        val onNewIntent = MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
        onNewIntent.isAccessible = true
        rule.activityRule.scenario.onActivity { activity -> onNewIntent.invoke(activity, intent) }
    }

    /** Waits until the app's own log (newest session file) contains [marker]. */
    private fun assertLogContains(marker: String, timeoutMs: Long = 30_000) {
        rule.waitUntil(timeoutMs) { readAppLog().contains(marker) }
    }

    private fun readAppLog(): String {
        val dir = File(rule.activity.filesDir, "logs")
        val newest = dir.listFiles()?.maxByOrNull { it.lastModified() } ?: return ""
        return runCatching { newest.readText() }.getOrDefault("")
    }
}
