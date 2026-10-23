//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.payanam.core.ui.navigation.PayanamTab
import org.junit.Test

/**
 * The desktop's habits CRUD journey, walked in-process the way Android's `HabitsCrudTest` walks it on a
 * device.
 *
 * **The gap this closes.** The desktop proved the Habits SCREEN renders (`DesktopNavHostSharedScreenRenderTest`
 * pins the FAB and the empty state) but nothing on the desktop ever CREATED a habit through the UI, opened it,
 * or deleted it. Those are the write paths a user takes every day, and on Android an on-device class covers
 * them - so the desktop's parity claim for Habits rested on a screen-render assertion. This drives the same
 * create-open-delete spine through the real graph: the FAB opens the real `AddTaskScreen`, the typed title and
 * the Daily recurrence go through the real `AddTaskViewModel` into the real encrypted database, the habit card
 * opens the real `TaskDetailScreen`, and Delete removes it.
 *
 * **Why the production fixture.** A habit write touches `tasks` (a recurring task) and its `life_dimensions`
 * foreign key, and the add screen reads its dimension options from `LocalAppPreferences`; so this mounts over
 * [installProductionFixtureLayer] with the REAL preference state ([loadedPreferences]), exactly as the Tasks and
 * Time journeys do.
 *
 * **Why the tab is reached by the first-match form.** The harness's `goToTab` resolves its tab by
 * `onNodeWithText(tab.title(strings))`, and the Habits screen's own top-bar title carries the SAME copy
 * (`locHabits`) - so the label matches twice and the single-node lookup throws. This clicks the first match (the
 * tab control) and waits for idle, the form the Notes journey uses and Android's own click makes.
 *
 * **Why the habit card is opened through the unmerged title node.** The card is clickable as a whole, but the
 * checkmark canvas fills the right half of the row, so a tap aimed at the merged row node lands on the canvas
 * and toggles a day instead of opening the detail. The title's own (unmerged) node sits left of the canvas, so
 * tapping that node reaches the card's click handler - the same reach Android's `openHabitDetail` makes.
 *
 * **What it does NOT claim.** It does not walk the completion dialog, the checkmark edit dialog, the day-metrics
 * strip, sort/filter/search or the edit screen; those are other items' claims. This is the create-open-delete
 * spine the desktop was missing. The habit title is a data literal, never app copy.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopHabitsJourneyTest : DesktopAppJourneyHarness() {
    @Test
    fun `a habit is created with the Daily recurrence, opened in detail, and deleted`() =
        runAtTheAppWindowSize {
            installProductionFixtureLayer()
            mountShell(configuredState(), isLandingReady = true, preferences = loadedPreferences())

            // ---- 1. The Habits tab and its empty state ------------------------------------------
            onAllNodesWithText(PayanamTab.Habits.title(strings)).onFirst().performClick()
            waitForIdle()
            awaitNodeWithText(strings.locNoHabitsYet)
            frame("00-habits-empty", "the Habits screen with no habits yet", copy = strings.locNoHabitsYet)

            // ---- 2. CREATE: FAB -> AddTaskScreen -> title -> Recurring -> Daily -> Save ----------
            onNodeWithContentDescription(strings.locAddTask).performClick()
            awaitNodeWithText(strings.locSaveTask)
            // The title is the FIRST editable field on the screen, so it is addressed by its action rather
            // than by a label that a second field could also carry.
            onAllNodes(hasSetTextAction()).onFirst().performTextInput(HABIT_TITLE)
            frame("01-add-habit-title", "the Add Task screen with the habit title typed", copy = HABIT_TITLE)

            // Selecting "Recurring" opens the recurrence picker. It can open on its Custom tab, where the
            // Daily preset is not rendered, so the Preset tab is selected first (a no-op when it is already
            // selected), then the Daily preset, then the dialog is confirmed.
            onAllNodesWithText(strings.locRecurring).onFirst().performClick()
            waitForIdle()
            // The picker can open on its Custom tab, where the presets are not rendered, so the Preset mode
            // is selected first. On the desktop choosing a preset applies it and closes the picker, so the
            // OK is pressed ONLY while the picker is still up - guarded rather than assumed.
            if (exists(strings.locPreset)) onAllNodesWithText(strings.locPreset).onFirst().performClick()
            if (exists(strings.locDaily)) onAllNodesWithText(strings.locDaily).onFirst().performClick()
            frame("02-recurrence-daily", "the Add Task screen with the Daily recurrence chosen", copy = strings.locDaily)
            if (exists(strings.locOk)) onAllNodesWithText(strings.locOk).onFirst().performClick()

            // The Save button sits below a scrollable form on a 411x891 surface, so it is scrolled to before
            // it is pressed - the same reach a user makes.
            onNodeWithText(strings.locSaveTask).performScrollTo().performClick()

            // ---- 3. READ: the created habit appears in the list ---------------------------------
            awaitNodeWithText(HABIT_TITLE)
            assertLogged("AddTaskViewModel.createTask", "Task created successfully")
            frame("03-habit-created", "the Habits list carrying the created habit", copy = HABIT_TITLE)

            // ---- 4. OPEN: the habit card's title opens TaskDetailScreen --------------------------
            onAllNodesWithText(HABIT_TITLE, useUnmergedTree = true).onFirst().performClick()
            awaitNodeWithText(strings.locTaskDetails)
            frame("04-habit-detail", "the Task Detail screen for the created habit", copy = strings.locTaskDetails)

            // ---- 5. DELETE: the top-bar delete opens a confirm dialog, whose Delete removes it ----
            onNodeWithContentDescription(strings.locDelete).performClick()
            awaitNodeWithText(strings.locDeleteTask)
            // The dialog's confirm button is the only node carrying the bare "Delete" TEXT: the top-bar
            // control is a contentDescription and the dialog title is "Delete Task?".
            onNodeWithText(strings.locDelete).performClick()

            // The delete navigates back to the list, and the card is gone once the poller re-reads.
            awaitNodeGone(HABIT_TITLE)
            frame("05-habit-deleted", "the Habits screen after the habit was deleted", copy = strings.locNoHabitsYet)
        }

    private companion object {
        /** A data literal, never app copy: the habit is asserted by what the user typed. */
        const val HABIT_TITLE = "Desktop Journey Habit"
    }

    /** True when a node carrying [label] is present right now (no waiting). */
    private fun DesktopComposeUiTest.exists(label: String): Boolean = onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
}
