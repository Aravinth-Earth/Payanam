//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.navigation.PayanamTab
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import org.junit.Test
import java.io.File

/**
 * Discovery probe: renders the app's screens on the DESKTOP at a chosen window size, font scale and
 * LANGUAGE, and writes, per screen, a PNG, a geometry dump and an overflow list.
 *
 * **Why it exists.** The desktop is claim-scoped, and the milestone's goal is FEATURE PARITY with Android
 * in functionality, UI and layout. A pixel comparison is dominated by font and density, so this probe emits
 * NORMALISED GEOMETRY (each node's bounds as a fraction of the window), which is what
 * `build-tools/scripts/parity-screen-diff.py` compares against the Android probe's dump.
 *
 * **The language matters to the diff.** The reference device runs Tamil, so a Tamil sweep and Tamil deep
 * scenes are rendered alongside the English ones — a diff between an English desktop and a Tamil device
 * flags every label and measures nothing.
 *
 * **What it renders.**
 *  - The seven top-level tabs, at the shipped text scale, at a LARGE scale (1.3), at a NARROW width (320
 *    dp), and in Tamil.
 *  - The deep screens a tab sweep cannot reach: the Add Task form, the Add Task recurrence picker dialog,
 *    an expanded Settings section, the Settings scoring card, and the Notes editor.
 *
 * **How to read the output.** `*.geometry.txt` is every labelled node's bounds normalised to the window
 * (0..1). `*._overflow.txt` names nodes whose bounds leave the window HORIZONTALLY. The PNG is best-effort
 * (a dialog owns its own root); `*.image.txt` records whether it was captured.
 *
 * This is an instrument, not a gate: it asserts only that the artefacts were written.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopLayoutProbeTest : DesktopAppJourneyHarness() {
    @Test
    fun `the top-level tabs at the shipped text scale`() = runTabSweep("tabs-fs0.8", DESKTOP_TEXT_SCALE, ENGLISH)

    @Test
    fun `the top-level tabs at a large text scale`() = runTabSweep("tabs-fs1.3", 1.3f, ENGLISH)

    @Test
    fun `the top-level tabs at a narrow width`() =
        runTabSweepAtWidth("tabs-narrow-320", DESKTOP_TEXT_SCALE, widthDp = 320, language = ENGLISH)

    /** The sweep the parity diff reads against the Tamil device. */
    @Test
    fun `the top-level tabs in Tamil`() = runTabSweep("tabs-ta", DESKTOP_TEXT_SCALE, TAMIL)

    @Test
    fun `the Add Task form`() =
        runScene("add-task", ENGLISH) { s ->
            clickTab(PayanamTab.Tasks, s)
            onAllNodesWithContentDescription(s.locAddTask).onFirst().performClick()
            waitForIdle()
        }

    @Test
    fun `the Add Task recurrence dialog`() =
        runScene("add-task-recurrence", ENGLISH) { s ->
            clickTab(PayanamTab.Tasks, s)
            onAllNodesWithContentDescription(s.locAddTask).onFirst().performClick()
            waitForIdle()
            onAllNodesWithText(s.locRecurring).onFirst().performClick()
            waitForIdle()
        }

    @Test
    fun `an expanded Settings section`() =
        runScene("settings-appearance", ENGLISH) { s ->
            clickTab(PayanamTab.Settings, s)
            onAllNodesWithText(s.settingsAppearanceTitle).onFirst().performClick()
            waitForIdle()
        }

    @Test
    fun `a Settings section with a card open`() =
        runScene("settings-scoring", ENGLISH) { s ->
            clickTab(PayanamTab.Settings, s)
            onAllNodesWithText(s.settingsScoringTitle).onFirst().performClick()
            waitForIdle()
        }

    // ---- Tamil scenes, matching the reference device for the parity diff -------------------------

    @Test
    fun `the Add Task form in Tamil`() =
        runScene("add-task-ta", TAMIL) { s ->
            clickTab(PayanamTab.Tasks, s)
            onAllNodesWithContentDescription(s.locAddTask).onFirst().performClick()
            waitForIdle()
        }

    @Test
    fun `the Add Task recurrence dialog in Tamil`() =
        runScene("add-task-recurrence-ta", TAMIL) { s ->
            clickTab(PayanamTab.Tasks, s)
            onAllNodesWithContentDescription(s.locAddTask).onFirst().performClick()
            waitForIdle()
            onAllNodesWithText(s.locRecurring).onFirst().performClick()
            waitForIdle()
        }

    @Test
    fun `an expanded Settings section in Tamil`() =
        runScene("settings-appearance-ta", TAMIL) { s ->
            clickTab(PayanamTab.Settings, s)
            onAllNodesWithText(s.settingsAppearanceTitle).onFirst().performClick()
            waitForIdle()
        }

    @Test
    fun `the Notes editor in Tamil`() =
        runScene("notes-editor-ta", TAMIL) { s ->
            clickTab(PayanamTab.Notes, s)
            onAllNodesWithContentDescription(s.locAddNote).onFirst().performClick()
            waitForIdle()
        }

    // ---- render shapes --------------------------------------------------------------------------

    /** Walks the seven top-level tabs at the shipped width. */
    private fun runTabSweep(
        name: String,
        fontScale: Float,
        language: UiLanguage,
    ) = runTabSweepAtWidth(name, fontScale, PHONE_WINDOW_WIDTH_DP, language)

    /** Walks the seven top-level tabs at an EXPLICIT width, writing artefacts per tab. */
    private fun runTabSweepAtWidth(
        name: String,
        fontScale: Float,
        widthDp: Int,
        language: UiLanguage,
    ) = runAtWindowSize(widthDp = widthDp, heightDp = PHONE_WINDOW_HEIGHT_DP) {
        mountProbe(language, fontScale)
        val s = UiStrings.of(language)
        val dir = File(runDirectory, name).apply { mkdirs() }
        TABS.forEach { tab ->
            onAllNodesWithText(tab.title(s)).onFirst().performClick()
            waitForIdle()
            writeArtefacts(dir, tab.route, widthDp, PHONE_WINDOW_HEIGHT_DP)
        }
        assertThat(dir.listFiles()?.size ?: 0).isAtLeast(TABS.size * 3)
    }

    /** Renders ONE scene, writing a frame + geometry + overflow list for it. */
    private fun runScene(
        name: String,
        language: UiLanguage,
        navigate: DesktopComposeUiTest.(UiStrings) -> Unit,
    ) = runAtWindowSize(widthDp = PHONE_WINDOW_WIDTH_DP, heightDp = PHONE_WINDOW_HEIGHT_DP) {
        mountProbe(language, DESKTOP_TEXT_SCALE)
        val dir = File(runDirectory, name).apply { mkdirs() }
        navigate(UiStrings.of(language))
        writeArtefacts(dir, "screen", PHONE_WINDOW_WIDTH_DP, PHONE_WINDOW_HEIGHT_DP)
        assertThat(dir.listFiles()?.size ?: 0).isAtLeast(3)
    }

    /** Installs the production fixture and mounts the shell in [language]. */
    private fun DesktopComposeUiTest.mountProbe(
        language: UiLanguage,
        fontScale: Float,
    ) {
        installProductionFixtureLayer()
        mountShell(
            state = configuredState(),
            isLandingReady = true,
            preferences = loadedPreferences(systemLanguageTag = languageTagOf(language)),
            language = language,
            fontScale = fontScale,
        )
    }

    private fun DesktopComposeUiTest.writeArtefacts(
        dir: File,
        base: String,
        widthDp: Int,
        heightDp: Int,
    ) {
        File(dir, "$base.geometry.txt").writeText(geometryDump(widthDp, heightDp))
        File(dir, "$base._overflow.txt").writeText(overflowFindings(widthDp))
        // The PNG is best-effort: a DIALOG owns a separate root, so `onRoot()` can refuse to capture one.
        // The status is written either way, so a scene always leaves the same number of artefacts and a
        // missing PNG is recorded as a reason rather than as a short file count.
        File(dir, "$base.image.txt").writeText(captureRootImage(File(dir, "$base.png")))
    }

    // ---- extraction -----------------------------------------------------------------------------

    /**
     * Every labelled node, in tree order, with its bounds NORMALISED to the window (0..1).
     *
     * Normalised, not px: the device renders at density 2.625 and the desktop at 1, so raw px cannot be
     * compared. A control at `L=0.10, R=0.90` on both is the same layout; one at `L=0.10` and `L=0.40` is a
     * divergence. The surface size is passed in, so a narrow-width sweep normalises by ITS own width.
     */
    private fun DesktopComposeUiTest.geometryDump(
        widthDp: Int,
        heightDp: Int,
    ): String =
        labelledNodes(widthDp, heightDp).joinToString("\n") { node ->
            val rect = node.rect
            "%-55s click=%-5s L=%.3f T=%.3f R=%.3f B=%.3f w=%.3f h=%.3f".format(
                node.label.take(55),
                node.click.toString(),
                rect[0],
                rect[1],
                rect[2],
                rect[3],
                rect[2] - rect[0],
                rect[3] - rect[1],
            )
        }

    /** Nodes whose bounds leave the window HORIZONTALLY. */
    private fun DesktopComposeUiTest.overflowFindings(widthDp: Int): String =
        labelledNodes(widthDp, PHONE_WINDOW_HEIGHT_DP)
            .filter { it.rect[0] < -EDGE_TOLERANCE || it.rect[2] > 1f + EDGE_TOLERANCE }
            .joinToString("\n") {
                "HORIZONTAL-OVERFLOW '${it.label.take(50)}' L=%.3f R=%.3f".format(it.rect[0], it.rect[2])
            }

    /**
     * Activates a top-level tab by its FIRST match.
     *
     * The harness's [goToTab] uses `onNodeWithText`, which throws when a tab's label is ALSO the current
     * screen's title (the Tasks rail item and the Tasks heading both read "Tasks"). This takes the first
     * match, which is the rail control.
     */
    private fun DesktopComposeUiTest.clickTab(
        tab: PayanamTab,
        strings: UiStrings,
    ) {
        onAllNodesWithText(tab.title(strings)).onFirst().performClick()
        waitForIdle()
    }

    /** A labelled, non-empty node with its bounds normalised to the window. */
    private class LabelledNode(
        val label: String,
        val click: Boolean,
        val rect: FloatArray,
    )

    private fun DesktopComposeUiTest.labelledNodes(
        widthDp: Int,
        heightDp: Int,
    ): List<LabelledNode> =
        onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { node ->
                val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
                val cd = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
                val tag = node.config.getOrNull(SemanticsProperties.TestTag)
                val label = (text ?: cd ?: tag)?.replace("\n", " ")
                if (label.isNullOrBlank()) return@mapNotNull null
                val r = node.boundsInRoot
                if (r.width <= 0f || r.height <= 0f) return@mapNotNull null
                LabelledNode(
                    label = label,
                    click = node.config.contains(SemanticsActions.OnClick),
                    rect =
                        floatArrayOf(
                            r.left / widthDp,
                            r.top / heightDp,
                            r.right / widthDp,
                            r.bottom / heightDp,
                        ),
                )
            }

    private companion object {
        /** Two nodes sharing a boundary edge are adjacent, not overflowing. */
        const val EDGE_TOLERANCE = 0.005f

        /** The top-level tabs, in the order the shell offers them. */
        val TABS =
            listOf(
                PayanamTab.Tasks,
                PayanamTab.Habits,
                PayanamTab.Time,
                PayanamTab.Journal,
                PayanamTab.Notes,
                PayanamTab.Lenses,
                PayanamTab.Settings,
            )

        /** The reference device runs Tamil, so the parity diff needs Tamil scenes too. */
        val TAMIL = UiLanguage.TAMIL
        val ENGLISH = UiLanguage.ENGLISH

        fun languageTagOf(language: UiLanguage): String =
            when (language) {
                UiLanguage.TAMIL -> "ta"
                else -> "en"
            }
    }
}
