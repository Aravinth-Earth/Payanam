//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.payanam.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Habits tab's list controls: sort, filter and search.
 *
 * **What was uncovered.** [HabitsCrudTest] proved create → complete → skip → detail → delete. The three
 * controls that change HOW the list is shown — the sort menu, the filter menu and the search field — were
 * rendered but never driven. A list control that opens a menu but writes nothing, or a search that does not
 * filter, is invisible to every existing habit assertion because the list still shows its habits.
 *
 * **The journey.** Two habits are created so a filter can be observed to change the list rather than merely
 * run. Then, on the Habits tab: the sort menu is opened and an option chosen (the menu must close); the
 * filter menu is opened and "Due today only" chosen (the menu must close); the search is opened, a query
 * typed, and the non-matching habit asserted GONE and the matching one still present; then the search is
 * cleared and both habits return.
 *
 * Every label is the app's own copy via [E2eLabels]; the two habit titles and the search query are data the
 * test types in.
 */
@RunWith(AndroidJUnit4::class)
class HabitsFilterSortTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-HABITS-FILTER")

    /** The app's own copy in the app's current language. */
    private val l: E2eLabels.Labels get() = h.labels

    /** The device sleeps on a short timeout mid-build; a stopped Activity has no Compose tree. */
    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun habitsSortFilterAndSearchAreDriven() {
        FreshSetup(h).runIfNeeded()

        // Two habits, so a filter/search can be observed to REMOVE one rather than merely run.
        createHabit(HABIT_A)
        createHabit(HABIT_B)

        h.goToTab(l.habits)
        h.assertVisible(HABIT_A)
        h.assertVisible(HABIT_B)
        h.capture("habits-before-controls")

        sort()
        filter()
        search()
        cleanup()
        h.log("habits-filter-sort=done")
    }

    /**
     * Removes the two habits this class created.
     *
     * The class shares one instrumentation invocation with the rest of the feature journeys
     * ([FreshSetup.runIfNeeded]), so anything it leaves behind is data the NEXT class reads - and the Habits
     * CRUD journey would then fail its own precondition for a reason that has nothing to do with it.
     * Deleting what it created is the rule every creating class follows, and it is what makes one install
     * and one setup serve the whole route.
     */
    private fun cleanup() {
        for (title in listOf(HABIT_A, HABIT_B)) {
            // The card's merged node carries the checkmark canvas over its right half, so the title's own
            // UNMERGED node is what reaches the card's click handler - the reach the CRUD journey makes.
            rule.onAllNodes(h.labelled(title), useUnmergedTree = true).onFirst().performClick()
            h.assertVisible(l.ui.locDelete)
            h.click(l.ui.locDelete) // opens the confirm dialog
            h.clickLast(l.ui.locDelete) // confirms - the dialog reuses the "Delete" label
            h.waitForGone(title)
            h.assertNotVisible(title)
        }
        h.log("cleanup=habits-deleted")
    }

    /** Creates a daily habit through the Add Task screen, the same way [HabitsCrudTest] does. */
    private fun createHabit(title: String) {
        h.goToTab(l.habits)
        h.click(l.addTask)
        h.type(l.taskTitleField, title)
        // Recurring opens the picker; Preset is selected first (it can open on Custom), then Daily, then OK.
        h.click(l.ui.locRecurring)
        h.click(l.ui.locPreset)
        h.click(l.ui.locDaily)
        h.click(l.ui.locOk)
        h.scrollTo(l.saveTask)
        h.click(l.saveTask)
        h.dismissNotificationOfferIfPresent()
        h.assertVisible(title)
    }

    /** The sort menu: open it, choose an option, and require the menu to close. */
    private fun sort() {
        h.click(l.ui.locSortHabits)
        h.assertVisible(l.ui.locSortNameZToA)
        h.click(l.ui.locSortNameAToZ)
        // The menu closing is the observable proof the choice was taken.
        h.waitForGone(l.ui.locSortNameZToA)
        h.assertVisible(HABIT_A)
        h.log("sort=name-asc")
    }

    /** The filter menu: open it, toggle "Due today only", and dismiss the (multi-toggle) menu. */
    private fun filter() {
        h.click(l.ui.locFilterHabits)
        h.assertVisible(l.ui.locHideAllMarkedToday)
        h.click(l.ui.locDueTodayOnly)
        // The filter menu is a MULTI-toggle — its items do not dismiss it, so a user can set several — so
        // it is closed with Back, and the closing is the observable proof the toggle was accepted.
        h.back()
        h.waitForGone(l.ui.locHideAllMarkedToday)
        h.assertVisible(HABIT_A)
        h.log("filter=due-today-only")
    }

    /** Search: open it, type a query, require the non-match GONE and the match present, then clear it. */
    private fun search() {
        h.click(l.ui.locSearchHabits)
        h.assertVisible(l.ui.locSearchHabitsPlaceholder)
        h.type(l.ui.locSearchHabits, SEARCH_QUERY)
        // The matching habit stays; the other is filtered out — the assertion that proves the filter ran.
        h.assertVisible(HABIT_A)
        h.waitForGone(HABIT_B)
        h.log("search=filtered")
        h.click(l.ui.locClearSearch)
        h.waitForGone(l.ui.locSearchHabitsPlaceholder)
        h.assertVisible(HABIT_B)
        h.log("search=cleared")
    }

    private companion object {
        /** Suite-authored data, not localized copy. */
        const val HABIT_A = "Alpha Stretch"
        const val HABIT_B = "Beta Walk"

        /** Matches only [HABIT_A]. */
        const val SEARCH_QUERY = "Alpha"
    }
}
