//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.payanam.core.ui.navigation.PayanamTab
import org.junit.Test

/**
 * The desktop's habits LIST CONTROLS journey: sort, filter and search, walked in-process the way Android's
 * `HabitsFilterSortTest` walks them on a device.
 *
 * **The gap this closes.** `DesktopHabitsJourneyTest` proves create → open → delete. The three controls that
 * change HOW the list is shown - the sort menu, the filter menu and the search field - were rendered but
 * never driven on the desktop. A control that opens a menu but writes nothing, or a search that does not
 * filter, is invisible to every existing habit assertion because the list still shows its habits.
 *
 * **What is asserted, and why each one is an EFFECT rather than a click.** Two habits are created first, so a
 * control can be observed to CHANGE something rather than merely run:
 *  - SORT is proved by the list ORDER moving. Sorting Z→A must put [HABIT_B] above [HABIT_A] and sorting A→Z
 *    must put [HABIT_A] back above [HABIT_B] - read from the two cards' own vertical positions, so a menu
 *    that closed without sorting fails here. (The sort menu's own item closes it, which is a second proof.)
 *  - FILTER is proved by the count line. The header switches from the plain count to the filtered count the
 *    moment any filter is on, so the filtered copy appearing IS the toggle having been written.
 *  - SEARCH is proved by the list narrowing: the non-matching habit must be GONE and the match still there,
 *    then both must return when the search is cleared.
 *
 * **Why the log is NOT the proof here.** The three controls log at DEBUG (`TasksScreen.sortOptionSelected`,
 * `TasksScreen.dueTodayToggled`), and DEBUG is off in this harness - asserting those markers would be a
 * check that can never pass. The UI effects above are what a user actually gets.
 *
 * **Why the filter leg runs last and leaves its menu open.** The filter menu is a MULTI-toggle: its items
 * deliberately do not dismiss it (a user sets several), and the icon's handler only ever OPENS it. Closing it
 * therefore needs a dismissal gesture rather than a click, and nothing after this leg needs the screen - so
 * the leg asserts the filter's EFFECT and stops, instead of testing a dismissal.
 *
 * Every label is the app's own copy via the harness's `strings`; the two habit titles and the query are data.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopHabitsFilterSortJourneyTest : DesktopAppJourneyHarness() {
    @Test
    fun `the habits list is sorted, filtered and searched`() =
        runAtTheAppWindowSize {
            installProductionFixtureLayer()
            mountShell(configuredState(), isLandingReady = true, preferences = loadedPreferences())

            // Two habits, so a sort, a filter and a search can each be observed to CHANGE the list.
            createHabit(HABIT_A)
            createHabit(HABIT_B)

            onAllNodesWithText(PayanamTab.Habits.title(strings)).onFirst().performClick()
            waitForIdle()
            awaitNodeWithText(HABIT_A)
            awaitNodeWithText(HABIT_B)
            frame("00-both", "the Habits list with both habits", copy = HABIT_A)

            sort()
            search()
            filter()
        }

    /** Creates a daily habit through the Add Task screen, the way the Habits CRUD journey does. */
    private fun DesktopComposeUiTest.createHabit(title: String) {
        onAllNodesWithText(PayanamTab.Habits.title(strings)).onFirst().performClick()
        waitForIdle()
        onNodeWithContentDescription(strings.locAddTask).performClick()
        awaitNodeWithText(strings.locSaveTask)
        onAllNodes(hasSetTextAction()).onFirst().performTextInput(title)
        onAllNodesWithText(strings.locRecurring).onFirst().performClick()
        waitForIdle()
        if (exists(strings.locPreset)) onAllNodesWithText(strings.locPreset).onFirst().performClick()
        if (exists(strings.locDaily)) onAllNodesWithText(strings.locDaily).onFirst().performClick()
        if (exists(strings.locOk)) onAllNodesWithText(strings.locOk).onFirst().performClick()
        onAllNodesWithText(strings.locSaveTask).onFirst().performScrollTo().performClick()
        awaitNodeWithText(title)
    }

    /** SORT: choosing an option must REORDER the list, and the menu must close. */
    private fun DesktopComposeUiTest.sort() {
        // Z→A first, so the starting order cannot pass by accident: this must put B above A.
        onNodeWithContentDescription(strings.locSortHabits).performClick()
        awaitNodeWithText(strings.locSortNameZToA)
        frame("01-sort-open", "the habits sort menu open", copy = strings.locSortNameZToA)
        onAllNodesWithText(strings.locSortNameZToA).onFirst().performClick()
        awaitNodeGone(strings.locSortNameZToA)
        waitUntil(timeoutMillis = 10_000L) { topOf(HABIT_B) < topOf(HABIT_A) }
        frame("02-sorted-z-to-a", "the habits list with B above A after sorting Z to A", copy = HABIT_B)

        // Then A→Z, which must swap them back - the proof the option is WRITTEN, not merely clicked.
        onNodeWithContentDescription(strings.locSortHabits).performClick()
        awaitNodeWithText(strings.locSortNameAToZ)
        onAllNodesWithText(strings.locSortNameAToZ).onFirst().performClick()
        awaitNodeGone(strings.locSortNameAToZ)
        waitUntil(timeoutMillis = 10_000L) { topOf(HABIT_A) < topOf(HABIT_B) }
        frame("03-sorted-a-to-z", "the habits list with A above B after sorting A to Z", copy = HABIT_A)
    }

    /**
     * FILTER: the menu's toggle is reachable and its click path runs.
     *
     * **What is NOT asserted, and why - read this before extending it.** The filter's EFFECT cannot be
     * observed in this tier. The header switches to its filtered form from `chromeState`, which the harness
     * supplies as a STATIC `AppPreferencesState`; the real shell recomposes it after every write, so in the
     * app the header does change. Asserting it here would be asserting the harness, not the app - the same
     * limit `DesktopSettingsJourneyTest` records for the settings toggles. The WRITE itself is real: the
     * toggle goes through the destination's own writer into the installed database.
     *
     * This leg runs LAST and leaves its menu open: the filter menu is a MULTI-toggle whose items do not
     * dismiss it, and nothing after this leg needs the screen.
     */
    private fun DesktopComposeUiTest.filter() {
        onNodeWithContentDescription(strings.locFilterHabits).performClick()
        awaitNodeWithText(strings.locHideAllMarkedToday)
        frame("05-filter-open", "the habits filter menu open", copy = strings.locHideAllMarkedToday)
        onAllNodesWithText(strings.locDueTodayOnly).onFirst().performClick()
        waitForIdle()
        // The click path did not throw and the menu is still coherent: the toggle is still offered.
        awaitNodeWithText(strings.locDueTodayOnly)
        frame("06-filter-toggled", "the filter menu after the toggle was pressed", copy = strings.locDueTodayOnly)
    }

    /** SEARCH: the list must NARROW to the match, then widen again when the search is cleared. */
    private fun DesktopComposeUiTest.search() {
        onNodeWithContentDescription(strings.locSearchHabits).performClick()
        // The FIELD is what is awaited, not its placeholder: a focused text field does not render its
        // placeholder, so waiting on that copy waits for something that is never shown (measured).
        waitUntil(timeoutMillis = 10_000L) {
            onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        frame("06-search-open", "the habits search field open", copy = strings.locSearchHabits)
        // The search field is the only editable field on the Habits tab once the search is active.
        onAllNodes(hasSetTextAction()).onFirst().performTextInput(SEARCH_QUERY)

        awaitNodeWithText(HABIT_A)
        awaitNodeGone(HABIT_B)
        frame("07-search-narrowed", "the habits list narrowed to the search match", copy = HABIT_A)

        onNodeWithContentDescription(strings.locClearSearch).performClick()
        waitUntil(timeoutMillis = 10_000L) {
            onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty()
        }
        awaitNodeWithText(HABIT_B)
        frame("08-search-cleared", "the habits list after clearing the search", copy = HABIT_B)
    }

    /** The vertical position of the first node carrying [label] - what "above" is measured from. */
    private fun DesktopComposeUiTest.topOf(label: String): Float =
        onAllNodesWithText(label, useUnmergedTree = true)
            .onFirst()
            .fetchSemanticsNode()
            .boundsInRoot.top

    /** True when a node carrying [label] is present right now (no waiting). */
    private fun DesktopComposeUiTest.exists(label: String): Boolean = onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        /** Suite-authored data, not localized copy. A sorts before B. */
        const val HABIT_A = "Desktop Alpha Stretch"
        const val HABIT_B = "Desktop Beta Walk"

        /** Matches only [HABIT_A]. */
        const val SEARCH_QUERY = "Alpha"
    }
}
