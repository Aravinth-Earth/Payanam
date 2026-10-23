//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.lenses.ScoringConfigScreen
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.domain.model.ScoringConfig
import io.payanam.domain.repository.ScoringConfigRepository
import io.payanam.presentation.lenses.ScoringConfigViewModel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The shell's content pane must not make a shared screen unrenderable.
 *
 * **The defect this pins.** `desktopContentPane` (`DesktopApp.kt`) built its `Column` as
 * `Modifier.fillMaxSize().semantics { ... }.verticalScroll(rememberScrollState()).padding(28.dp)`. A
 * `verticalScroll` parent measures its child with `maxHeight = Constraints.Infinity`, and the shared graph
 * mounted inside it is a Material3 `Scaffold` (`PayanamNavHost.kt`) that lays its body out against the
 * incoming max height. The unbounded height therefore reached `ScaffoldLayout`, which threw
 * `IllegalStateException: Size(968 x 2147483647) is out of range` - width stayed bounded, only height went
 * infinite, and the identical constraint substitution happens under a real window's constraints. Every
 * shared screen was unrenderable, so the READY shell showed nothing at all. The controlled experiment that
 * established it rendered the SAME screen in the SAME fixture with and without that one modifier: it threw
 * with it and passed without it.
 *
 * **The fix was to delete the modifier**, not to bound the graph. The pane is `fillMaxSize()` and the
 * graph's own `Scaffold` scrolls its body, so the modifier contributed nothing but the unbounded
 * constraint.
 *
 * Two cases, because a render test alone cannot fail if someone re-adds the modifier:
 *
 * 1. [the shared Scaffold screen lays out inside the content pane's modifier chain] renders a real shared
 *    screen through the pane's exact modifier chain and asserts its copy reached the tree - the
 *    observable the crash destroyed.
 * 2. [the content pane does not make its own content unbounded] reads the production pane and asserts the
 *    PROPERTY - the pane's body contains `fillMaxSize` and no scroll modifier - rather than an offset into
 *    a `Column(...)` window. This is the source-reading idiom `DesktopNavHostParityTest` already uses in
 *    this module for wiring nothing else records, and it has to be a source read because a render cannot
 *    observe the measure policy of an ancestor: the crash depended on a modifier the fixture supplies for
 *    itself, so a test that only renders the screen keeps passing against a re-broken pane. The assertion
 *    covers the whole brace-matched pane body, so hoisting the modifier chain into a val - or any other
 *    reformatting - cannot break a correct pane, while a scroll modifier anywhere in it still fails.
 *
 * The screen is held in its LOADING branch on purpose. Once loaded, `ScoringConfigScreen`'s body is a
 * `Column` with its own `.verticalScroll(...)`, which is a different constraint problem; the repository
 * below never returns so the `Scaffold` alone is exercised.
 */
class DesktopShellRenderProbeTest {
    @get:Rule
    val composeRule = createComposeRule()

    // Found by walking up from the working directory rather than by a fixed relative path: Gradle runs
    // tests with the MODULE directory as the CWD, not the repository root, and a hard-coded `..` path
    // resolved to a file that does not exist and failed the whole suite from a green-looking test.
    private val appFile: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { it.resolve("desktop/src/main/kotlin/io/payanam/desktop/DesktopApp.kt") }
            .firstOrNull { it.isFile }
            ?: error("DesktopApp.kt not found above ${File("").absolutePath}")

    @Test
    fun `the shared Scaffold screen lays out inside the content pane's modifier chain`() {
        composeRule.setContent {
            contentPaneFixture(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = CONTENT_PANE_DESCRIPTION }
                    .padding(28.dp),
            )
        }

        // The pane's own semantics tag, so the assertion cannot be satisfied by the screen alone.
        composeRule.onNodeWithContentDescription(CONTENT_PANE_DESCRIPTION).assertIsDisplayed()
        // The string comes from the shared copy table rather than a literal, so nothing in this fixture
        // could satisfy it.
        composeRule.onNodeWithText(UiStrings.of(UiLanguage.ENGLISH).locLoadingConfiguration).assertIsDisplayed()
    }

    @Test
    fun `the content pane does not make its own content unbounded`() {
        val paneBody = desktopContentPaneBody(appFile.readText())

        // The pane BINDS its content to the constraints it was given, rather than widening them: whatever
        // else the pane does, the chain starts by claiming the whole incoming box. `fillMaxSize` is read
        // without its argument so this holds whether it is written `fillMaxSize()` or passed with arguments.
        assertThat(paneBody).contains("fillMaxSize")

        // The pane NEVER hands its child an unbounded axis. This is the whole defect: a scrolling parent
        // measures its child with `Constraints.Infinity` on that axis, and the shared Material3 `Scaffold`
        // lays its body out against the incoming max height, so it threw
        // `IllegalStateException: Size(968 x 2147483647) is out of range` and every shared screen was
        // unrenderable. Horizontal is included for the same reason on the other axis.
        //
        // Why this reads source rather than rendering: a render cannot observe an ANCESTOR's measure policy,
        // and the pane's modifier chain is not reachable from a fixture - `desktopContentPane` is private and
        // takes the database session controller, the vault and the document source, so mounting it means
        // mounting the whole graph. So this asserts the PROPERTY over the pane's entire body rather than an
        // offset into it: hoisting the chain into a `paneModifier` val, reordering the branches, or adding a
        // header no longer moves the assertion, and any scroll modifier anywhere in the pane still fails it.
        assertThat(paneBody).doesNotContain("verticalScroll")
        assertThat(paneBody).doesNotContain("horizontalScroll")
    }

    /**
     * The whole source of `desktopContentPane`, brace-matched from its declaration.
     *
     * Deliberately wider than the `Column(...)` window an earlier version sliced: that window ended at
     * `PayanamNavHost(` and therefore only saw modifiers written INLINE on the `Column`, so hoisting the
     * chain into a local val made a correct pane fail for a formatting reason. Brace matching follows the
     * function wherever its modifiers are written. The two guards fail loudly if the scan ever goes wrong,
     * so a matcher bug cannot make this test pass vacuously.
     */
    private fun desktopContentPaneBody(source: String): String {
        val declaration = source.indexOf(PANE_DECLARATION)
        assertThat(declaration).isGreaterThan(0)

        val bodyStart = source.indexOf('{', declaration)
        assertThat(bodyStart).isGreaterThan(declaration)

        var depth = 0
        var index = bodyStart
        while (index < source.length) {
            when (source[index]) {
                '{' -> {
                    depth++
                }

                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart, index + 1)
                }
            }
            index++
        }
        error("${PANE_DECLARATION.trim()} has no closing brace: the brace match never balanced")
    }

    /**
     * The environment the desktop root installs around its content pane: the shared Material 3 theme and
     * the ambient `LocalUiStrings` table a shared screen reads. The screen under test is the only variable.
     */
    @Composable
    private fun contentPaneFixture(outerModifier: Modifier) {
        io.payanam.core.ui.theme.PayanamTheme(darkTheme = false) {
            CompositionLocalProvider(
                LocalUiStrings provides UiStrings.of(UiLanguage.ENGLISH),
            ) {
                Column(modifier = outerModifier) {
                    ScoringConfigScreen(
                        viewModel =
                            ScoringConfigViewModel(
                                scoringConfigRepository = NeverLoadedScoringConfigRepository(),
                            ),
                    )
                }
            }
        }
    }

    private companion object {
        const val CONTENT_PANE_DESCRIPTION = "Desktop content pane for Settings"

        /** The declaration the pane's body is brace-matched from; see [desktopContentPaneBody]. */
        const val PANE_DECLARATION = "private fun desktopContentPane("
    }
}

/**
 * A scoring configuration that never loads, so the screen stays in its non-scrollable loading branch for
 * the whole render (see the class KDoc). The write paths are never reached - the probe only mounts the
 * screen - so they fail loudly rather than pretending to be wired.
 */
private class NeverLoadedScoringConfigRepository : ScoringConfigRepository {
    override suspend fun getConfig(): ScoringConfig = awaitCancellation()

    override fun observeConfig(): Flow<ScoringConfig> = throw UnsupportedOperationException("the probe never observes")

    override suspend fun saveConfig(config: ScoringConfig) = throw UnsupportedOperationException("the probe never writes")

    override suspend fun resetToDefaults() = throw UnsupportedOperationException("the probe never writes")
}
