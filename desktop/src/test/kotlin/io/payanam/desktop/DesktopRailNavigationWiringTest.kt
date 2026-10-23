//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * The rail's wiring, which no render can observe.
 *
 * **Why this exists.** The navigation rail, the shell surface and the content pane are all `private` to
 * `DesktopApp.kt`, so a test cannot mount them: no fixture can click the production rail. The behaviours the
 * fix rests on are therefore not readable from a render at all - they are a set of call-site facts. This is the
 * source-reading idiom `DesktopNavHostParityTest` and `DesktopShellRenderProbeTest` already use in this module
 * for the same reason.
 *
 * **The mutants this is built to catch**, each named because a source assertion is only worth its lines if
 * something can survive it otherwise:
 *
 * 1. the rail selects its route but never publishes a request, so the graph never moves;
 * 2. the graph is mounted without its request parameters, so every rail tap is a silent no-op - the failure
 *    looks identical to success from the outside;
 * 3. the start destination goes back to tracking the rail's selection, which reinstalls the graph on every tap
 *    and discards per-tab state and drill-downs - the defect this whole change removes;
 * 4. the holder is never passed to the shell, or the callbacks channel never carries the request.
 *
 * **Every slice asserts its own markers first.** A source-reading test that slices between two absent strings
 * and then checks `doesNotContain` passes while asserting nothing, which is how this class of test goes
 * quietly dead; the markers make an empty parse a failure.
 */
class DesktopRailNavigationWiringTest {
    private val appSource = source("desktop/src/main/kotlin/io/payanam/desktop/DesktopApp.kt").readText()
    private val supportSource =
        source("desktop/src/main/kotlin/io/payanam/desktop/DesktopAppSupport.kt").readText()

    @Test
    fun `the rail still highlights its selection and also publishes a graph request`() {
        val railBlock = slice(appSource, "onNavigate = { route ->", "sessionLogger.i(")

        // The highlight and the reset guard both read `activeRoute`, so the old call must survive...
        assertThat(railBlock).contains("callbacks.onRouteSelected(route)")
        // ...and the request is what actually moves the graph.
        assertThat(railBlock).contains("callbacks.onRailRouteRequested(route.storageKey)")
    }

    @Test
    fun `the graph is mounted with its request channel and without the rail as start destination`() {
        // The end marker is the mount's last argument, not the next declaration: the rail is declared BEFORE
        // this pane, so a following-declaration marker would not be found and the parse would fail rather
        // than slice.
        val pane = slice(appSource, "private fun desktopContentPane(", "onRestartAfterDelete = callbacks.onQuitRequested,")

        assertThat(pane).contains("topLevelNavigationRequest = topLevelNavigationRequest")
        assertThat(pane).contains("onTopLevelNavigationRequestConsumed = onTopLevelNavigationRequestConsumed")
        // The defect: a start destination that tracks the rail's selection makes the library rebuild the
        // graph on every tap and clear every saved back-stack key.
        assertThat(pane).doesNotContain("state.activeRoute.storageKey")
    }

    @Test
    fun `the request holder is owned by the shell and handed to it`() {
        val shellCall = slice(appSource, "val sessionLandingRoute =", "passphraseUnlockCollaborators =")

        assertThat(shellCall).contains("topLevelNavigationRequest = railRouteRequest")
        assertThat(shellCall).contains("onTopLevelNavigationRequestConsumed = { railRouteRequest = null }")
        assertThat(shellCall).contains("landingRoute = sessionLandingRoute.storageKey")
    }

    @Test
    fun `the callbacks channel carries the rail request from declaration to its single call site`() {
        assertThat(supportSource).contains("val onRailRouteRequested: (String) -> Unit,")
        assertThat(supportSource).contains("onRailRouteRequested = onRailRouteRequested,")
        assertThat(appSource).contains("onRailRouteRequested = { key -> railRouteRequest = key },")
    }

    /**
     * The text between two markers, failing loudly if either is missing.
     *
     * The slice is guaranteed non-empty by construction, which is the point: every `doesNotContain` below is
     * meaningless against an empty string.
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
         * Found by walking up from the working directory rather than by a fixed relative path: Gradle runs
         * tests with the MODULE directory as the CWD, not the repository root.
         */
        fun source(relativePath: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { it.resolve(relativePath) }
                .firstOrNull { it.isFile }
                ?: error("not found above ${File("").absolutePath}: $relativePath")
    }
}
