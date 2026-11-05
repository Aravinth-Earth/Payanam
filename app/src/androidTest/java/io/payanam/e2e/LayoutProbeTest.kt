//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.payanam.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Discovery probe (Android side): walks the seven top-level tabs, captures a PNG per tab and LOGS a
 * normalised geometry dump per tab, so the frames and geometry can be compared with the desktop probe
 * ([io.payanam.desktop.DesktopLayoutProbeTest]) on the host.
 *
 * The device is the reference configuration for the comparison — it is the real phone, at the owner's own
 * font size (0.8) and screen zoom (density 420). The geometry is normalised to the Compose root, so the
 * numbers are comparable with the desktop's normalised dump despite the different density.
 *
 * Geometry goes to stdout/logcat (AGP writes it to a per-test logcat file on the host) rather than to a file,
 * because the app's external files dir needs MTP on Android 11+; the PNGs still go to that dir for `adb pull`.
 *
 * Deep screens a tab sweep cannot reach — the add-task editor and its recurrence picker, the expanded
 * appearance section, the note editor — are captured the same way after the tab loop, because the
 * tab sweep stops at the seven top-level destinations.
 *
 * This is an instrument, not a gate: it asserts only that the setup reached the app.
 */
@RunWith(AndroidJUnit4::class)
class LayoutProbeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-LAYOUT-PROBE")

    private val l: E2eLabels.Labels get() = h.labels

    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun probeTopLevelScreens() {
        FreshSetup(h).runIfNeeded()

        val tabs = listOf(
            "tasks" to l.tasks,
            "habits" to l.habits,
            "time" to l.time,
            "journal" to l.journal,
            "notes" to l.notes,
            "lenses" to l.lenses,
            "settings" to l.settings,
        )
        tabs.forEach { (route, label) ->
            h.goToTab(label)
            h.awaitIdle()
            h.capture("probe-$route")
            h.log("geometry route=$route begin")
            geometryDump().forEach { h.log("geometry $it") }
            h.log("geometry route=$route end")
        }

        // Deep screens a tab sweep cannot reach: the editors and dialogs a tab's FAB or section
        // header opens, captured in the same shape so the frames compare with the desktop probe.
        h.log("layout-probe=deep-begin")

        // add-task: the Tasks FAB opens the editor as its own screen. It stays open for the
        // recurrence capture below, which starts from it.
        h.goToTab(l.tasks)
        h.awaitIdle()
        if (h.exists(l.ui.locAddTask)) {
            h.click(l.ui.locAddTask)
            h.awaitIdle()
            h.capture("probe-add-task")
            h.log("geometry route=add-task begin")
            geometryDump().forEach { h.log("geometry $it") }
            h.log("geometry route=add-task end")
        } else {
            h.log("geometry route=add-task skipped (add-task FAB not found)")
        }

        // add-task-recurrence: the editor's Recurring segmented option opens the picker dialog.
        if (h.exists(l.ui.locRecurring)) {
            h.click(l.ui.locRecurring)
            h.awaitIdle()
            h.capture("probe-add-task-recurrence")
            h.log("geometry route=add-task-recurrence begin")
            geometryDump().forEach { h.log("geometry $it") }
            h.log("geometry route=add-task-recurrence end")
            // Dismiss the picker, then the editor, so the next capture starts from a tab.
            h.back()
            h.awaitIdle()
            h.back()
            h.awaitIdle()
        } else {
            h.log("geometry route=add-task-recurrence skipped (recurring option not found)")
            // The editor may still be open (recurrence is feature-flagged); leave it for a tab.
            if (h.exists(l.ui.locBack)) {
                h.click(l.ui.locBack)
                h.awaitIdle()
            }
        }

        // settings-appearance: the Appearance card header expands its section in place.
        h.goToTab(l.settings)
        h.awaitIdle()
        if (h.exists(l.ui.settingsAppearanceTitle)) {
            h.click(l.ui.settingsAppearanceTitle)
            h.awaitIdle()
            h.capture("probe-settings-appearance")
            h.log("geometry route=settings-appearance begin")
            geometryDump().forEach { h.log("geometry $it") }
            h.log("geometry route=settings-appearance end")
        } else {
            h.log("geometry route=settings-appearance skipped (appearance header not found)")
        }

        // notes-editor: the Notes add-note FAB opens the editor dialog.
        h.goToTab(l.notes)
        h.awaitIdle()
        if (h.exists(l.addNote)) {
            h.click(l.addNote)
            h.awaitIdle()
            h.capture("probe-notes-editor")
            h.log("geometry route=notes-editor begin")
            geometryDump().forEach { h.log("geometry $it") }
            h.log("geometry route=notes-editor end")
            // Dismiss the dialog, back to the Notes tab.
            h.back()
            h.awaitIdle()
        } else {
            h.log("geometry route=notes-editor skipped (add-note FAB not found)")
        }

        h.log("layout-probe=done")
    }

    /** Every labelled node's bounds normalised to the Compose root, in tree order. */
    private fun geometryDump(): List<String> {
        // A dialog owns a SECOND root, and `onRoot()` refuses when there is more than one. The first root
        // is the app window (the dialog window sorts after it), and it is the one whose size normalises the
        // dump for every screen, dialog or not.
        val root = rule.onAllNodes(isRoot()).onFirst().fetchSemanticsNode().boundsInRoot
        val w = root.width
        val hgt = root.height
        return rule.onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { node ->
                val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
                val cd = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
                val tag = node.config.getOrNull(SemanticsProperties.TestTag)
                val label = text ?: cd ?: tag
                if (label.isNullOrBlank()) return@mapNotNull null
                val rect = node.boundsInRoot
                if (rect.width <= 0f || rect.height <= 0f) return@mapNotNull null
                val click = node.config.contains(SemanticsActions.OnClick)
                "%-55s click=%-5s L=%.3f T=%.3f R=%.3f B=%.3f w=%.3f h=%.3f".format(
                    label.replace("\n", " ").take(55),
                    click.toString(),
                    rect.left / w,
                    rect.top / hgt,
                    rect.right / w,
                    rect.bottom / hgt,
                    rect.width / w,
                    rect.height / hgt,
                )
            }
    }
}
