//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.navigation

import androidx.compose.runtime.Composable
import io.payanam.shared.settings.FocusModePreset

/**
 * The body of every destination in the shared graph, supplied by the platform that is running it.
 *
 * WHY THE DESTINATIONS ARE SUPPLIED RATHER THAN DECLARED HERE:
 *
 * [PayanamNavHost] owns the routes, the arguments and the startup-gate logic - that is the part that must
 * not differ between Android and the desktop, and the part that used to be re-implemented per platform.
 * A destination's *body*, however, is wired to something the shared module deliberately has no access to:
 * on Android a Hilt ViewModel collecting from the encrypted database, on the desktop the desktop's own
 * stores. Hilt is Android-only, so the bodies cannot move with the graph.
 *
 * This interface is the seam between those two halves. It is not a per-screen wrapper: a platform
 * implements it once (one file), and the same shared screen renders on both once it has moved. A screen
 * that has moved is called from here with the platform's state.
 *
 * Every method receives the [PayanamNavActions] for its destination, so content drives navigation the same
 * way on every platform, and completion callbacks (`onConfigured`, `onUnlocked`, ...) are the host's, not
 * the body's - the host is what decides where the flow continues.
 */
interface PayanamNavHostScreens {
    /** The first-run passphrase setup gate. */
    @Composable
    fun passphraseSetup(
        nav: PayanamNavActions,
        onConfigured: () -> Unit,
    )

    /** The passphrase unlock gate. */
    @Composable
    fun passphraseUnlock(
        nav: PayanamNavActions,
        onUnlocked: () -> Unit,
        onForgotPassphraseReset: () -> Unit,
    )

    /** The passphrase-change form, reached from Settings. */
    @Composable
    fun passphraseChange(
        nav: PayanamNavActions,
        onPassphraseChanged: () -> Unit,
    )

    /** Database initialisation / import gate. */
    @Composable
    fun databaseInit(
        nav: PayanamNavActions,
        onDatabaseReady: () -> Unit,
    )

    /** Focus-mode preset onboarding. */
    @Composable
    fun focusModeSelection(
        nav: PayanamNavActions,
        onPresetSelected: (FocusModePreset) -> Unit,
    )

    /** The Tasks tab. */
    @Composable
    fun tasks(nav: PayanamNavActions)

    /** The Habits tab. */
    @Composable
    fun habits(nav: PayanamNavActions)

    /**
     * The Time tab.
     *
     * The two request ids are the "open the start/stop-tracking dialog" requests issued by the home-screen
     * widget and carried in through [PayanamExternalNavigationCommand]; the matching `on...Handled`
     * callback tells the host it may drop the pending request.
     */
    @Composable
    fun time(
        nav: PayanamNavActions,
        openStartTrackingDialogRequestId: Long?,
        onOpenStartTrackingDialogHandled: (Long) -> Unit,
        openStopTrackingDialogRequestId: Long?,
        onOpenStopTrackingDialogHandled: (Long) -> Unit,
    )

    /** The Notes tab. */
    @Composable
    fun notes(nav: PayanamNavActions)

    /** The Journal tab. */
    @Composable
    fun journal(nav: PayanamNavActions)

    /** The Lenses tab. */
    @Composable
    fun lenses(nav: PayanamNavActions)

    /** The Settings tab. */
    @Composable
    fun settings(nav: PayanamNavActions)

    /** The create-task form. */
    @Composable
    fun addTask(nav: PayanamNavActions)

    /** The detail screen for [taskId]. */
    @Composable
    fun taskDetail(
        nav: PayanamNavActions,
        taskId: String,
    )

    /** The edit form for [taskId]. */
    @Composable
    fun editTask(
        nav: PayanamNavActions,
        taskId: String,
    )

    /** The score-detail screen for [type] and [key]. */
    @Composable
    fun scoreDetail(
        nav: PayanamNavActions,
        type: String,
        key: String,
    )

    /** The scoring configuration screen. */
    @Composable
    fun scoringConfig(nav: PayanamNavActions)

    /** The AI assistant. */
    @Composable
    fun assistant(nav: PayanamNavActions)

    /** The AI assistant's settings. */
    @Composable
    fun assistantSettings(nav: PayanamNavActions)
}
