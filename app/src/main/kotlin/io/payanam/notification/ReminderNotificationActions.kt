//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.notification

import android.os.Build
import androidx.annotation.VisibleForTesting

/** One action slot a task reminder notification fills, in display order. */
enum class ReminderActionSlot {
    /** Mark the task complete. Present at every action count. */
    COMPLETE,

    /** Mark the task missed. Present only when three actions render. */
    MISS,

    /** Open the reminder's own destination in the app. Present whenever two or more actions render. */
    MORE,
}

/** Actions to add to a reminder notification; the rendered count is unqueryable, see [ASSUMED_VISIBLE_ACTIONS]. */
fun reminderActionSlots(sdkInt: Int = Build.VERSION.SDK_INT): List<ReminderActionSlot> = reminderActionSlotsForCount(assumedVisibleActionCount(sdkInt))

/** Slots for [visibleCount] rendered actions; three is the ceiling, a fourth is never rendered. */
@VisibleForTesting
internal fun reminderActionSlotsForCount(visibleCount: Int): List<ReminderActionSlot> = when {
    visibleCount >= ASSUMED_VISIBLE_ACTIONS -> listOf(
        ReminderActionSlot.COMPLETE,
        ReminderActionSlot.MISS,
        ReminderActionSlot.MORE,
    )

    visibleCount == 2 -> listOf(ReminderActionSlot.COMPLETE, ReminderActionSlot.MORE)

    else -> listOf(ReminderActionSlot.COMPLETE)
}

/** Documented assumption: three actions render on every supported version; Android reports nothing at runtime. */
internal const val ASSUMED_VISIBLE_ACTIONS = 3

/** Rendered action count assumed for [sdkInt]; one action is the floor below the three-action API level. */
private fun assumedVisibleActionCount(sdkInt: Int): Int = if (sdkInt >= Build.VERSION_CODES.P) ASSUMED_VISIBLE_ACTIONS else 1
