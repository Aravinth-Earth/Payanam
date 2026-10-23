//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.time

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.DimensionPreference
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * The two clickable timeline blocks, rendered for real on the JVM target.
 *
 * WHY THIS NEEDS ITS OWN FILE, AND WHY A RENDERED TREE IS THE ONLY INSTRUMENT. Both blocks are named for
 * what they are made of: `DimensionIconCascadeLayer` draws a field of decorative icons, every one of which
 * passes `contentDescription = null`, and in the roomiest case a single line of `labelSmall` text clipped to
 * a handful of characters. A node built that way has no name, and a day of tracked time is nothing but a
 * column of these - so a screen reader walked the timeline hearing only "button". There is no string to grep
 * for and no value to assert: `null` is precisely what the Compose API takes to mean "decorative, on
 * purpose", so the only thing that distinguishes a named block from an anonymous one is the rendered
 * semantics tree.
 *
 * THE NAMES ARE NOT NEW COPY. Each block already computed the label it draws - `buildTimeBlockCompactLabel`
 * from its dimension, task, range, duration and focus value - and that same value is what the description is
 * built from here. So the expectations below are computed through [buildTimeBlockCompactLabel] and the
 * shared copy table rather than written out: a control cannot pass by being labelled with the wrong string,
 * and a change to the label's format moves both sides together instead of making this test lie.
 *
 * `LocalUiStrings` has no default - a read without a provider fails loudly by design - so every case installs
 * the English table, which is what keeps these assertions about the SCREEN rather than about copy that happens
 * to be hardcoded.
 *
 * WHY THE CLICK IS ASSERTED AND NOT JUST THE NAME. Naming a control is worth nothing if the name is attached
 * to a node the user cannot reach, and merging a block's descendants is exactly the kind of change that can
 * quietly swallow its click action - so each case asserts the description AND that the same node still takes
 * the press, AND that it fires its OWN callback. A single label applied to both blocks, or a merge that left
 * both blocks inert, would pass a name-only test and is the defect this file is for.
 */
class TimeEntryBlockSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val strings: UiStrings = UiStrings.of(UiLanguage.ENGLISH)

    @Test
    fun `a tracked entry's block is named with the label it draws and is still clickable`() {
        val entry = entry(id = ENTRY_ID, startHour = 9, endHour = 10, endMinute = 20)
        // No task lookup, so the rendered block has no title to announce - and the expectation is derived
        // from the SAME empty lookup, which is what keeps the two from drifting apart.
        val taskLookup = emptyMap<String, Task>()
        val expected = entryLabel(taskLookup = taskLookup)
        var clicks = 0

        render(entries = listOf(entry), taskLookup = taskLookup, onEntryClick = { clicks++ })

        val block = composeRule.onNodeWithContentDescription(expected)
        block.assertHasClickAction()
        // Scrolled into the viewport first: a day is a tall column inside a vertical scroll, and a block that
        // is laid out but below the fold is a node the finder can reach and a press cannot - so without this
        // the click would be injected at a point no window owns and the counter would read zero.
        block.performScrollTo().performClick()
        assertEquals(1, clicks, "the entry block did not run its own click callback, or ran it twice")
        // Counted, so a description duplicated onto a second node cannot satisfy the finder above.
        composeRule.onAllNodesWithContentDescription(expected).assertCountEquals(1)
    }

    @Test
    fun `a past occurrence's block is named and is still clickable`() {
        val task = task(id = TASK_ID, title = TASK_TITLE)
        val occurrence = occurrence(taskId = TASK_ID, hour = 14)
        val expected = occurrenceLabel(task)

        var clicks = 0
        render(
            entries = emptyList(),
            pastOccurrences = listOf(occurrence),
            taskLookup = mapOf(TASK_ID to task),
            onOccurrenceClick = { _, _ -> clicks++ },
        )

        val block = composeRule.onNodeWithContentDescription(expected)
        block.assertHasClickAction()
        // Same scroll-then-click as the entry block above: this one sits even lower in the day.
        block.performScrollTo().performClick()
        assertEquals(1, clicks, "the occurrence block did not run its own click callback, or ran it twice")
    }

    /**
     * THE IDENTITY THAT MATTERS: the block that is named is the block that is pressed.
     *
     * Both blocks are drawn from the same field of icons into the same column, so a description applied to
     * the wrong one - or to both - would still let every name-only assertion above pass. Two blocks whose
     * windows do not overlap are therefore rendered on one day and each is reached by its OWN label, with the
     * callbacks counted separately: a block that reported the other's description, or that fired the other's
     * callback, fails here and nowhere else.
     */
    @Test
    fun `both blocks on one day are named distinctly and each fires only its own callback`() {
        val entry = entry(id = ENTRY_ID, startHour = 9, endHour = 10, endMinute = 20)
        val task = task(id = TASK_ID, title = TASK_TITLE)
        val occurrence = occurrence(taskId = TASK_ID, hour = 14)
        val taskLookup = mapOf(TASK_ID to task)
        val entryName = entryLabel(taskLookup = taskLookup)
        val occurrenceName = occurrenceLabel(task)
        // The guard the rest of this case depends on is that the two labels DIFFER: an entry that announced
        // the same words as the occurrence would let the finder below reach either block, and the identity
        // this file exists to protect would be untested. Asserted as inequality - equal labels would make
        // every click below ambiguous, so equality is the fixture failure, not the pass condition.
        assertNotEquals(entryName, occurrenceName, "the fixture no longer distinguishes the two blocks")

        val entryClicks = mutableListOf<String>()
        val occurrenceClicks = mutableListOf<String>()
        render(
            entries = listOf(entry),
            pastOccurrences = listOf(occurrence),
            taskLookup = taskLookup,
            onEntryClick = { entryClicks += it.id },
            onOccurrenceClick = { occ, _ -> occurrenceClicks += occ.id },
        )

        composeRule.onNodeWithContentDescription(entryName).performScrollTo().performClick()
        composeRule.onNodeWithContentDescription(occurrenceName).performScrollTo().performClick()

        assertEquals(listOf(ENTRY_ID), entryClicks, "the entry block answered for the occurrence, or not at all")
        assertEquals(listOf(OCCURRENCE_ID), occurrenceClicks, "the occurrence block answered for the entry")
    }

    /**
     * The block with the LEAST to announce is still announced.
     *
     * An occurrence whose task has been deleted renders no task title at all, so before this fix it was the
     * most anonymous thing on the timeline: a bare icon cascade with no text and no name, and a click whose
     * meaning a user could not see. It is named from the copy table's own "unknown task" wording, which is
     * what the block already falls back to for its visible caption - so a stale block reads as stale rather
     * than as silence.
     */
    @Test
    fun `an occurrence whose task is gone is still named`() {
        val occurrence = occurrence(taskId = MISSING_TASK_ID, hour = 14)
        var clicks = 0

        render(
            entries = emptyList(),
            pastOccurrences = listOf(occurrence),
            // The lookup deliberately misses: this is the state a deleted task leaves behind.
            taskLookup = emptyMap(),
            onOccurrenceClick = { _, _ -> clicks++ },
        )

        // Matched on the copy table's own "unknown task" wording rather than on a whole label: with no task
        // there is no dimension and no duration the test could honestly predict, and the point of the case is
        // that the block says SOMETHING identifying. `locUnknownTask` is the segment the block itself falls
        // back to, so a block that had lost its description entirely could not satisfy this.
        val orphan =
            composeRule.onAllNodes(hasContentDescription(strings.locUnknownTask, substring = true))
        orphan.assertCountEquals(1)
        orphan[0].assertHasClickAction()
        // Scrolled into view before the press, for the same reason the two cases above do it.
        orphan[0].performScrollTo().performClick()
        assertEquals(1, clicks, "an orphaned occurrence block stopped being clickable")
    }

    private fun render(
        entries: List<TimeEntry>,
        pastOccurrences: List<TaskOccurrence> = emptyList(),
        taskLookup: Map<String, Task> = emptyMap(),
        onEntryClick: (TimeEntry) -> Unit = {},
        onOccurrenceClick: (TaskOccurrence, Task?) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
                TimeCalendarView(
                    selectedDate = DAY,
                    entries = entries,
                    activeEntry = null,
                    plannedTasks = emptyList(),
                    pastOccurrences = pastOccurrences,
                    taskLookup = taskLookup,
                    hourHeightDp = HOUR_HEIGHT_DP,
                    currentTime = DAY.atTime(20, 0),
                    scrollState = rememberScrollState(),
                    use24Hour = true,
                    highlightedDimensionId = null,
                    preferences = preferences(),
                    onEntryClick = onEntryClick,
                    onPlannedTaskClick = {},
                    onPastOccurrenceClick = onOccurrenceClick,
                )
            }
        }
    }

    /**
     * The name an entry block carries, computed the way the block computes its own caption.
     *
     * The dimension is read from the PREFERENCES lookup by id, which is why the fixture registers a dimension
     * rather than relying on the fallback: an entry whose dimension resolved to nothing is labelled with an
     * EMPTY string, so every expectation would then begin with a stray separator and the assertion would be
     * about the fixture rather than about the block.
     *
     * The TASK segment is read out of the same [taskLookup] the caller renders with, for the same reason:
     * production resolves the title as `entry.taskId?.let { taskLookup[it]?.title }`, and a null title is
     * dropped by [buildTimeBlockCompactLabel] rather than spelled out. Writing "Morning Run" into this helper
     * regardless of the lookup described an entry the fixture never draws - the block whose lookup is empty
     * announces no title at all, and expecting one was the bug this signature removes.
     */
    private fun entryLabel(taskLookup: Map<String, Task>): String {
        val entry = entry(id = ENTRY_ID, startHour = 9, endHour = 10, endMinute = 20)
        val taskTitle = entry.taskId?.let { taskLookup[it]?.title }
        return buildTimeBlockCompactLabel(
            dimensionLabel = DIMENSION_LABEL,
            taskLabel = taskTitle,
            startLabel = START_LABEL,
            endLabel = "10:20",
            durationLabel = strings.locDurationHoursMinutesCompact.format(1, 20),
            focusValueLabel = FOCUS_LABEL,
        )
    }

    /**
     * The name an occurrence block carries, from the same builder over its own segments.
     *
     * The window is spelled out rather than recomputed: the block centres a 30-minute occurrence on its due
     * time, so a task due at 14:00 draws as 13:45 - 14:15, and the label the block builds says so. This
     * fixture's task sets `durationMinutes = 30` precisely so that half-hour arithmetic is checkable by eye.
     */
    private fun occurrenceLabel(task: Task): String =
        buildTimeBlockCompactLabel(
            dimensionLabel = task.lifeIntentionCategory,
            taskLabel = task.title,
            startLabel = "13:45",
            endLabel = "14:15",
            durationLabel = strings.locDurationMinutesCompact.format(30),
            focusValueLabel = formatCompactFocusValue(task.focusRequired),
        )

    /**
     * One visible life dimension.
     *
     * An entry with no resolvable dimension falls back to an EMPTY label and the block then announces a
     * leading separator, so the fixture registers the row the label is read from. The colour is irrelevant to
     * every assertion here and is only there because the row requires one.
     */
    private fun preferences(): AppPreferencesState =
        AppPreferencesState(
            dimensionPreferences =
                listOf(
                    DimensionPreference(
                        key = DIMENSION_ID,
                        label = DIMENSION_LABEL,
                        color = Color(0xFF4CAF50),
                        isVisible = true,
                    ),
                ),
        )

    private fun entry(
        id: String,
        startHour: Int,
        endHour: Int,
        endMinute: Int,
    ): TimeEntry {
        val start = DAY.atTime(startHour, 0)
        val end = DAY.atTime(endHour, endMinute)
        return TimeEntry(
            id = id,
            lifeIntentionCategory = DIMENSION_LABEL,
            taskId = TASK_ID,
            startedAt = start,
            endedAt = end,
            focusRating = 0.7,
            createdAt = start,
            updatedAt = end,
            dimensionId = DIMENSION_ID,
        )
    }

    private fun occurrence(
        taskId: String,
        hour: Int,
    ): TaskOccurrence =
        TaskOccurrence(
            id = OCCURRENCE_ID,
            taskId = taskId,
            // The ISO instant is what places the block on this day, so it carries the hour.
            occurrenceDate = "${DAY}T${hour.toString().padStart(2, '0')}:00:00",
            status = "completed",
        )

    private fun task(
        id: String,
        title: String,
    ): Task {
        val stamp = DAY.atTime(8, 0)
        return Task(
            id = id,
            title = title,
            dueDate = DAY.atTime(14, 0),
            createdAt = stamp,
            updatedAt = stamp,
            durationMinutes = 30,
            lifeIntentionCategory = DIMENSION_LABEL,
            dimensionId = DIMENSION_ID,
        )
    }

    private companion object {
        /** A fixed day, so the timeline's arithmetic and the expectations cannot drift with the clock. */
        val DAY: LocalDate = LocalDate.of(2026, 3, 15)

        /** 60dp an hour, the shared default: tall enough that a 80-minute block is not a sliver. */
        const val HOUR_HEIGHT_DP = 60f

        const val ENTRY_ID = "entry-1"
        const val TASK_ID = "task-1"
        const val OCCURRENCE_ID = "occurrence-1"
        const val MISSING_TASK_ID = "task-deleted"

        const val TASK_TITLE = "Morning Run"
        const val DIMENSION_ID = "dim_health_wellness"
        const val DIMENSION_LABEL = "Health & Wellness"
        const val START_LABEL = "09:00"

        /** What [formatCompactFocusValue] renders for this fixture's 0.7 rating, not a literal. */
        val FOCUS_LABEL: String = formatCompactFocusValue(0.7) ?: ""
    }
}