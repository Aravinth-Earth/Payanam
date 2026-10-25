//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.preferences.mapAppPreferences
import io.payanam.shared.settings.DesktopTopLevelRoute
import org.junit.Test
import java.io.File

/**
 * The desktop's nav rail and its launch route may only offer destinations the desktop can actually
 * render.
 *
 * **The claim this file makes today, in full.** Every route the rail can offer RENDERS, including the
 * `Time` route a fresh install opens on by default. This used to be false for `Time` and `Settings`;
 * `Settings` was wired first, and both now render shared screens.
 *
 * The chain is production code end to end, and each link is load-bearing for the claim:
 *  - `mapAppPreferences` reads an absent `launch_destination_route` row as `DEFAULT_LAUNCH_ROUTE`
 *    (`AppPreferencesMapper.kt`), and that default is `time`;
 *  - the same function reads an absent `tab_visible_time` row as "visible", because an absent preset row
 *    resolves to the full suite, whose visible set is every route;
 *  - `desktopLaunchRoute` therefore resolves the launch route to `TIME` (the route is visible, so the
 *    hidden-route fallback never fires) and the rail offers it.
 *
 * **Why the gaps are recorded rather than asserted as failures.** `:desktop:test` failing fails the build
 * gate, so a red-by-design test cannot ship, and one that is red for a fortnight trains everyone to
 * ignore it. The facts are held as data and compared for an EXACT match, which is the shape
 * `DesktopNavHostParityTest` already uses for the same reason. Inverted, it is the useful direction: it
 * goes red when a destination that used to throw starts rendering, when one that renders starts throwing,
 * or when the default launch route moves - in all three cases until the record is updated in the same
 * commit as the fix. A test that only asserted emptiness could not notice a gap closing, which is the
 * direction that matters.
 *
 * **What it deliberately does not decide.** Where a fresh install should land while `Time` is still
 * unrenderable is a product question - the honest answers (land on a route that renders, land on Settings,
 * or land on Time and render it) are all defensible and they are not equivalent to a user. This file records
 * the fact so the choice cannot be made by accident, and asserts nothing about which answer is right. That
 * question is now moot for `Time`, which renders.
 *
 * The working directory is `desktop/` under Gradle, so the repo root is `..`.
 */
class DesktopTopLevelDestinationRenderabilityTest {
    private val repoRoot = File("..").canonicalFile

    private val screensFile =
        File(repoRoot, "desktop/src/main/kotlin/io/payanam/desktop/DesktopPayanamNavHostScreens.kt")

    /**
     * The top-level routes whose rail entry currently leads to a destination that throws.
     *
     * EMPTY, and that is the claim this file makes: every rail route renders. `SETTINGS` used to be in
     * this list and no longer throws - it renders the shared `SettingsScreen` over the shell's own rows. It
     * matters more than the other destinations because `settings` is also the documented fallback when every
     * other route is hidden (`DesktopNavigationModel`), so it was one rail click from a crash.
     *
     * Nothing is missing from this record any more: absent from it means one thing only, that the route
     * renders.
     *
     * The assertion below is an EXACT match, so this list going empty is a claim that would be contradicted
     * the moment a rail route started throwing again - which is the direction that matters.
     */
    private val recordedUnrenderableTopLevelRoutes = emptySet<DesktopTopLevelRoute>()

    /**
     * The launch route a fresh install resolves to, with no preference rows written at all.
     *
     * Recorded rather than asserted to a better value: the mapper's own absent-row default is what decides
     * this, and the mapper is shared with Android, where `time` is correct because Android renders the Time
     * screen. Changing the default is a shared-code decision and belongs to whoever owns the Time screen.
     */
    private val recordedFreshInstallLaunchRoute = DesktopTopLevelRoute.TIME

    @Test
    fun `the top-level routes that throw are exactly the recorded ones`() {
        val unrenderable = topLevelRoutesThatThrow()

        assertThat(unrenderable).containsExactlyElementsIn(recordedUnrenderableTopLevelRoutes)
    }

    @Test
    fun `a fresh install's launch route is exactly the recorded one`() {
        // The REAL mapper, over an empty settings map, because a fresh database has no preference rows and
        // that absence is the whole input. Reading the value out of the mapper's source instead would assert
        // on a copy of the rule rather than on the rule.
        val state =
            mapAppPreferences(
                settings = emptyMap(),
                catalogRows = emptyList(),
                systemLanguageTag = "en",
                backupStatus = null,
                defaultDebugLoggingEnabled = false,
            )

        assertThat(desktopLaunchRoute(state)).isEqualTo(recordedFreshInstallLaunchRoute)
    }

//
//
//
//
//
//
//
//
//
//
//
//
//

    /**
     * The `DesktopTopLevelRoute`s whose override body calls `desktopDestinationNotMigrated`.
     *
     * A call site is attributed to the nearest preceding `override fun NAME(`, which is the destination it
     * sits in. The function's own declaration is not a call site: it is excluded by requiring a string
     * argument, which a declaration never has.
     */
    private fun topLevelRoutesThatThrow(): Set<DesktopTopLevelRoute> {
        val byLowercaseName = DesktopTopLevelRoute.entries.associateBy { it.name.lowercase() }
        return throwingDestinations(screensFile.readLines())
            .keys
            .mapNotNull { destination -> byLowercaseName[destination.lowercase()] }
            .toSet()
    }

    /**
     * Destination function name to the destination name its body reports, for every throw site in
     * [source]'s lines.
     *
     * The lines are a PARAMETER rather than a file path so the caller decides what is scanned. The
     * production entry point reads [screensFile] itself, and that is the only caller left - the
     * self-referential case that measured the scanner against a synthetic source went with the last
     * production throw site, because a test that exists to prove a test is not a claim about the app.
     */
    private fun throwingDestinations(lines: List<String>): Map<String, String> {
        val found = mutableMapOf<String, String>()
        var currentFunction: String? = null
        for (line in lines) {
            OVERRIDE.find(line)?.let { currentFunction = it.groupValues[1] }
            if (currentFunction == null) continue
            CALL.find(line)?.let { call -> found[currentFunction] = call.groupValues[1] }
        }
        return found
    }

    private companion object {
        /** `override fun time(` at the start of a line, which is how every destination body opens. */
        private val OVERRIDE = Regex("""^\s*override fun (\w+)\(""")

        /** A throw call with its argument: the declaration has a parameter list, never a quoted string. */
        private val CALL = Regex("""desktopDestinationNotMigrated\("([^"]+)"""")

//
//
//
//
//
//
    }
}
