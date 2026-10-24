//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.savedstate.SavedState
import androidx.savedstate.read
import io.payanam.core.ui.components.PayanamAlertDialog
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.text.formatArgs
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.settings.FocusModePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The application's navigation graph, rendered identically by every platform.
 *
 * MOVED FROM `:app` - this is the keystone of the shared UI. Sharing screens without the graph hands the
 * desktop a set of screens it cannot reach, and leaves every later screen needing a per-platform call
 * site; the graph is what makes "the same app on every platform" structurally true rather than a claim
 * about individual screens.
 *
 * What changed in the move, and why:
 *
 *  - **Navigation is Compose Multiplatform navigation** (`org.jetbrains.androidx.navigation`) rather than
 *    the Android artifact, so `NavHost`/`composable`/`navArgument` exist on the desktop too. The routes,
 *    the arguments and the back-stack behaviour are the ones Android already had.
 *  - **The destination bodies arrive through [PayanamNavHostScreens].** Their wiring is a Hilt ViewModel on
 *    Android and the desktop's stores on the desktop, and Hilt does not exist off-Android, so the bodies
 *    cannot live here. This object is not a per-screen wrapper: it is one implementation per platform.
 *  - **The Android-only inputs are parameters.** The previous version read the app's
 *    `AppPreferencesViewModel` for tab visibility, the landing route and the backup-failure banner; it
 *    reached `MainActivity` through `navController.context` to request a silent unlock; and it read
 *    `FeatureFlags`/`UnifiedLogger` through Android singletons. All of it arrives as arguments now, which
 *    is also what makes the graph testable without an Activity.
 *  - **Logging goes through [AppLogger]**, the process-wide holder every platform installs its real logger
 *    into. The event names and payloads are unchanged, so an existing log grep still works.
 *
 * @param screens the destination bodies for the platform currently running.
 * @param language the language [LocalUiStrings] is installed with; supplied by the caller so this
 *   composable can label the bottom bar and can provide the table for everything below it.
 * @param minimalModeEnabled whether minimal mode is on (an Android build flag; a parameter so the desktop
 *   resolves it the same way).
 * @param tabVisibility per-tab visibility the user chose in Settings.
 * @param landingRoute the route to open on when the app is ready.
 * @param isLandingReady false while preferences are still loading - the host shows a spinner instead of
 *   guessing a start destination.
 */
@Composable
fun PayanamNavHost(
    screens: PayanamNavHostScreens,
    language: UiLanguage,
    minimalModeEnabled: Boolean,
    tabVisibility: Map<String, Boolean> = emptyMap(),
    landingRoute: String = "",
    isLandingReady: Boolean = false,
    shouldShowPassphraseUnlock: Boolean = false,
    shouldShowPassphraseSetup: Boolean = false,
    shouldShowDatabaseInit: Boolean = false,
    shouldShowFocusModeOnboarding: Boolean = false,
    shouldShowSecurityStateUnavailable: Boolean = false,
    shouldShowOnboardingIntro: Boolean = false,
    resumeToRouteAfterUnlock: String? = null,
    externalCommand: PayanamExternalNavigationCommand? = null,
    backupFailureMessage: String? = null,
    backupFailureAt: String? = null,
    onDismissBackupFailure: () -> Unit = {},
    onFocusModePresetSelected: (FocusModePreset) -> Unit = {},
    onPassphraseUnlocked: () -> Unit = {},
    onDatabaseReady: () -> Unit = {},
    onOnboardingIntroFinished: () -> Unit = {},
    onSecurityResetRequested: () -> Unit = {},
    onQuitRequested: () -> Unit = {},
    onRestartAfterDelete: () -> Unit = {},
    /**
     * The restart duty after a successful passphrase change: the platform's own exit path.
     *
     * WHY IT IS A PARAMETER RATHER THAN MORE HOST LOGIC. The host decided the whole outcome on its own -
     * it logged the success and popped back - and that was every platform got. A platform that has to end
     * its own process on success therefore could not act at all, and the desktop is exactly that platform:
     * its restart port STARTS a replacement process and RETURNS, so the process that asked for the
     * restart still holds the single-instance lease the replacement needs to take. [ProcessRestart]'s
     * contract puts ending that process on whoever asked for the restart; with no seam here the desktop's
     * success ended in a pop and the replacement instance lost the lease race.
     *
     * WHAT IT DOES NOT CHANGE. The host still pops the back stack, and still pops BEFORE calling this, so
     * a platform that supplies nothing behaves exactly as it did. That is the Android case and it is not
     * an oversight: there the change has already ended the process by the time success is reported, so
     * there is nothing left for the platform to do.
     */
    onRestartAfterPassphraseChange: () -> Unit = {},
    onExternalCommandConsumed: () -> Unit = {},
    /**
     * A top-level (tab) navigation requested from outside the graph - the desktop's navigation rail.
     *
     * Deliberately narrower than [externalCommand]: a rail tap names one of the top-level routes and nothing
     * else, so there is no payload slot here. A command that must carry one (`OpenTimeScreen` carries a
     * quick-start flag and a request id) keeps using that shape; this is not a generalisation of it.
     *
     * The caller STORES this value and never invokes anything from composition. The host navigates when it
     * can: while a startup gate is showing the request waits, and a request the route policy refuses is
     * dropped and consumed rather than left pending, so it cannot fire later when the policy changes.
     */
    topLevelNavigationRequest: String? = null,
    /** Called once the request has been honoured or refused; the caller clears its stored value. */
    onTopLevelNavigationRequestConsumed: () -> Unit = {},
    onUnlockReturnRouteConsumed: () -> Unit = {},
    onSilentUnlockRequested: (route: String) -> Unit = {},
) {
    val strings = UiStrings.of(language)
    val logger = AppLogger.current
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val currentResolvedRoute = navBackStackEntry?.resolveConcreteRoute()
    var pendingTimeQuickStartRequestId by remember { mutableStateOf<Long?>(null) }
    var pendingTimeStopTrackingRequestId by remember { mutableStateOf<Long?>(null) }
    var pendingReturnRouteAfterUnlock by remember { mutableStateOf<String?>(resumeToRouteAfterUnlock) }

    /**
     * True while the database-init gate showing is the NEXT STEP OF A RESET rather than a first run.
     *
     * The owner's ruling (2026-10-14): *"When a user is using a reset option which means user already know
     * how an app works only then they will be using the reset option. So in that case we don't need to show
     * them the onboarding flow once more."* A reset deletes the database and the credential, so the facts
     * it leaves behind are indistinguishable from a first install - which is why the reset used to be sent
     * to the passphrase-setup gate and then into the first-run flow: the tour, then the create/import
     * chooser, over a database the user had just asked to be replaced.
     *
     * The gate for "no database" on BOTH platforms is this init gate, so the reset continues HERE, and the
     * flag is what the destination is told. It is cleared when the gate reports ready, so a later first run
     * on the same process is not mistaken for the reset's continuation.
     */
    var databaseInitFollowsReset by remember { mutableStateOf(false) }

    // Check if a route is allowed given current feature flags and preferences.
    // Delegates to PayanamNavRoutePolicy for testable logic; logs outcomes for observability.
    fun isRouteAllowed(route: String): Boolean {
        val allowed = PayanamNavRoutePolicy.isAllowed(route, minimalModeEnabled)
        if (!allowed) {
            logger.d("PayanamNavHost.isRouteAllowed", "Route blocked by minimal mode", mapOf("route" to route))
        } else if (route in PayanamNavRoutePolicy.startupGateRoutes) {
            logger.d("PayanamNavHost.isRouteAllowed", "Startup gate route allowed", mapOf("route" to route))
        }
        return allowed
    }

    // Bottom navigation items filtered by tab visibility and minimal mode.
    val visibleBottomNavItems =
        remember(tabVisibility, minimalModeEnabled) {
            visiblePayanamTabs(tabVisibility = tabVisibility, minimalModeEnabled = minimalModeEnabled)
        }

    // Hide bottom bar on first-run setup routes.
    val showBottomBar =
        currentRoute != PayanamRoutes.PASSPHRASE_SETUP &&
            currentRoute != PayanamRoutes.PASSPHRASE_UNLOCK &&
            currentRoute != PayanamRoutes.PASSPHRASE_CHANGE &&
            currentRoute != PayanamRoutes.DATABASE_INIT &&
            currentRoute != PayanamRoutes.FOCUS_MODE_SELECTION
    val navigateToTopLevel: (String) -> Boolean =
        remember(navController) {
            { route ->
                // The bottom bar can be composed a frame before NavHost installs its graph, and
                // navController.graph throws inside that window - a real crash on a very early tap.
                // currentBackStackEntry does not touch the graph, so it is a safe readiness probe.
                // Returns false while the graph is not ready: callers that must not lose the
                // request (external commands) retry instead of dropping the navigation.
                if (navController.currentBackStackEntry != null) {
                    // The navigation call itself goes on `Dispatchers.Main.immediate`, which is the
                    // framework main thread on Android and the Swing EDT on the desktop. Navigation
                    // mutates the back stack and the lifecycle state of every entry in it, so it has
                    // to land on the same thread that owns the composition - the same reason Android's
                    // own navigation callbacks always run on the main looper. The probe above stays on
                    // the calling thread so its synchronous Boolean answer still means "the graph
                    // accepted this"; only the mutation is handed over. `immediate` keeps this a
                    // straight-line call whenever the caller is already there, and the hop is skipped
                    // entirely rather than deferred when it is not.
                    CoroutineScope(Dispatchers.Main.immediate).launch {
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                    true
                } else {
                    false
                }
            }
        }
    val navActions =
        remember(navController, navigateToTopLevel) {
            PayanamNavActions(
                navigateToRoute = { route ->
                    // Same rule as `navigateToTopLevel`, and for the same reason: this is the entry
                    // point Android's screens and the desktop's own screens call alike, so it is the
                    // one that must be on the main dispatcher for both.
                    CoroutineScope(Dispatchers.Main.immediate).launch {
                        navController.navigate(route)
                    }
                },
                navigateToTopLevelRoute = navigateToTopLevel,
                popBackStackAction = {
                    // Same main-dispatcher rule as `navigateToRoute`, for the same reason: popping
                    // mutates back-stack lifecycle state, so it belongs on the thread that owns the
                    // composition - the framework main thread on Android, the Swing EDT on the desktop.
                    CoroutineScope(Dispatchers.Main.immediate).launch {
                        navController.popBackStack()
                    }
                },
                onRestartAfterDataDelete = onRestartAfterDelete,
            )
        }
    // A top-level navigation requested from outside the graph (the desktop rail). Same guards as the
    // external-command effect below, and for the same reasons: a request must not navigate over a startup
    // gate, and it must answer to the SAME route policy the bottom bar answers to, or one intent would have
    // two outcomes in one file.
    LaunchedEffect(
        topLevelNavigationRequest,
        currentRoute,
        minimalModeEnabled,
        shouldShowPassphraseUnlock,
        shouldShowPassphraseSetup,
        shouldShowDatabaseInit,
        shouldShowFocusModeOnboarding,
        shouldShowSecurityStateUnavailable,
        shouldShowOnboardingIntro,
    ) {
        val request = topLevelNavigationRequest ?: return@LaunchedEffect
        if (shouldShowPassphraseUnlock || currentRoute == PayanamRoutes.PASSPHRASE_UNLOCK ||
            shouldShowPassphraseSetup || currentRoute == PayanamRoutes.PASSPHRASE_SETUP ||
            currentRoute == PayanamRoutes.PASSPHRASE_CHANGE ||
            shouldShowDatabaseInit || currentRoute == PayanamRoutes.DATABASE_INIT ||
            shouldShowFocusModeOnboarding || currentRoute == PayanamRoutes.FOCUS_MODE_SELECTION ||
            shouldShowSecurityStateUnavailable || currentRoute == PayanamRoutes.SECURITY_STATE_UNAVAILABLE ||
            shouldShowOnboardingIntro || currentRoute == PayanamRoutes.ONBOARDING_INTRO
        ) {
            // Wait rather than drop: the gate is showing, and the key list re-runs this effect when it stops.
            return@LaunchedEffect
        }
        if (isRouteAllowed(request)) {
            if (navigateToTopLevel(request)) {
                onTopLevelNavigationRequestConsumed()
            }
        } else {
            logger.w(
                "PayanamNavHost.topLevelNavigationRequest",
                "Top-level navigation request refused by route policy",
                mapOf("route" to request),
            )
            // Consumed even though it was refused: leaving it non-null would re-run on every
            // `currentRoute` change and could fire later, when the policy has moved.
            onTopLevelNavigationRequestConsumed()
        }
    }
    LaunchedEffect(
        externalCommand,
        currentRoute,
        tabVisibility,
        shouldShowPassphraseUnlock,
        shouldShowPassphraseSetup,
        shouldShowDatabaseInit,
        shouldShowFocusModeOnboarding,
        shouldShowSecurityStateUnavailable,
        shouldShowOnboardingIntro,
    ) {
        if (shouldShowPassphraseUnlock || currentRoute == PayanamRoutes.PASSPHRASE_UNLOCK ||
            shouldShowPassphraseSetup || currentRoute == PayanamRoutes.PASSPHRASE_SETUP ||
            currentRoute == PayanamRoutes.PASSPHRASE_CHANGE ||
            shouldShowDatabaseInit || currentRoute == PayanamRoutes.DATABASE_INIT ||
            shouldShowFocusModeOnboarding || currentRoute == PayanamRoutes.FOCUS_MODE_SELECTION ||
            shouldShowSecurityStateUnavailable || currentRoute == PayanamRoutes.SECURITY_STATE_UNAVAILABLE ||
            shouldShowOnboardingIntro || currentRoute == PayanamRoutes.ONBOARDING_INTRO
        ) {
            return@LaunchedEffect
        }
        when (val command = externalCommand) {
            is PayanamExternalNavigationCommand.OpenTimeScreen -> {
                val targetModuleDisabled =
                    PayanamNavRoutePolicy.isModuleDisabled(
                        route = PayanamTab.Time.route,
                        minimalModeEnabled = minimalModeEnabled,
                        tabVisibility = tabVisibility,
                    )
                if (targetModuleDisabled) {
                    // Respect a disabled module: drop the command and log it instead of opening a
                    // surface the user turned off. Consuming it keeps it from firing later when
                    // the module is re-enabled.
                    logger.w(
                        "PayanamNavHost",
                        "External navigation command blocked: target module is disabled",
                        mapOf(
                            "route" to PayanamTab.Time.route,
                            "source" to command.source,
                            "tabVisibility" to tabVisibility[PayanamTab.Time.route],
                        ),
                    )
                    onExternalCommandConsumed()
                } else if (navigateToTopLevel(PayanamTab.Time.route)) {
                    if (command.openQuickStart) {
                        pendingTimeQuickStartRequestId = command.requestId
                    }
                    if (command.openStopTracking) {
                        pendingTimeStopTrackingRequestId = command.requestId
                    }
                    logger.i(
                        "PayanamNavHost",
                        "Handled external navigation command",
                        mapOf(
                            "route" to PayanamTab.Time.route,
                            "source" to command.source,
                            "openQuickStart" to command.openQuickStart,
                            "openStopTracking" to command.openStopTracking,
                        ),
                    )
                    onExternalCommandConsumed()
                } else {
                    // The graph is not installed yet (preferences still loading). Keep the command
                    // pending; the effect re-runs as currentRoute/tabVisibility change while the
                    // app becomes ready, and the navigation is retried then.
                    logger.d(
                        "PayanamNavHost",
                        "External navigation command deferred until the navigation graph is ready",
                        mapOf(
                            "route" to PayanamTab.Time.route,
                            "source" to command.source,
                        ),
                    )
                }
            }

            null -> {
                Unit
            }
        }
    }
    LaunchedEffect(shouldShowPassphraseUnlock, currentRoute, resumeToRouteAfterUnlock) {
        if (!shouldShowPassphraseUnlock || currentResolvedRoute == null) {
            return@LaunchedEffect
        }
        if (resumeToRouteAfterUnlock != null && pendingReturnRouteAfterUnlock != resumeToRouteAfterUnlock) {
            pendingReturnRouteAfterUnlock = resumeToRouteAfterUnlock
        }
        // The unlock gate must not yank a startup gate the user is standing on. The passphrase-change
        // screen was the first case: the change closes the session itself (the rekey needs the file
        // alone), the desktop's gate follows the session LIVE, and navigating away mid-submit cancels
        // the change and rolls the backup back over a rekey that had already succeeded. The
        // forgot-passphrase reset is the second: it deletes the database and navigates to the setup
        // screen, and this effect sent the user back to an unlock screen for a database that no longer
        // exists, so the button looked dead on Android and desktop both.
        //
        // Guarding only PASSPHRASE_CHANGE (and PASSPHRASE_UNLOCK) left the other gates exposed to the
        // same override, so the condition is the whole gate set now - the same set the helper below
        // already treats as "not somewhere the user was".
        if (isStartupGateRoute(currentResolvedRoute)) {
            return@LaunchedEffect
        }
        if (shouldCaptureReturnRouteForUnlock(currentResolvedRoute)) {
            pendingReturnRouteAfterUnlock = currentResolvedRoute
            onSilentUnlockRequested(currentResolvedRoute)
        }
        logger.i(
            "PayanamNavHost",
            "Routing to passphrase unlock for silent re-auth",
            mapOf(
                "currentRoute" to currentResolvedRoute,
                "returnRoute" to (pendingReturnRouteAfterUnlock ?: "none"),
            ),
        )
        // Same main-dispatcher rule as `navigateToRoute`: a `LaunchedEffect` body runs on the
        // context its recomposer supplies, which is the main dispatcher under a normal Android
        // activity but is whatever the desktop's or a test's recomposer hands over, so the graph
        // cannot take that thread for granted when it mutates the back stack.
        CoroutineScope(Dispatchers.Main.immediate).launch {
            navController.navigate(PayanamRoutes.PASSPHRASE_UNLOCK) {
                launchSingleTop = true
            }
        }
    }
    DisposableEffect(navController) {
        // A named object rather than a lambda: the multiplatform navigation artifact declares
        // OnDestinationChangedListener as a plain interface, so Kotlin's SAM conversion does not apply.
        val listener =
            object : NavController.OnDestinationChangedListener {
                override fun onDestinationChanged(
                    controller: NavController,
                    destination: NavDestination,
                    arguments: SavedState?,
                ) {
                    val route = destination.route ?: return
                    logger.i(
                        "PayanamNavHost.destinationChanged",
                        "Navigation event",
                        mapOf("route" to route),
                    )
                    if (minimalModeEnabled && !isRouteAllowed(route)) {
                        logger.w(
                            "PayanamNavHost.backStackGuard",
                            "Popping disabled route from back stack",
                            mapOf("route" to route),
                        )
                        // This listener is answered on whichever thread performed the navigation that
                        // produced the destination, and that navigation can originate anywhere that holds
                        // a controller, so the guard pop joins them on the main dispatcher.
                        CoroutineScope(Dispatchers.Main.immediate).launch {
                            controller.popBackStack()
                        }
                    }
                }
            }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }
    // Every destination body reads its copy from the ambient table rather than taking the language as
    // a parameter, so the table is installed once here for the whole graph.
    CompositionLocalProvider(LocalUiStrings provides strings) {
        Scaffold(
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar {
                        val currentDestination = navBackStackEntry?.destination

                        visibleBottomNavItems.forEach { tab ->
                            val selected =
                                currentDestination?.hierarchy?.any {
                                    it.route == tab.route
                                } == true
                            val tabLabel = tab.title(strings)
                            NavigationBarItem(
                                modifier =
                                    Modifier.semantics {
                                        contentDescription = tabLabel
                                    },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = null,
                                    )
                                },
                                // ONE line, ellipsised. The bar has a fixed height, so a label that wraps -
                                // which a long Tamil word or a large OS text-size setting makes it do - draws
                                // a second line the bar clips mid-word ("Journ"/"al"). `maxLines = 1` keeps
                                // the label inside the bar on every language and text scale.
                                label = { Text(tabLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                alwaysShowLabel = true,
                                selected = selected,
                                onClick = {
                                    if (isRouteAllowed(tab.route)) {
                                        logger.i("PayanamNavHost", "Navigating to screen", mapOf("route" to tab.route))
                                        navigateToTopLevel(tab.route)
                                    } else {
                                        logger.w(
                                            "PayanamNavHost",
                                            "Navigation blocked by feature flags",
                                            mapOf(
                                                "route" to tab.route,
                                                "minimalModeEnabled" to minimalModeEnabled,
                                            ),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            if (!backupFailureMessage.isNullOrBlank() && !shouldShowPassphraseUnlock && currentRoute != PayanamRoutes.PASSPHRASE_UNLOCK) {
                PayanamAlertDialog(
                    onDismissRequest = onDismissBackupFailure,
                    title = { Text(strings.backupFailureDialogTitle) },
                    text = {
                        Text(
                            backupFailureAt?.let {
                                strings.backupFailureDialogMessageWithTime.formatArgs(
                                    it,
                                    backupFailureMessage,
                                )
                            } ?: strings.backupFailureDialogMessageWithoutTime.formatArgs(
                                backupFailureMessage,
                            ),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = onDismissBackupFailure) {
                            Text(strings.settingsActionDismissErrorMessage)
                        }
                    },
                )
            }
            val landingReady =
                isLandingReady && landingRoute.isNotEmpty() &&
                    !shouldShowPassphraseSetup && !shouldShowPassphraseUnlock &&
                    !shouldShowDatabaseInit && !shouldShowFocusModeOnboarding &&
                    !shouldShowSecurityStateUnavailable &&
                    !shouldShowOnboardingIntro
            // The startup gates are STATE, and the graph has to follow them. `startDestination` is read when
            // the graph is CREATED and never again - measured, not assumed: on the Linux desktop the
            // database-init gate's import completed, the platform's startup mode advanced past that gate, and
            // NOTHING navigated, so the screen stayed on `database_init`. The user saw the import button again
            // over a database that had just imported. Android dismisses the same gate the same way it raises
            // it - by clearing the flag in its own `onDatabaseReady` (`showDatabaseInit = false`) - and its
            // composition is state-driven; this effect is the graph's half of that rule.
            //
            // PASSPHRASE_UNLOCK is deliberately NOT its business: the unlock gate above owns its own effect,
            // because it carries a return route, and two navigations out of one flag change is how a screen
            // ends up flickering between two gates.
            // The refusal outranks every other gate: when the passphrase store cannot be read, the app
            // must not fall through to a first-run tour or an unlock screen it cannot honour. This effect
            // navigates to the refusal destination and leaves it there - nothing pops it, because nothing
            // behind it is usable.
            LaunchedEffect(shouldShowOnboardingIntro, currentResolvedRoute) {
                if (!shouldShowOnboardingIntro) return@LaunchedEffect
                if (currentResolvedRoute == PayanamRoutes.ONBOARDING_INTRO) return@LaunchedEffect
                // A gate effect that runs on the FIRST composition runs before the NavHost below has
                // set its graph, and navigate() on a graph-less controller throws - measured on the
                // Linux desktop's first run, where this effect crashed the app before the tour ever
                // drew. The back stack exists exactly when the graph does, so an empty back stack
                // means "not yet": the NavHost's own initial composition then lands on the gate's
                // start destination anyway, and this effect re-runs on the next state change.
                if (navController.currentBackStackEntry == null) return@LaunchedEffect
                navController.navigate(PayanamRoutes.ONBOARDING_INTRO) {
                    popUpTo(0) { inclusive = true }
                }
            }

            LaunchedEffect(shouldShowSecurityStateUnavailable, currentResolvedRoute) {
                if (!shouldShowSecurityStateUnavailable) return@LaunchedEffect
                if (currentResolvedRoute == PayanamRoutes.SECURITY_STATE_UNAVAILABLE) return@LaunchedEffect
                if (navController.currentBackStackEntry == null) return@LaunchedEffect
                navController.navigate(PayanamRoutes.SECURITY_STATE_UNAVAILABLE) {
                    popUpTo(0) { inclusive = true }
                }
            }

            LaunchedEffect(
                shouldShowPassphraseSetup,
                shouldShowDatabaseInit,
                shouldShowFocusModeOnboarding,
                currentResolvedRoute,
                landingReady,
            ) {
                val route = currentResolvedRoute ?: return@LaunchedEffect
                if (shouldShowPassphraseUnlock || route == PayanamRoutes.PASSPHRASE_UNLOCK) {
                    return@LaunchedEffect
                }
                val raisedGate =
                    when {
                        shouldShowPassphraseSetup -> PayanamRoutes.PASSPHRASE_SETUP
                        shouldShowDatabaseInit -> PayanamRoutes.DATABASE_INIT
                        shouldShowFocusModeOnboarding -> PayanamRoutes.FOCUS_MODE_SELECTION
                        else -> null
                    }
                val destination =
                    when {
                        raisedGate != null && route != raisedGate -> {
                            raisedGate
                        }

                        // Only the init gate is answered here: the setup and focus destinations navigate from
                        // their own success callbacks, and a second navigation out of this effect would race
                        // the one they already perform.
                        raisedGate == null && route == PayanamRoutes.DATABASE_INIT && landingReady -> {
                            landingRoute
                        }

                        else -> {
                            null
                        }
                    } ?: return@LaunchedEffect
                logger.i(
                    "PayanamNavHost",
                    "Startup gate moved; the graph follows it",
                    mapOf("from" to route, "to" to destination),
                )
                // Same main-dispatcher rule as the other back-stack mutations in this file: the gate
                // moves the graph, and a graph is owned by the thread its composition runs on.
                CoroutineScope(Dispatchers.Main.immediate).launch {
                    navController.navigate(destination) {
                        popUpTo(route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            val startDestination =
                remember(landingReady, landingRoute) {
                    if (landingReady) {
                        landingRoute
                    } else {
                        when {
                            // The refusal outranks every other gate (see the effect above): a store that
                            // cannot be read must not fall through to a first-run tour or an unlock
                            // screen it cannot honour.
                            shouldShowSecurityStateUnavailable -> PayanamRoutes.SECURITY_STATE_UNAVAILABLE

                            shouldShowPassphraseSetup -> PayanamRoutes.PASSPHRASE_SETUP

                            shouldShowPassphraseUnlock -> PayanamRoutes.PASSPHRASE_UNLOCK

                            shouldShowDatabaseInit -> PayanamRoutes.DATABASE_INIT

                            shouldShowOnboardingIntro -> PayanamRoutes.ONBOARDING_INTRO

                            shouldShowFocusModeOnboarding -> PayanamRoutes.FOCUS_MODE_SELECTION

                            else -> null
                        }
                    }
                }
            if (startDestination == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                return@Scaffold
            }
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(PayanamRoutes.PASSPHRASE_SETUP) {
                    screens.passphraseSetup(
                        nav = navActions,
                        onConfigured = {
                            val nextRoute =
                                when {
                                    shouldShowDatabaseInit -> PayanamRoutes.DATABASE_INIT
                                    shouldShowFocusModeOnboarding -> PayanamRoutes.FOCUS_MODE_SELECTION
                                    else -> PayanamTab.Lenses.route
                                }
                            logger.i("PayanamNavHost", "Passphrase configured, continuing startup flow", mapOf("nextRoute" to nextRoute))
                            // A screen's own success callback, not a composition scope: the platform's view
                            // model answers it on the dispatcher that model runs on, so the navigation is
                            // handed to the main dispatcher rather than assumed to be there.
                            CoroutineScope(Dispatchers.Main.immediate).launch {
                                // Same double-transition guard as the unlock callback below: clearing the
                                // setup gate changes `startDestination`, so the graph may already be on
                                // `nextRoute` and a second push would pop an entry that never reached CREATED.
                                if (navController.currentBackStackEntry?.resolveConcreteRoute() != nextRoute) {
                                    navController.navigate(nextRoute) {
                                        popUpTo(PayanamRoutes.PASSPHRASE_SETUP) { inclusive = true }
                                    }
                                }
                            }
                        },
                    )
                }
                composable(PayanamRoutes.PASSPHRASE_UNLOCK) {
                    screens.passphraseUnlock(
                        nav = navActions,
                        onUnlocked = {
                            logger.i("PayanamNavHost", "Passphrase unlocked; continuing startup flow")
                            onPassphraseUnlocked()
                            val nextRoute =
                                when {
                                    shouldShowDatabaseInit -> PayanamRoutes.DATABASE_INIT
                                    shouldShowFocusModeOnboarding -> PayanamRoutes.FOCUS_MODE_SELECTION
                                    pendingReturnRouteAfterUnlock != null -> pendingReturnRouteAfterUnlock!!
                                    else -> PayanamTab.Lenses.route
                                }
                            pendingReturnRouteAfterUnlock = null
                            onUnlockReturnRouteConsumed()
                            // The unlock callback is answered from the database work that finished it, which
                            // is not the composition's thread: navigating from there moved a back-stack
                            // entry's state off the thread that owns it and the graph threw
                            // `Method setCurrentState must be called on the main thread`.
                            CoroutineScope(Dispatchers.Main.immediate).launch {
                                // Guard against the transition being performed twice. Clearing the unlock
                                // gate changes `startDestination`, so the NavHost rebuilds the graph and
                                // lands on `nextRoute` by itself. Navigating as well pops that freshly built
                                // entry before it reaches CREATED, which the lifecycle registry rejects with
                                // "State must be at least 'CREATED' to be moved to 'DESTROYED'" on the
                                // `lenses` route (measured, build 2054). Whoever got there first wins.
                                if (navController.currentBackStackEntry?.resolveConcreteRoute() != nextRoute) {
                                    navController.navigate(nextRoute) {
                                        popUpTo(PayanamRoutes.PASSPHRASE_UNLOCK) { inclusive = true }
                                    }
                                }
                            }
                        },
                        onForgotPassphraseReset = {
                            logger.w("PayanamNavHost", "Forgot-passphrase reset completed; continuing into the database-init gate")
                            // Same rule as the unlock callback above, and for the same reason: this arrives
                            // from the reset work, not from the composition.
                            //
                            // WHY THE INIT GATE AND NOT THE PASSPHRASE-SETUP GATE. The reset deleted the
                            // database AND the credential, and both platforms' own startup decisions answer
                            // "no database artifacts" with the init gate (Android:
                            // `resolveShouldShowDatabaseInit` -> true; desktop: the scaffold branch ->
                            // `InitializeDatabase`). Sending the user to the setup gate instead put the graph
                            // on a screen both platforms' facts said was not the gate - so the graph's own
                            // gate effect moved it on, and the setup screen was left behind with its
                            // passphrase unconfigured. The init gate is where the passphrase is collected
                            // for a database that does not exist yet (its own create form), and it is where
                            // the mandatory life dimensions are confirmed - the one step a REPLACED database
                            // genuinely needs again. See `DatabaseInitScreen.beginAtDatabaseCreation`.
                            databaseInitFollowsReset = true
                            CoroutineScope(Dispatchers.Main.immediate).launch {
                                navController.navigate(PayanamRoutes.DATABASE_INIT) {
                                    popUpTo(PayanamRoutes.PASSPHRASE_UNLOCK) { inclusive = true }
                                }
                            }
                        },
                    )
                }
                composable(PayanamRoutes.PASSPHRASE_CHANGE) {
                    screens.passphraseChange(
                        nav = navActions,
                        onPassphraseChanged = {
                            logger.i("PayanamNavHost", "Passphrase changed successfully")
                            // On the main dispatcher for the same reason `navigateToRoute` is: popping
                            // moves a back-stack entry through its lifecycle states, and on the desktop
                            // this callback arrives on the test's interaction thread while `Dispatchers.Main`
                            // is the Swing EDT, so popping here would move an entry's state off the thread
                            // that owns it. `immediate` makes it a plain call when the caller is already there.
                            CoroutineScope(Dispatchers.Main.immediate).launch {
                                navController.popBackStack()
                            }
                            // The host's own outcome is unchanged and happens first; the platform's
                            // restart duty runs after it, so a desktop that must end this process gets to.
                            onRestartAfterPassphraseChange()
                        },
                    )
                }

                // Database initialization screen (shown only on first launch or when no DB)
                composable(PayanamRoutes.DATABASE_INIT) {
                    screens.databaseInit(
                        nav = navActions,
                        // The reset's own continuation: the destination opens at its create form rather
                        // than offering the first-run tour and the create/import chooser. Cleared below, in
                        // the same breath as the ready report, so the flag cannot outlive the gate it
                        // describes.
                        resumesAfterReset = databaseInitFollowsReset,
                        onDatabaseReady = {
                            logger.i(
                                "PayanamNavHost",
                                "Database init reported ready; delegating startup flow re-evaluation",
                                mapOf("from" to "DatabaseInit", "followedReset" to databaseInitFollowsReset),
                            )
                            databaseInitFollowsReset = false
                            onDatabaseReady()
                        },
                    )
                }

                // The first-run tour, reached the same way every other startup gate is.
                composable(PayanamRoutes.ONBOARDING_INTRO) {
                    screens.onboardingIntro(onFinished = onOnboardingIntroFinished)
                }

                // The fail-closed refusal. It is a destination rather than platform chrome so that a
                // platform that cannot read its passphrase store has exactly one surface to show, drawn
                // by the shared screen and reached the same way every other startup gate is.
                composable(PayanamRoutes.SECURITY_STATE_UNAVAILABLE) {
                    screens.securityStateUnavailable(
                        onSecurityResetRequested = onSecurityResetRequested,
                        onQuitRequested = onQuitRequested,
                    )
                }

                // Focus Mode Selection screen (shown after database init on first launch)
                composable(PayanamRoutes.FOCUS_MODE_SELECTION) {
                    screens.focusModeSelection(
                        nav = navActions,
                        onPresetSelected = { preset ->
                            logger.i(
                                "PayanamNavHost",
                                "Focus mode preset selected, navigating to Lenses screen",
                                mapOf("preset" to preset.presetId),
                            )
                            onFocusModePresetSelected(preset)
                            // Same main-dispatcher rule as the other screen callbacks: the preset arrives on
                            // the dispatcher that loaded it, and popping the gate mutates back-stack state.
                            CoroutineScope(Dispatchers.Main.immediate).launch {
                                navController.navigate(PayanamTab.Lenses.route) {
                                    popUpTo(PayanamRoutes.FOCUS_MODE_SELECTION) { inclusive = true }
                                }
                            }
                        },
                    )
                }
                composable(PayanamTab.Tasks.route) {
                    screens.tasks(nav = navActions)
                }
                composable(PayanamTab.Time.route) {
                    screens.time(
                        nav = navActions,
                        openStartTrackingDialogRequestId = pendingTimeQuickStartRequestId,
                        onOpenStartTrackingDialogHandled = { handledRequestId ->
                            if (pendingTimeQuickStartRequestId == handledRequestId) {
                                pendingTimeQuickStartRequestId = null
                            }
                        },
                        openStopTrackingDialogRequestId = pendingTimeStopTrackingRequestId,
                        onOpenStopTrackingDialogHandled = { handledRequestId ->
                            if (pendingTimeStopTrackingRequestId == handledRequestId) {
                                pendingTimeStopTrackingRequestId = null
                            }
                        },
                    )
                }
                composable(PayanamTab.Notes.route) {
                    screens.notes(nav = navActions)
                }
                composable(PayanamTab.Habits.route) {
                    screens.habits(nav = navActions)
                }
                composable(PayanamTab.Journal.route) {
                    screens.journal(nav = navActions)
                }
                composable(PayanamTab.Lenses.route) {
                    screens.lenses(nav = navActions)
                }
                composable(PayanamTab.Settings.route) {
                    screens.settings(nav = navActions)
                }

                // Non-bottom-nav routes
                composable(PayanamRoutes.ADD_TASK) {
                    screens.addTask(nav = navActions)
                }
                composable(
                    route = PayanamRoutes.TASK_DETAIL,
                    arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val taskId = backStackEntry.arguments?.read { getStringOrNull("taskId") } ?: return@composable
                    screens.taskDetail(nav = navActions, taskId = taskId)
                }
                composable(
                    route = PayanamRoutes.SCORE_DETAIL,
                    arguments =
                        listOf(
                            navArgument("type") { type = NavType.StringType },
                            navArgument("key") { type = NavType.StringType },
                        ),
                ) { backStackEntry ->
                    val typeName = backStackEntry.arguments?.read { getStringOrNull("type") } ?: return@composable
                    val key = backStackEntry.arguments?.read { getStringOrNull("key") } ?: return@composable
                    logger.i("PayanamNavHost", "Opening score detail", mapOf("type" to typeName, "key" to key))
                    screens.scoreDetail(nav = navActions, type = typeName, key = key)
                }
                composable(
                    route = PayanamRoutes.EDIT_TASK,
                    arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val taskId = backStackEntry.arguments?.read { getStringOrNull("taskId") } ?: return@composable
                    screens.editTask(nav = navActions, taskId = taskId)
                }
                composable(PayanamRoutes.SCORING_CONFIG) {
                    screens.scoringConfig(nav = navActions)
                }
            }
        }
    }
}

/** The concrete route for the entry currently on the back stack, arguments expanded. */
internal fun NavBackStackEntry.resolveConcreteRoute(): String? {
    val route = destination.route ?: return null
    val taskId = arguments?.read { getStringOrNull("taskId") }
    return resolveConcreteRoute(route, taskId)
}
