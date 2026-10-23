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
 * **The defect this records, stated as the user sees it.** Two of the seven top-level routes the rail can
 * show - `Time` and `Settings` - are destinations whose desktop bodies still refuse to render, and the
 * `Time` route is also what the app opens on by default. A fresh install that finishes first-run with the
 * full suite (or with any preset that includes Time) therefore lands on a screen that throws rather than
 * on a screen, and clicking `Time` in the rail throws the same way. The UI offers both.
 *
 * The chain is production code end to end, and each link is load-bearing for the claim:
 *  - `mapAppPreferences` reads an absent `launch_destination_route` row as `DEFAULT_LAUNCH_ROUTE`
 *    (`AppPreferencesMapper.kt`), and that default is `time`;
 *  - the same function reads an absent `tab_visible_time` row as "visible", because an absent preset row
 *    resolves to the full suite, whose visible set is every route;
 *  - `desktopLaunchRoute` therefore resolves the launch route to `TIME` (the route is visible, so the
 *    hidden-route fallback never fires) and the rail offers it;
 *  - `DesktopPayanamNavHostScreens.time(...)` raises `desktopDestinationNotMigrated`.
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
 * the fact so the choice cannot be made by accident, and asserts nothing about which answer is right.
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
     * EMPTY, and that is the claim this file now makes: every rail route renders. `SETTINGS` used to be in
     * this list and no longer throws - it renders the shared `SettingsScreen` over the shell's own rows. It
     * matters more than the other destinations because `settings` is also the documented fallback when every
     * other route is hidden (`DesktopNavigationModel`), so it was one rail click from a crash.
     *
     * `ASSISTANT` and `ASSISTANT_SETTINGS` still throw, and that is why the set is empty rather than because
     * nothing throws: they are not rail routes - they are reached from inside a screen - so they are out of
     * this file's scope and deliberately absent, exactly as before. `PASSPHRASE_CHANGE` is absent for the same
     * reason and renders the shared screen over the shared ViewModel. So "absent from this record" now means
     * two different things for those two, and only one of them is a gap -
     * `DesktopNavHostDestinationRenderTest` covers the reachable-from-a-screen destinations.
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

    @Test
    fun `the scan finds the throw sites, so the assertions above are not vacuous`() {
        // The failure mode of a source-reading test is a scan that silently matches nothing, which makes
        // every assertion on its output pass. This pins the scan against the file's own call sites.
        val allThrowingDestinations = throwingDestinations()

        assertThat(allThrowingDestinations).isNotEmpty()
        assertThat(allThrowingDestinations.keys).doesNotContain(null)
    }

    /**
     * The `DesktopTopLevelRoute`s whose override body calls `desktopDestinationNotMigrated`.
     *
     * A call site is attributed to the nearest preceding `override fun NAME(`, which is the destination it
     * sits in. The function's own declaration is not a call site: it is excluded by requiring a string
     * argument, which a declaration never has.
     */
    private fun topLevelRoutesThatThrow(): Set<DesktopTopLevelRoute> {
        val byLowercaseName = DesktopTopLevelRoute.entries.associateBy { it.name.lowercase() }
        return throwingDestinations()
            .keys
            .mapNotNull { destination -> byLowercaseName[destination.lowercase()] }
            .toSet()
    }

    /** Destination function name to the destination name its body reports, for every throw site. */
    private fun throwingDestinations(): Map<String, String> {
        val found = mutableMapOf<String, String>()
        var currentFunction: String? = null
        for (line in screensFile.readLines()) {
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
    }
}
