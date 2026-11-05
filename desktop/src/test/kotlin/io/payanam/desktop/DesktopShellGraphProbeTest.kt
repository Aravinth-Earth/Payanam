//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.navigation.PayanamNavActions
import io.payanam.core.ui.navigation.PayanamNavHost
import io.payanam.core.ui.navigation.PayanamNavHostScreens
import io.payanam.core.ui.navigation.PayanamRoutes
import io.payanam.core.ui.navigation.PayanamTab
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.UnifiedLogger
import io.payanam.shared.settings.FocusModePreset
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The graph itself, mounted - the half of the shell probe that `DesktopShellRenderProbeTest` does not cover.
 *
 * **Why this exists.** That probe renders a hand-written `Column` carrying the content pane's modifier
 * chain around one screen, and its second case reads `DesktopApp.kt` as source text. Neither mounts
 * [PayanamNavHost], so nothing in the suite had ever composed the real graph: the routes, the startup
 * gates, the bottom bar and the navigation library were all unexercised on the desktop. This fixture is
 * the instrument the rail fix needs - a mounted graph whose visible destination can be observed - and it
 * pins that the graph mounts and that a bottom-bar tap really switches destination, rather than merely
 * drawing a pane.
 *
 * **How the destination is observed, and why two ways.** The fake bodies below record which one composed,
 * which is the user-visible half. It is not sufficient alone: a body can compose while the navigation
 * library has not accepted the route (or has restarted the graph), so the library's own event is asserted
 * as well - [PayanamNavHost] logs `PayanamNavHost.destinationChanged` with the route it landed on. The
 * back stack itself is not readable from a test: the host never exposes its `NavController`, and
 * `PayanamNavActions` has no read API, so the log is the only legitimate observation point.
 *
 * **What this does NOT prove.** The production navigation rail
 * (`desktopNavigationRail`) is private to `DesktopApp.kt` and cannot be rendered here, so this fixture
 * exercises the shared graph's own bottom bar. Whether the *rail* drives the graph is a separate
 * assertion and is not claimed by this test.
 *
 * **No theme wrapper, deliberately.** The graph installs its own string table
 * (`CompositionLocalProvider(LocalUiStrings provides strings)`), which the render probe had to supply
 * itself because it mounts a bare screen. Wrapping it again would be testing the wrapper.
 */
@Suppress("UNUSED_PARAMETER", "LongParameterList")
class DesktopShellGraphProbeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val navigation = NavigationEventRecorder()
    private val composedRoutes = mutableListOf<String>()
    private val screens = RecordingScreens(composedRoutes)

    // The logger is a process-wide singleton and test classes in a module share one JVM, so installing in
    // `@Before` and resetting in `@After` is required in both directions: the `@Before` stops this class
    // from asserting against a logger leaked by an earlier one, and the `@After` stops it leaking onward.
    @Before
    fun installLogger() {
        AppLogger.install(navigation)
    }

    @After
    fun resetLogger() {
        AppLogger.resetForTests()
    }

    @Test
    fun `the shared graph mounts and a bottom bar tap switches destination`() {
        val notesLabel = PayanamTab.Notes.title(UiStrings.of(UiLanguage.ENGLISH))

        composeRule.setContent {
            PayanamNavHost(
                screens = screens,
                language = UiLanguage.ENGLISH,
                minimalModeEnabled = false,
                landingRoute = PayanamTab.Tasks.route,
                isLandingReady = true,
            )
        }
        composeRule.waitForIdle()

        // 1. The graph mounted and opened its start destination, whose body is the fake's - so nothing but
        //    an accepted `tasks` route can satisfy it.
        assertThat(composedRoutes.last()).isEqualTo(PayanamTab.Tasks.route)
        composeRule.onNodeWithText(bodyText(PayanamTab.Tasks.route)).assertIsDisplayed()

        // 2. The bottom bar carries a real item for the target tab. `assertExists` rather than
        //    `assertIsDisplayed`: seven items can overflow the test window, and a scrolled-off item is
        //    still a correct bar.
        composeRule.onNodeWithContentDescription(notesLabel).assertExists()

        // 3. The tap switches destination - the body that composes changes, AND the library logs the route.
        composeRule.onNodeWithContentDescription(notesLabel).performClick()
        composeRule.waitForIdle()

        assertThat(composedRoutes.last()).isEqualTo(PayanamTab.Notes.route)
        composeRule.onNodeWithText(bodyText(PayanamTab.Notes.route)).assertIsDisplayed()
        assertThat(navigation.routes).contains(PayanamTab.Notes.route)
    }

    /**
     * A top-level request - the desktop rail's channel - moves the graph and is then consumed.
     *
     * This is the half the rail needs: the rail cannot reach the graph's controller (they are siblings), so
     * it stores a route and the graph navigates when it can. Consuming on success is what stops the request
     * from firing again on the next `currentRoute` change.
     */
    @Test
    fun `a top level navigation request switches destination and is consumed`() {
        val request = mutableStateOf<String?>(null)
        composeRule.setContent {
            PayanamNavHost(
                screens = screens,
                language = UiLanguage.ENGLISH,
                minimalModeEnabled = false,
                landingRoute = PayanamTab.Tasks.route,
                isLandingReady = true,
                topLevelNavigationRequest = request.value,
                onTopLevelNavigationRequestConsumed = { request.value = null },
            )
        }
        composeRule.waitForIdle()
        assertThat(composedRoutes.last()).isEqualTo(PayanamTab.Tasks.route)

        request.value = PayanamTab.Notes.route
        composeRule.waitForIdle()

        // The destination moved, the library accepted the route, and the request was cleared - so a later
        // `currentRoute` change cannot re-fire it.
        assertThat(composedRoutes.last()).isEqualTo(PayanamTab.Notes.route)
        composeRule.onNodeWithText(bodyText(PayanamTab.Notes.route)).assertIsDisplayed()
        assertThat(navigation.routes).contains(PayanamTab.Notes.route)
        assertThat(request.value).isNull()
    }

    /**
     * A request the route policy refuses must not navigate, and must still be consumed.
     *
     * The bottom bar refuses `habits` in minimal mode through `isRouteAllowed`; a request that skipped that
     * check would give one intent two answers in this file, which is the inconsistency this pins. Consuming
     * the refusal matters as much: a refused request left non-null re-runs on every `currentRoute` change and
     * can fire later, when the policy has moved.
     */
    @Test
    fun `a request the route policy refuses does not navigate and is still consumed`() {
        val request = mutableStateOf<String?>(null)
        composeRule.setContent {
            PayanamNavHost(
                screens = screens,
                language = UiLanguage.ENGLISH,
                // `habits` is the tab minimal mode excludes, so the policy refuses this route here.
                minimalModeEnabled = true,
                landingRoute = PayanamTab.Tasks.route,
                isLandingReady = true,
                topLevelNavigationRequest = request.value,
                onTopLevelNavigationRequestConsumed = { request.value = null },
            )
        }
        composeRule.waitForIdle()
        assertThat(composedRoutes.last()).isEqualTo(PayanamTab.Tasks.route)

        request.value = PayanamTab.Habits.route
        composeRule.waitForIdle()

        // Refused: the graph stays put and the refused destination never composes.
        assertThat(composedRoutes).doesNotContain(PayanamTab.Habits.route)
        composeRule.onNodeWithText(bodyText(PayanamTab.Tasks.route)).assertIsDisplayed()
        // ...and it never even arrived there. This is the assertion that separates a real pre-check from
        // letting the graph move and relying on the back-stack guard to pop it back: the guard leaves the
        // refused route in the navigation log, so a mutate-away of this check is caught here and nowhere else.
        assertThat(navigation.routes).doesNotContain(PayanamTab.Habits.route)
        assertThat(request.value).isNull()
    }

    /**
     * A passphrase change the platform wants to end its own process over: the host leaves the
     * destination AND hands the platform its restart duty.
     *
     * This is the whole seam, exercised through the real graph. Both halves are asserted because either
     * alone would pass against a defect: a host that popped and never called the duty (the old behaviour,
     * which the desktop would keep forever) and a host that called the duty without popping (which would
     * strand the user on a screen whose database key no longer matches).
     */
    @Test
    fun `a passphrase change pops the destination and hands the platform its restart duty`() {
        val restartDuties = mutableListOf<String>()
        val driveScreens = RecordingScreens(composedRoutes, offerPassphraseChangeFlow = true)
        composeRule.setContent {
            PayanamNavHost(
                screens = driveScreens,
                language = UiLanguage.ENGLISH,
                minimalModeEnabled = false,
                landingRoute = PayanamTab.Settings.route,
                isLandingReady = true,
                onRestartAfterPassphraseChange = { restartDuties += "exit" },
            )
        }
        composeRule.waitForIdle()

        // Reached through the graph's own routes - settings, then passphrase_change - rather than by
        // starting on the destination, so the back stack this pops back through is a real one.
        composeRule.onNodeWithText(RecordingScreens.OPEN_CHANGE_LABEL).performClick()
        composeRule.waitForIdle()
        assertThat(composedRoutes.last()).isEqualTo(RecordingScreens.PASSPHRASE_CHANGE)

        composeRule.onNodeWithText(RecordingScreens.SUCCESS_LABEL).performClick()
        composeRule.waitForIdle()

        // The platform's duty ran exactly once, and the destination behind it was left the way the host
        // has always left it.
        assertThat(restartDuties).containsExactly("exit")
        assertThat(composedRoutes.last()).isEqualTo(PayanamTab.Settings.route)
    }

    /**
     * A platform that supplies no duty is unaffected - the pop is the host's own and always was.
     *
     * The Android case, pinned so the seam cannot quietly become required: there the change has already
     * ended the process by the time success is reported, so Android supplies nothing and must keep
     * behaving exactly as it did.
     */
    @Test
    fun `a passphrase change with no platform duty still leaves the destination`() {
        val driveScreens = RecordingScreens(composedRoutes, offerPassphraseChangeFlow = true)
        composeRule.setContent {
            PayanamNavHost(
                screens = driveScreens,
                language = UiLanguage.ENGLISH,
                minimalModeEnabled = false,
                landingRoute = PayanamTab.Settings.route,
                isLandingReady = true,
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(RecordingScreens.OPEN_CHANGE_LABEL).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(RecordingScreens.SUCCESS_LABEL).performClick()
        composeRule.waitForIdle()

        assertThat(composedRoutes.last()).isEqualTo(PayanamTab.Settings.route)
    }

    /** The text a fake destination renders, so an assertion names the route it came from. */
    private fun bodyText(route: String): String = "body:$route"

    /**
     * A completed forgot-passphrase reset continues into the DATABASE-INIT gate, and that gate is TOLD.
     *
     * THE DEFECT THIS PINS (build 2038, owner's device log): the reset used to be sent to the passphrase-setup
     * gate, which both platforms' own startup decisions contradict - "no database artifacts" is the init
     * gate's own condition on Android (`resolveShouldShowDatabaseInit`) and on the desktop (the scaffold
     * branch). The platform then re-resolved its gates, the graph's gate effect moved the user off the setup
     * screen, and the flow ended on the tour and the create/import chooser over a database the user had just
     * asked to be replaced.
     *
     * TWO ASSERTIONS, and the second is the load-bearing one: the destination change says WHERE the reset
     * went, and the recorded flag says WHAT it was told - which is what makes the gate open at its create
     * form (the passphrase for the replacing database is collected there) rather than at the first-run pair.
     */
    @Test
    fun `a completed forgot-passphrase reset continues into the database-init gate and tells it so`() {
        val resetScreens = RecordingScreens(composedRoutes, offerForgotPassphraseResetFlow = true)
        composeRule.setContent {
            PayanamNavHost(
                screens = resetScreens,
                language = UiLanguage.ENGLISH,
                minimalModeEnabled = false,
                landingRoute = PayanamTab.Lenses.route,
                isLandingReady = false,
                shouldShowPassphraseUnlock = true,
            )
        }
        composeRule.waitForIdle()
        assertThat(composedRoutes.last()).isEqualTo(RecordingScreens.PASSPHRASE_UNLOCK)

        composeRule.onNodeWithText(RecordingScreens.RESET_LABEL).performClick()
        composeRule.waitForIdle()

        assertThat(composedRoutes.last()).isEqualTo(RecordingScreens.DATABASE_INIT)
        // The production route string, not the fake's own tag: what the navigation library accepted is the
        // thing this half asserts, and the shared route table is its definition.
        assertThat(navigation.routes).contains(PayanamRoutes.DATABASE_INIT)
        assertThat(resetScreens.lastDatabaseInitResumesAfterReset).isTrue()
    }

    /**
     * Every destination, rendering its route as text and recording that it composed.
     *
     * The bodies are deliberately inert: the host owns the routes, the arguments and the gates, and this
     * fixture exists to exercise exactly that half. Completion callbacks are never invoked - a fake that
     * fired `onConfigured` would be testing a flow this test does not assert.
     */
    private class RecordingScreens(
        private val composedRoutes: MutableList<String>,
        /**
         * When true, the fake `settings` body offers a way into the passphrase-change destination and that
         * destination's body offers a way to report a successful change, so a test can drive the host's
         * own outcome through the graph without a real screen.
         *
         * Off by default, because a fake that offered this on its own would be exercising a flow no other
         * assertion here looks at.
         */
        private val offerPassphraseChangeFlow: Boolean = false,
        /**
         * When true, the fake unlock body offers the control that reports a COMPLETED forgot-passphrase
         * reset to the host, so a test can drive the reset leg through the graph without a real screen.
         *
         * Off by default, for the same reason as [offerPassphraseChangeFlow]: a fake that fired the reset on
         * its own would exercise a flow no other assertion here looks at.
         */
        private val offerForgotPassphraseResetFlow: Boolean = false,
    ) : PayanamNavHostScreens {
        /**
         * What the last composition of the init destination was told about the reset.
         *
         * Recorded rather than inferred: the graph navigates to this gate for a first run AND for the reset,
         * and only the flag tells the two apart - so an assertion that read the landing route alone could
         * not notice the flag being dropped.
         */
        var lastDatabaseInitResumesAfterReset: Boolean? = null

        @Composable
        private fun body(route: String) {
            composedRoutes += route
            // `BasicText`, not Material3's `Text`: `:core:ui` declares Material3 as an `implementation`
            // dependency, so it is on the runtime classpath but NOT on this module's compile classpath.
            // Foundation is (through `compose.desktop.currentOs`), and `BasicText` publishes the same text
            // semantics `onNodeWithText` reads.
            BasicText("body:$route")
        }

        @Composable
        override fun passphraseSetup(
            nav: PayanamNavActions,
            onConfigured: () -> Unit,
        ) = body(PASSPHRASE_SETUP)

        @Composable
        override fun passphraseUnlock(
            nav: PayanamNavActions,
            onUnlocked: () -> Unit,
            onForgotPassphraseReset: () -> Unit,
        ) {
            body(PASSPHRASE_UNLOCK)
            if (offerForgotPassphraseResetFlow) {
                BasicText(text = RESET_LABEL, modifier = Modifier.clickable { onForgotPassphraseReset() })
            }
        }

        @Composable
        override fun passphraseChange(
            nav: PayanamNavActions,
            onPassphraseChanged: () -> Unit,
        ) {
            body(PASSPHRASE_CHANGE)
            if (offerPassphraseChangeFlow) {
                BasicText(text = SUCCESS_LABEL, modifier = Modifier.clickable { onPassphraseChanged() })
            }
        }

        @Composable
        override fun databaseInit(
            nav: PayanamNavActions,
            onDatabaseReady: () -> Unit,
            resumesAfterReset: Boolean,
        ) {
            lastDatabaseInitResumesAfterReset = resumesAfterReset
            body(DATABASE_INIT)
        }

        @Composable
        override fun onboardingIntro(onFinished: () -> Unit) = body(ONBOARDING_INTRO)

        @Composable
        override fun securityStateUnavailable(
            onSecurityResetRequested: () -> Unit,
            onQuitRequested: () -> Unit,
        ) = body(SECURITY_STATE_UNAVAILABLE)

        @Composable
        override fun focusModeSelection(
            nav: PayanamNavActions,
            onPresetSelected: (FocusModePreset) -> Unit,
        ) = body(FOCUS_MODE_SELECTION)

        @Composable
        override fun tasks(nav: PayanamNavActions) = body(PayanamTab.Tasks.route)

        @Composable
        override fun habits(nav: PayanamNavActions) = body(PayanamTab.Habits.route)

        @Composable
        override fun time(
            nav: PayanamNavActions,
            openStartTrackingDialogRequestId: Long?,
            onOpenStartTrackingDialogHandled: (Long) -> Unit,
            openStopTrackingDialogRequestId: Long?,
            onOpenStopTrackingDialogHandled: (Long) -> Unit,
        ) = body(PayanamTab.Time.route)

        @Composable
        override fun notes(nav: PayanamNavActions) = body(PayanamTab.Notes.route)

        @Composable
        override fun journal(nav: PayanamNavActions) = body(PayanamTab.Journal.route)

        @Composable
        override fun lenses(nav: PayanamNavActions) = body(PayanamTab.Lenses.route)

        @Composable
        override fun settings(nav: PayanamNavActions) {
            body(PayanamTab.Settings.route)
            if (offerPassphraseChangeFlow) {
                BasicText(
                    text = OPEN_CHANGE_LABEL,
                    modifier = Modifier.clickable { nav.navigate(PayanamRoutes.PASSPHRASE_CHANGE) },
                )
            }
        }

        @Composable
        override fun addTask(nav: PayanamNavActions) = body(ADD_TASK)

        @Composable
        override fun taskDetail(
            nav: PayanamNavActions,
            taskId: String,
        ) = body(TASK_DETAIL)

        @Composable
        override fun editTask(
            nav: PayanamNavActions,
            taskId: String,
        ) = body(EDIT_TASK)

        @Composable
        override fun scoreDetail(
            nav: PayanamNavActions,
            type: String,
            key: String,
        ) = body(SCORE_DETAIL)

        @Composable
        override fun scoringConfig(nav: PayanamNavActions) = body(SCORING_CONFIG)

        companion object {
            // Distinct literal tags rather than the shared route table: a fixture that read its own
            // expectations from production constants could not notice those constants changing.
            // The fake `settings` body's control: navigate to the passphrase-change destination.
            const val OPEN_CHANGE_LABEL = "open:passphrase-change"

            /** The fake passphrase-change body's control: report a successful change to the host. */
            const val SUCCESS_LABEL = "success:passphrase-change"

            /** The fake unlock body's control: report a COMPLETED forgot-passphrase reset to the host. */
            const val RESET_LABEL = "reset:forgot-passphrase"

            const val PASSPHRASE_SETUP = "gate:passphrase-setup"
            const val PASSPHRASE_UNLOCK = "gate:passphrase-unlock"
            const val PASSPHRASE_CHANGE = "gate:passphrase-change"
            const val DATABASE_INIT = "gate:database-init"
            const val FOCUS_MODE_SELECTION = "gate:focus-mode-selection"
            const val ONBOARDING_INTRO = "gate:onboarding-intro"
            const val SECURITY_STATE_UNAVAILABLE = "gate:security-state-unavailable"
            const val ADD_TASK = "route:add-task"
            const val TASK_DETAIL = "route:task-detail"
            const val EDIT_TASK = "route:edit-task"
            const val SCORE_DETAIL = "route:score-detail"
            const val SCORING_CONFIG = "route:scoring-config"
        }
    }

    /**
     * Records the graph's own navigation events, so the assertion can distinguish "a destination is drawn"
     * from "the navigation library accepted a route".
     */
    private class NavigationEventRecorder : UnifiedLogger {
        val routes = mutableListOf<String>()

        override fun d(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) = Unit

        override fun i(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) {
            if (source == DESTINATION_CHANGED) {
                (data["route"] as? String)?.let(routes::add)
            }
        }

        override fun w(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) = Unit

        override fun e(
            source: String,
            message: String,
            error: Throwable?,
            data: Map<String, Any?>,
        ) = Unit

        override fun eSync(
            source: String,
            message: String,
            error: Throwable?,
            data: Map<String, Any?>,
        ) = Unit

        private companion object {
            const val DESTINATION_CHANGED = "PayanamNavHost.destinationChanged"
        }
    }
}
