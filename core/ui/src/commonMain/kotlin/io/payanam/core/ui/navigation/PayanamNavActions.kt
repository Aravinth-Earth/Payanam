//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.navigation

/**
 * What a destination's content is allowed to ask the navigation graph to do.
 *
 * WHY THIS EXISTS:
 *
 * The graph lives in the shared module, but the *content* of each destination does not: on Android a
 * screen reads its state from a Hilt-provided ViewModel and writes to the database, on the desktop it
 * reads from the desktop's own stores. Hilt is Android-only, so the destination bodies cannot live
 * beside the graph - they arrive through [PayanamNavHostScreens] and are handed this object so they can
 * still drive navigation.
 *
 * The alternative - exposing the raw `NavController` - would put the graph's internals into every
 * platform's wiring and let a caller navigate to a route the graph does not declare. The named helpers
 * below are the routes themselves, so a typo is a compile error rather than a screen that silently never
 * opens.
 */
class PayanamNavActions internal constructor(
    private val navigateToRoute: (String) -> Unit,
    private val navigateToTopLevelRoute: (String) -> Boolean,
    private val popBackStackAction: () -> Unit,
    /** Restarts the process so a deleted/replaced database is reopened cleanly. */
    val onRestartAfterDataDelete: () -> Unit,
) {
    /** Navigates to an arbitrary declared route. Prefer the named helpers. */
    fun navigate(route: String) {
        navigateToRoute(route)
    }

    /**
     * Switches to a bottom-navigation tab, saving and restoring that tab's state.
     *
     * Returns false while the graph is not installed yet; a caller that must not lose the request has to
     * retry. See [PayanamNavHost] for why the readiness probe is `currentBackStackEntry` rather than the
     * graph itself.
     */
    fun navigateToTopLevel(route: String): Boolean = navigateToTopLevelRoute(route)

    /** Pops the current destination. */
    fun goBack() {
        popBackStackAction()
    }

    /** Opens the Tasks tab. */
    fun openTasks() {
        navigateToTopLevelRoute(PayanamTab.Tasks.route)
    }

    /** Opens the Habits tab. */
    fun openHabits() {
        navigateToTopLevelRoute(PayanamTab.Habits.route)
    }

    /** Opens the Time tab. */
    fun openTime() {
        navigateToTopLevelRoute(PayanamTab.Time.route)
    }

    /** Opens the Journal tab. */
    fun openJournal() {
        navigateToTopLevelRoute(PayanamTab.Journal.route)
    }

    /** Opens the Notes tab. */
    fun openNotes() {
        navigateToTopLevelRoute(PayanamTab.Notes.route)
    }

    /** Opens the Lenses tab. */
    fun openLenses() {
        navigateToTopLevelRoute(PayanamTab.Lenses.route)
    }

    /** Opens the Settings tab. */
    fun openSettings() {
        navigateToTopLevelRoute(PayanamTab.Settings.route)
    }

    /** Opens the create-task form. */
    fun openAddTask() {
        navigateToRoute(PayanamRoutes.ADD_TASK)
    }

    /** Opens the detail screen for [taskId]. */
    fun openTaskDetail(taskId: String) {
        navigateToRoute(PayanamRoutes.taskDetail(taskId))
    }

    /** Opens the edit form for [taskId]. */
    fun openEditTask(taskId: String) {
        navigateToRoute(PayanamRoutes.editTask(taskId))
    }

    /** Opens the score-detail screen for [type] and [key]. */
    fun openScoreDetail(
        type: String,
        key: String,
    ) {
        navigateToRoute(PayanamRoutes.scoreDetail(type, key))
    }

    /** Opens the scoring configuration screen. */
    fun openScoringConfig() {
        navigateToRoute(PayanamRoutes.SCORING_CONFIG)
    }

    /** Opens the AI assistant. */
    fun openAssistant() {
        navigateToRoute(PayanamRoutes.ASSISTANT)
    }

    /** Opens the AI assistant's settings. */
    fun openAssistantSettings() {
        navigateToRoute(PayanamRoutes.ASSISTANT_SETTINGS)
    }

    /** Opens the passphrase-change form. */
    fun openPassphraseChange() {
        navigateToRoute(PayanamRoutes.PASSPHRASE_CHANGE)
    }
}
