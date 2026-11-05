//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.navigation

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.settings.DesktopTopLevelRoute
import org.junit.Test
import java.io.File

/**
 * The desktop rail's top-level navigation, asserted **per route**.
 *
 * **Why this file exists.** The rail publishes a request and the graph runs it through one function, so a
 * single shared assertion ("the rail passes `route.storageKey`, the host calls `navigateToTopLevel`") would
 * stay green while a single route was wrong - a rail entry whose value the graph does not register is a tap
 * that reads as a no-op, and nothing told the two enums apart. This test asserts the chain for **every**
 * [DesktopTopLevelRoute], so a route added on the desktop and not on the shared side (or the reverse) fails
 * here rather than in a rendered build.
 *
 * **Three facts, one loop and one slice:**
 * 1. the value the rail publishes is a route the shared graph registers as a top-level destination;
 * 2. handing that value to the graph's one top-level navigation delivers that value and nothing else;
 * 3. the host's request effect is where the value lands - the source says it calls `navigateToTopLevel(request)`.
 *
 * The third is a source read because the host's effect runs inside the composition: the idiomatic seam the
 * desktop module already uses for exactly this (`DesktopRailNavigationWiringTest`) is not available here
 * because that test needs the desktop's own sources, and slice-asserting the one line is the honest way to
 * keep the load-bearing call named rather than assumed.
 */
class DesktopRailTopLevelRouteTest {
    private val hostSource =
        source("core/ui/src/commonMain/kotlin/io/payanam/core/ui/navigation/PayanamNavHost.kt").readText()

    @Test
    fun `every desktop rail route is a top-level route the shared graph registers`() {
        val registered =
            setOf(
                PayanamTab.Tasks.route,
                PayanamTab.Time.route,
                PayanamTab.Notes.route,
                PayanamTab.Habits.route,
                PayanamTab.Journal.route,
                PayanamTab.Lenses.route,
                PayanamTab.Settings.route,
            )

        assertThat(DesktopTopLevelRoute.entries.map { it.storageKey }.toSet()).isEqualTo(registered)
    }

    @Test
    fun `selecting each desktop rail route hands the graph that route's own value`() {
        for (route in DesktopTopLevelRoute.entries) {
            val delivered = mutableListOf<String>()
            val actions =
                PayanamNavActions(
                    navigateToRoute = { },
                    navigateToTopLevelRoute = { value ->
                        delivered += value
                        true
                    },
                    popBackStackAction = { },
                    onRestartAfterDataDelete = { },
                )

            assertThat(actions.navigateToTopLevel(route.storageKey)).isTrue()
            assertThat(delivered).containsExactly(route.storageKey)
        }
    }

    @Test
    fun `the host runs a rail request through the graph's one top-level navigation`() {
        val effect =
            slice(
                hostSource,
                "val request = topLevelNavigationRequest ?: return@LaunchedEffect",
                "onTopLevelNavigationRequestConsumed()",
            )

        assertThat(effect).contains("navigateToTopLevel(request)")
    }

    /**
     * The text between two markers, failing loudly if either is missing, so an empty slice can never make an
     * assertion vacuously true.
     */
    private fun slice(
        source: String,
        from: String,
        to: String,
    ): String {
        val start = source.indexOf(from)
        assertThat(start).isAtLeast(0)
        val end = source.indexOf(to, start)
        assertThat(end).isGreaterThan(start)
        return source.substring(start, end)
    }

    private companion object {
        /**
         * Found by walking up from the working directory: Gradle runs tests with the MODULE directory as the
         * CWD, not the repository root.
         */
        fun source(relativePath: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { it.resolve(relativePath) }
                .firstOrNull { it.isFile }
                ?: error("not found above ${File("").absolutePath}: $relativePath")
    }
}
