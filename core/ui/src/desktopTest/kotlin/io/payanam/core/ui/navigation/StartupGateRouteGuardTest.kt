//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * The silent re-auth must never fire over a startup gate the user is standing on, asserted per route.
 *
 * **Why this file exists.** The forgot-passphrase reset deletes the database and navigates to the
 * passphrase-setup screen; a still-set "unlock required" flag - computed once, before the reset - then made
 * the graph's silent-re-auth effect send the user to an unlock screen for a database that no longer existed.
 * To the user the button did nothing, on Android and on the desktop, because the graph is shared. The effect
 * had guarded only the unlock and passphrase-change screens (two routes written out by hand) while the helper
 * beside it already enumerated the whole gate set, which is why the same class of override kept recurring one
 * screen at a time.
 *
 * **Two facts, one loop and one slice:**
 * 1. every startup gate is classified as one ([isStartupGateRoute]), and an ordinary screen is not - so a
 *    route added to the gate set is classified once, in one place, instead of per effect;
 * 2. the silent-re-auth effect in [PayanamNavHost] asks that predicate rather than comparing two routes
 *    by hand. Fact 2 is a source read because the effect runs inside the composition with no seam that can
 *    drive it from a JVM test; the sibling effects in the same file are the precedent for the guard, and
 *    this asserts the third one stops hand-rolling its own list.
 */
class StartupGateRouteGuardTest {
    private val gateRoutes =
        listOf(
            PayanamRoutes.PASSPHRASE_SETUP,
            PayanamRoutes.PASSPHRASE_UNLOCK,
            PayanamRoutes.PASSPHRASE_CHANGE,
            PayanamRoutes.DATABASE_INIT,
            PayanamRoutes.FOCUS_MODE_SELECTION,
            PayanamRoutes.SECURITY_STATE_UNAVAILABLE,
            PayanamRoutes.ONBOARDING_INTRO,
        )

    @Test
    fun `every startup gate is classified as a gate, not as a place the user was`() {
        gateRoutes.forEach { route ->
            assertThat(isStartupGateRoute(route)).isTrue()
        }
    }

    @Test
    fun `an ordinary screen is not a gate`() {
        listOf(PayanamTab.Lenses.route, PayanamTab.Settings.route).forEach { route ->
            assertThat(isStartupGateRoute(route)).isFalse()
        }
    }

    @Test
    fun `the reset's destination is a gate, which is what stops the unlock screen from overriding it`() {
        // The exact case the user hit: after a reset the graph is sent to setup, and the re-auth effect
        // must be silent. If this ever reads false, the reset button goes back to looking dead.
        assertThat(isStartupGateRoute(PayanamRoutes.PASSPHRASE_SETUP)).isTrue()
    }

    @Test
    fun `the silent re-auth effect asks the predicate instead of comparing routes by hand`() {
        val source =
            File("src/commonMain/kotlin/io/payanam/core/ui/navigation/PayanamNavHost.kt")
                .takeIf { it.exists() }
                ?: File("core/ui/src/commonMain/kotlin/io/payanam/core/ui/navigation/PayanamNavHost.kt")
        assertThat(source.exists()).isTrue()
        val text = source.readText()

        assertThat(text).contains("if (isStartupGateRoute(currentResolvedRoute))")
        // The hand-rolled pair it replaced must not come back: that is what left the other gates exposed.
        assertThat(text).doesNotContain("currentResolvedRoute == PayanamRoutes.PASSPHRASE_CHANGE\n        ) {\n            return@LaunchedEffect")
    }

    @Test
    fun `the classification is the one the graph's own route helper already described`() {
        // isStartupGateRoute is defined as the inverse of the "not somewhere the user was" set, so the two
        // can never drift into disagreeing about which routes are gates.
        gateRoutes.forEach { route ->
            assertThat(isStartupGateRoute(route)).isEqualTo(!shouldCaptureReturnRouteForUnlock(route))
        }
    }
}
