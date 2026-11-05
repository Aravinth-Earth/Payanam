//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

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
 * The desktop's Tasks CRUD journey, walked in-process the way Android's `TasksCrudTest` walks it on a
 * device.
 *
 * **The gap this closes.** The desktop proved the Tasks SCREEN renders (`DesktopNavHostSharedScreenRenderTest`)
 * and that the first-run journey reaches it (`DesktopFirstRunJourneyTest`), but nothing on the desktop ever
 * CREATED a task through the UI, opened it, or deleted it. Those are the three write paths a user takes
 * every day, and on Android they are covered by an on-device class - so the desktop's parity claim rested on
 * a screen-render assertion and a README. This drives the same three steps through the real graph: the FAB
 * opens the real `AddTaskScreen`, the typed title goes through the real `AddTaskViewModel` into the real
 * encrypted database, the row opens the real `TaskDetailScreen`, and Delete removes it.
 *
 * **Why the production fixture.** A task write touches `tasks` (and its `life_dimensions` foreign key), and
 * the list reads `tags`/`task_tags`; the first-run journey's three-table fixture would fail the first save.
 * So this mounts over [installProductionFixtureLayer] - a database built by the production create path, with
 * the canonical dimensions seeded - and hands [mountShell] the REAL preference state ([loadedPreferences]),
 * because `AddTaskScreen` reads its dimension options from `LocalAppPreferences` and disables Save when it
 * holds none.
 *
 * **What it does NOT claim.** It does not re-walk the on-device class's edit / search / sort / filter legs;
 * this is the create-open-delete spine the desktop was missing. The title is a data literal, never app copy.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopTasksCrudJourneyTest : DesktopAppJourneyHarness() {
    @Test
    fun `a task is created through the add screen, opened in detail, and deleted`() =
        runAtTheAppWindowSize {
            installProductionFixtureLayer()
            mountShell(configuredState(), isLandingReady = true, preferences = loadedPreferences())

            // The shell lands on Tasks. The list loads its rows on a background dispatcher the harness clock
            // does not control, so the FAB's own label is the wait target rather than a settle.
            awaitNodeWithContentDescription(strings.locAddTask)
            frame("00-tasks-empty", "the Tasks screen with no rows yet", copy = strings.locAddTask)

            // ---- CREATE: FAB -> AddTaskScreen -> title -> Save -----------------------------------
            onNodeWithContentDescription(strings.locAddTask).performClick()
            awaitNodeWithText(strings.locSaveTask)
            // The title is the FIRST editable field on the screen, so it is addressed by its action rather
            // than by a label that a second field could also carry.
            onAllNodes(hasSetTextAction()).onFirst().performTextInput(TASK_TITLE)
            frame("01-add-task", "the Add Task screen with the title typed", copy = TASK_TITLE)
            // The Save button sits below a scrollable form on a 411x891 surface, so it is scrolled to before
            // it is pressed - the same reach a user makes.
            onNodeWithText(strings.locSaveTask).performScrollTo().performClick()

            // ---- READ: the created task appears in the list --------------------------------------
            // The default filter is TODAY and the add screen defaults the due date to today, so the new task
            // is in the default view without touching the filter chips.
            awaitNodeWithText(TASK_TITLE)
            assertLogged("AddTaskViewModel.createTask", "Task created successfully")
            frame("02-tasks-with-row", "the Tasks list carrying the created task", copy = TASK_TITLE)

            // ---- OPEN: the row opens TaskDetailScreen --------------------------------------------
            onNodeWithText(TASK_TITLE).performClick()
            awaitNodeWithContentDescription(strings.locDelete)
            frame("03-task-detail", "the Task Detail screen for the created task", copy = TASK_TITLE)

            // ---- DELETE: the top-bar delete opens a confirm dialog, whose Delete removes it ------
            onNodeWithContentDescription(strings.locDelete).performClick()
            awaitNodeWithText(strings.locDeleteTask)
            // The dialog's confirm button is the only node carrying the bare "Delete" TEXT: the top-bar
            // control is a contentDescription and the dialog title is "Delete Task".
            onNodeWithText(strings.locDelete).performClick()

            // The delete navigates back to the list, and the row is gone once the poller re-reads.
            awaitNodeGone(TASK_TITLE)
            frame("04-tasks-after-delete", "the Tasks list after the task was deleted", copy = strings.locNoTasksDueToday)
        }

    private companion object {
        /** A data literal, never app copy: the row is asserted by what the user typed. */
        const val TASK_TITLE = "Desktop Journey Task"
    }
}
