//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Note
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.ui.graphics.vector.ImageVector
import io.payanam.core.ui.text.UiStrings

/**
 * Bottom-navigation destinations: route, label, and selected/unselected icons.
 *
 * Moved here from `:app` with its contents unchanged. The one difference is [title]: the Android version
 * held a `@StringRes Int` and resolved it with `stringResource`, which does not exist off-Android. The
 * shared version holds a function from [UiStrings] to the label, so the same tab is labelled by the same
 * copy on every platform and a missing key is a compile error.
 */
sealed class PayanamTab(
    val route: String,
    val title: (UiStrings) -> String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    /** The Tasks tab. */
    data object Tasks : PayanamTab(
        route = "tasks",
        title = { it.settingsDatabaseTasks },
        selectedIcon = Icons.Filled.Checklist,
        unselectedIcon = Icons.Outlined.Checklist,
    )

    /** The Time tab. */
    data object Time : PayanamTab(
        route = "time",
        title = { it.locTime },
        selectedIcon = Icons.Filled.Timer,
        unselectedIcon = Icons.Outlined.Timer,
    )

    /** The Notes tab. */
    data object Notes : PayanamTab(
        route = "notes",
        title = { it.settingsDatabaseNotes },
        selectedIcon = Icons.Filled.Note,
        unselectedIcon = Icons.Outlined.Note,
    )

    /** The Habits tab. */
    data object Habits : PayanamTab(
        route = "habits",
        title = { it.locHabits },
        selectedIcon = Icons.Filled.Repeat,
        unselectedIcon = Icons.Outlined.Repeat,
    )

    /** The Journal tab. */
    data object Journal : PayanamTab(
        route = "journal",
        title = { it.locJournal },
        selectedIcon = Icons.Filled.Edit,
        unselectedIcon = Icons.Outlined.Edit,
    )

    /** The Lenses tab. */
    data object Lenses : PayanamTab(
        route = "lenses",
        title = { it.locLenses },
        selectedIcon = Icons.Filled.Visibility,
        unselectedIcon = Icons.Outlined.Visibility,
    )

    /** The Settings tab. */
    data object Settings : PayanamTab(
        route = "settings",
        title = { it.settingsTitle },
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
    )
}

/**
 * Every route in the app, including the ones that are not bottom-navigation tabs.
 *
 * [taskDetail], [editTask] and [scoreDetail] are the concrete forms of the parameterised routes declared
 * by [TASK_DETAIL], [EDIT_TASK] and [SCORE_DETAIL].
 */
object PayanamRoutes {
    /** Passphrase unlock gate, shown when the encrypted database needs a passphrase. */
    const val PASSPHRASE_UNLOCK = "passphrase_unlock"

    /** Passphrase setup gate, shown on first run. */
    const val PASSPHRASE_SETUP = "passphrase_setup"

    /** Passphrase change form, reachable from Settings. */
    const val PASSPHRASE_CHANGE = "passphrase_change"

    /** Database initialisation / import gate. */
    const val DATABASE_INIT = "database_init"

    /** Focus-mode preset onboarding, shown after database init on first run. */
    const val FOCUS_MODE_SELECTION = "focus_mode_selection"

    /** The first-run tour, rendered by the shared screen. */
    const val ONBOARDING_INTRO = "onboarding_intro"

    /** The fail-closed security refusal: shown when the passphrase store cannot be read. */
    const val SECURITY_STATE_UNAVAILABLE = "security_state_unavailable"

    /** Create-task form. */
    const val ADD_TASK = "add_task"

    /** Task detail, parameterised by task id. */
    const val TASK_DETAIL = "task_detail/{taskId}"

    /** Edit-task form, parameterised by task id. */
    const val EDIT_TASK = "edit_task/{taskId}"

    /** Score detail, parameterised by score type and key. */
    const val SCORE_DETAIL = "score_detail/{type}/{key}"

    /** Scoring configuration. */
    const val SCORING_CONFIG = "scoring_config"

    /** Builds the concrete task-detail route for [taskId]. */
    fun taskDetail(taskId: String) = "task_detail/$taskId"

    /** Builds the concrete edit-task route for [taskId]. */
    fun editTask(taskId: String) = "edit_task/$taskId"

    /** Builds the concrete score-detail route for [type] and [key]. */
    fun scoreDetail(
        type: String,
        key: String,
    ) = "score_detail/$type/$key"
}

/** The bottom-navigation tabs, in bar order. */
val payanamBottomNavItems: List<PayanamTab> =
    listOf(
        PayanamTab.Tasks,
        PayanamTab.Habits,
        PayanamTab.Time,
        PayanamTab.Journal,
        PayanamTab.Notes,
        PayanamTab.Lenses,
        PayanamTab.Settings,
    )

/**
 * Centralised route-access policy for [PayanamNavHost].
 *
 * Extracted from the composable so the logic can be unit-tested without a Compose runtime.
 *
 * Route categories:
 * - [startupGateRoutes]: first-run / recovery flows - always allowed regardless of feature flags.
 * - [secondaryRoutes]: child screens reachable from allowed tabs (Tasks, Settings) - always allowed
 *   because they are part of the normal task/settings flow, not independent feature tabs.
 * - [minimalModeAllowedTabs]: bottom-nav tabs visible in minimal mode.
 */
object PayanamNavRoutePolicy {
    /** Routes that make up the first-run and recovery gates; never blocked by feature flags. */
    val startupGateRoutes: Set<String> =
        setOf(
            "database_init",
            "passphrase_setup",
            "passphrase_unlock",
            "passphrase_change",
            "focus_mode_selection",
        )

    /** Child routes reachable from an allowed tab. Prefix-matched: `task_detail` covers `task_detail/{taskId}`. */
    val secondaryRoutes: Set<String> =
        setOf(
            "add_task",
            "task_detail",
            "edit_task",
            "scoring_config",
        )

    /** Bottom-navigation tabs that stay visible in minimal mode. */
    val minimalModeAllowedTabs: Set<String> =
        setOf(
            "time",
            "tasks",
            "journal",
            "notes",
            "lenses",
            "settings",
        )

    /**
     * Returns true if [route] is allowed to be on the nav stack given [minimalModeEnabled].
     *
     * Startup gate routes and secondary (child) routes are always allowed.
     * In minimal mode, only [minimalModeAllowedTabs] are allowed for the bottom nav tabs;
     * other disabled tabs (currently habits) are blocked.
     */
    fun isAllowed(
        route: String,
        minimalModeEnabled: Boolean,
    ): Boolean {
        if (route in startupGateRoutes) return true
        if (secondaryRoutes.any { prefix -> route == prefix || route.startsWith("$prefix/") }) return true
        if (minimalModeEnabled && route !in minimalModeAllowedTabs) return false
        return true
    }

    /**
     * A module is disabled when the user hid its tab (focus preset or manual override) or
     * minimal mode excludes the route. External commands must respect this instead of
     * bypassing the user's choice.
     */
    fun isModuleDisabled(
        route: String,
        minimalModeEnabled: Boolean,
        tabVisibility: Map<String, Boolean>,
    ): Boolean = tabVisibility[route] == false || !isAllowed(route, minimalModeEnabled)
}

/** True when [route] should be remembered and returned to after a silent passphrase re-auth. */
fun shouldCaptureReturnRouteForUnlock(route: String): Boolean =
    route != PayanamRoutes.PASSPHRASE_UNLOCK &&
        route != PayanamRoutes.PASSPHRASE_SETUP &&
        route != PayanamRoutes.PASSPHRASE_CHANGE &&
        route != PayanamRoutes.DATABASE_INIT &&
        route != PayanamRoutes.FOCUS_MODE_SELECTION &&
        route != PayanamRoutes.SECURITY_STATE_UNAVAILABLE &&
        route != PayanamRoutes.ONBOARDING_INTRO

/**
 * Whether [route] is one of the app's own startup gates rather than a place the user was.
 *
 * These screens are the app asking its own questions. A silent unlock must never fire over one of them:
 * the gate the user is standing on is the newer answer, so dragging them to the unlock screen makes an
 * earlier decision (an unlock resolved at cold boot) override the action they just took. Measured case:
 * the forgot-passphrase reset deletes the database and navigates to the passphrase-setup screen, then a
 * still-set "unlock required" flag (computed once, before the reset) sent the user back to an unlock
 * screen for a database that no longer existed - on Android and desktop alike, because this graph is
 * shared. Same rule the passphrase-change screen already had, applied to the whole gate set instead of
 * one screen at a time.
 */
fun isStartupGateRoute(route: String): Boolean = !shouldCaptureReturnRouteForUnlock(route)

/**
 * The minimal-mode tab filter, shared so both platforms hide exactly the same tabs.
 *
 * Settings is always visible; every other tab is hidden when the user turned it off or when minimal mode
 * excludes it.
 */
fun visiblePayanamTabs(
    tabVisibility: Map<String, Boolean>,
    minimalModeEnabled: Boolean,
): List<PayanamTab> =
    payanamBottomNavItems.filter { tab ->
        val allowedByFlags =
            if (minimalModeEnabled) {
                tab.route in PayanamNavRoutePolicy.minimalModeAllowedTabs
            } else {
                true
            }
        (tab.route == PayanamTab.Settings.route || tabVisibility[tab.route] != false) && allowedByFlags
    }

/**
 * Expands a route template to its concrete form using the destination's arguments.
 *
 * `task_detail/{taskId}` becomes `task_detail/abc-123` so the route shown in logs and captured for
 * post-unlock return matches the one the user was actually on.
 */
fun resolveConcreteRoute(
    route: String,
    taskId: String? = null,
): String =
    when (route) {
        PayanamRoutes.TASK_DETAIL -> {
            taskId?.let { PayanamRoutes.taskDetail(it) } ?: route
        }

        PayanamRoutes.EDIT_TASK -> {
            taskId?.let { PayanamRoutes.editTask(it) } ?: route
        }

        else -> {
            route
        }
    }
