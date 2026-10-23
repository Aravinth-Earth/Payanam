//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextClearance
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.payanam.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Tasks CRUD journey on a fresh install: create (full fields) → edit → search → sort → filter → delete.
 *
 * One @Test method, one fresh-install invocation (run with `-KeepInstalled:$false`). Every label is read
 * from [E2eLabels] — the app's own copy, in the app's current language — so the flow runs unchanged on a
 * Tamil-locale device. The only literals are the task titles the test types in; those are data the test
 * compares against what it wrote, not localized copy.
 *
 * FILTER: the Tasks list opens on the `today` filter, and a task whose due date is not today (e.g. one
 * created with a past date) is hidden there. Every list assertion below therefore switches to the "All"
 * filter first — measured on device 2026-10-16, where a task created with a due date landed as `overdue`
 * and the default `today` filter showed an empty list.
 */
@RunWith(AndroidJUnit4::class)
class TasksCrudTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-TasksCrud")

    /** The app's own copy in the app's current language. */
    private val l get() = h.labels

    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun tasksCrudJourney() {
        // ═══ FRESH INSTALL → MAIN APP ═══════════════════════════════════════════════════════════════
        FreshSetup(h).runIfNeeded()

        // ═══ 1. FULL-FIELD CREATE ══════════════════════════════════════════════════════════════════
        h.goToTab(l.tasks)
        h.click(l.addTask)
        h.type(l.taskTitleField, TASK_TITLE)
        h.type(l.descriptionOptional, DESCRIPTION)

        // Life dimension: the anchor shows the first visible dimension; open it and pick a different one.
        h.click(l.physicalHealth)
        h.click(l.ui.locDimensionNameMentalHealth)
        h.assertVisible(l.ui.locDimensionNameMentalHealth)

        // Due date: open the date picker and confirm OK (the picker opens on a date near today).
        val dateLabel = DateTimeFormatter.ofPattern("EEE, MMM d").format(LocalDate.now())
        h.click(dateLabel)
        h.click(l.ui.locOk)

        h.scrollTo(l.saveTask)
        h.click(l.saveTask)
        h.assertVisible(l.addTask) // back on the Tasks list
        showAllTasks()
        h.assertVisible(TASK_TITLE)
        h.assertLogged("AddTaskViewModel.createTask", "Task created successfully")
        h.log("phase1=created")

        // ═══ 2. EDIT ═══════════════════════════════════════════════════════════════════════════════
        h.click(TASK_TITLE)
        h.assertVisible(l.taskDetails)
        h.click(l.edit)
        rule.onAllNodes(hasSetTextAction() and h.labelled(l.taskTitleField))
            .onFirst().performTextClearance()
        h.type(l.taskTitleField, TASK_TITLE_EDITED)
        h.scrollTo(l.ui.locSaveChanges)
        h.click(l.ui.locSaveChanges)
        h.assertVisible(TASK_TITLE_EDITED) // the detail screen reloaded with the new title
        h.back()
        showAllTasks()
        h.assertVisible(TASK_TITLE_EDITED) // and the list shows it too
        h.log("phase2=edited")

        // ═══ 3. SEARCH ══════════════════════════════════════════════════════════════════════════════
        // A second task is needed so the search has a non-match to exclude.
        h.click(l.addTask)
        h.type(l.taskTitleField, TASK_TITLE_2)
        h.scrollTo(l.saveTask)
        h.click(l.saveTask)
        h.assertVisible(l.addTask)
        showAllTasks()
        h.assertVisible(TASK_TITLE_2)

        // The tasks search field only exists under the "All" filter.
        h.type(l.ui.locSearchTasks, SEARCH_SUBSTRING)
        h.assertVisible(TASK_TITLE_EDITED)
        h.waitForGone(TASK_TITLE_2)
        // The tasks search field has no clear button; clear it the way a user would.
        rule.onAllNodes(hasSetTextAction() and h.labelled(l.ui.locSearchTasks))
            .onFirst().performTextClearance()
        h.assertVisible(TASK_TITLE_2)
        h.log("phase3=searched")

        // ═══ 4. SORT ═══════════════════════════════════════════════════════════════════════════════
        h.click(l.ui.locSortTasks)
        h.click(l.ui.locTitleAToZ)
        h.waitForGone(l.ui.locTitleAToZ) // the menu closed
        h.assertVisible(TASK_TITLE_EDITED) // the list still shows the task
        h.log("phase4=sorted")

        // ═══ 5. FILTER ═════════════════════════════════════════════════════════════════════════════
        // "Not Active" excludes a pending task, so the task leaves the list and returns under "All".
        h.clickSubstring(l.ui.locNotActive)
        h.waitForGone(TASK_TITLE_EDITED)
        showAllTasks()
        h.assertVisible(TASK_TITLE_EDITED)
        h.log("phase5=filtered")

        // ═══ 6. DELETE ═════════════════════════════════════════════════════════════════════════════
        h.click(TASK_TITLE_EDITED)
        h.assertVisible(l.taskDetails)
        h.click(l.ui.locDelete) // opens the confirm dialog
        h.clickLast(l.ui.locDelete) // confirms — the dialog reuses the "Delete" label
        h.waitForGone(TASK_TITLE_EDITED)
        h.assertNotVisible(TASK_TITLE_EDITED)
        h.log("phase6=deleted")
    }

    /** Switches the Tasks list to the "All" filter so a task outside "today" is visible. */
    private fun showAllTasks() {
        // The chip renders its count in the same node ("All (1)"), so match on the substring.
        h.clickSubstring(l.ui.locAll)
    }

    private companion object {
        /** Titles the test types in — data, not localized copy. */
        const val TASK_TITLE = "Water Plants"
        const val TASK_TITLE_EDITED = "Water Plants Edited"
        const val TASK_TITLE_2 = "Read Book"
        const val DESCRIPTION = "Weekly care routine"
        const val SEARCH_SUBSTRING = "Water"
    }
}
