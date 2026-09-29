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
 * 2. [the content pane does not make its own content unbounded] reads the production `Column` and fails if
 *    any `verticalScroll` reappears below `fillMaxSize()` on it. This is the source-reading idiom
 *    `DesktopNavHostParityTest` already uses in this module for wiring nothing else records: a modifier
 *    chain is not observable from a render, because the crash depends on the measure policy of an
 *    ancestor, so a test that only renders the screen would keep passing against a re-broken pane.
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
        val source = appFile.readText()
        val paneStart = source.indexOf("private fun desktopContentPane(")
        assertThat(paneStart).isGreaterThan(0)

        // The pane's own `Column` block: from the declaration to the line that opens the graph mount.
        val columnStart = source.indexOf("Column(", paneStart)
        val columnEnd = source.indexOf("PayanamNavHost(", columnStart)
        assertThat(columnStart).isGreaterThan(paneStart)
        assertThat(columnEnd).isGreaterThan(columnStart)

        val columnBlock = source.substring(columnStart, columnEnd)
        assertThat(columnBlock).doesNotContain("verticalScroll")
        assertThat(columnBlock).contains("fillMaxSize()")
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
