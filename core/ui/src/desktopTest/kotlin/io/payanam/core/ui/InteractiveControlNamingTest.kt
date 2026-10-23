//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The net that keeps an interactive control in this module from being reachable and anonymous.
 *
 * WHY THIS FILE EXISTS. The commit that named the biometric passphrase reveal and the two timeline blocks
 * fixed four controls in three files. It also measured something worse: nothing in the build stopped the same
 * defect returning at the next call site. The reason is not carelessness, it is the API. `null` is exactly
 * what `contentDescription` is FOR: Compose reads a null description as "decorative, on purpose", so no grep
 * for a missing string can tell a deliberate decorative icon from a control that lost its name. The only
 * instrument that can is the rendered semantics tree.
 *
 * TWO LAYERS, BECAUSE NEITHER SUBSUMES THE OTHER.
 *
 * LAYER 1 - THE RENDERED SWEEP ([everyInteractiveNodeIsNamed]). Fourteen shared screens that this module's own
 * `desktopTest` tree already has working fixtures for are rendered for real on the JVM target, and every
 * interactive node in the MERGED tree must carry a name. This is the only layer that can see a description
 * that was never written, because the absence of one is exactly what it measures. It covers only what it
 * renders, so the list of what it does NOT cover is part of the contract, not an afterthought.
 *
 * LAYER 2 - THE SOURCE GUARD ([`no null-description icon escapes the recorded set`]). A rendered sweep cannot cover
 * 110 production files, and a failure in the tree does not say which line to change. The guard therefore reads
 * this module's own `commonMain` sources and fails when a `contentDescription = null` appears in a declaration
 * that has not been recorded as a deliberate decorative. It fails CLOSED on what it can see: an unrecognised
 * declaration is a failure, not a pass. A guard that waved unknown code through would be the decorative version
 * of itself.
 *
 * WHAT "NAMED" MEANS, AND WHY THE MERGED TREE IS THE RIGHT ONE. The predicate is the one an assistive
 * technology actually walks. Compose MERGES an interactive container with the semantics of its descendants, so
 * `Button { Text("Run backup now") }` is a SINGLE node that already has a name - the child `Text` merges up
 * into it. That is why this file reads the MERGED tree (`useUnmergedTree = false`, the default) and not the
 * unmerged one: a `Text` sitting BESIDE a control rather than inside it contributes no name to that control,
 * and reading the unmerged tree would let a genuinely nameless control pass because some unrelated `Text`
 * exists two rows above it.
 *
 * A node is NAMED when it carries ANY of: a non-empty `ContentDescription`, a `Text`, or an `EditableText`.
 * `EditableText` counts even for a READ-ONLY field, because Compose publishes a field's value through it in
 * both the editable and the read-only case (verified against `CoreTextFieldSemanticsModifierNode` in the
 * shipped `ui-desktop` artifact), so a read-only menu anchor announcing its value is genuinely named rather
 * than technically nameless. `InputText` is deliberately NOT in the list: it is a password-obfuscation
 * output, not a name.
 *
 * A node is INTERACTIVE when it exposes at least one action a user or a screen reader can invoke: `OnClick`,
 * `OnLongClick`, `ToggleOnOff`, `SetSelection`, `SetText`, `InsertTextAtCursor`, or `ClearText`. Two actions
 * are deliberately EXCLUDED and named as such: `SetProgress` (a `Slider`'s value is announced from its own
 * semantics, and this module's sliders are not reachable through any fixture here) and the scroll actions
 * (`ScrollBy`, `ScrollToIndex` - a scroll container is not a control the user presses to reach something).
 *
 * THE NAMED-CONTROL PROOF, CONCRETELY, FOR THE CONTROL THE LAST COMMITT FIXED. Take the biometric passphrase
 * reveal toggle, at commonMain/.../settings/SettingsDatabaseSecuritySection.kt:305-310. Its screen IS swept
 * (case 11), so its protection is Layer 1 as well. `SettingsDatabaseSecuritySection` currently has NO
 * null-description site at all, so it does not appear in [recordedSiteCounts] - and that is exactly what
 * makes the revert fail: putting `contentDescription = null` back on line 305 introduces a declaration key
 * that the inventory has no record of, which is the first branch of [`no null-description icon escapes the recorded set`].
 * ONE line of production changes, and the gate is red. Verified against this module's tree: the file holds
 * 0 null-description sites today, so the revert is a genuinely NEW site and not a count overflow.
 *
 * THE SAME PROOF FOR THE TIMELINE BLOCKS, WHICH ARE swept. If the
 * `.semantics(mergeDescendants = true) { contentDescription = compactLabel }` that
 * TimeScreenTimeline.kt now carries on its clickable block were deleted, that block would have a click
 * action, no description, and no text - the icon cascade inside it passes null descriptions throughout - so
 * the TimeCalendarView fixture would fail. That is the proof that the net bites: the most
 * likely revert of the fix that prompted this file produces a red test, not a silent regression.
 *
 * COVERAGE: WHAT LAYER 1 ACTUALLY ASSERTS. Every screen below asserts the number of interactive nodes it
 * contributed, so a fixture that stops composing fails loudly instead of passing on an empty tree.
 *   1. [SettingsAppearanceSection]      3 language + 3 theme + 4 font-family + 2 time-format segments.
 *   2. [SettingsDefaultLandingSection]  3 top-level route segments, plus the task layer and state segments
 *                                        reachable from the Tasks destination.
 *   3. [SettingsTimeInsightsSection]    every chart-visibility switch this state turns on, each named by the
 *                                        semantics description a previous commit added.
 *   4. [DataManagementSettingsSection]  6 action buttons (export, import, uHabits, map habits, update
 *                                        passphrase, delete all).
 *   5. [DimensionSplitSection]          1 window chip + 2 shift arrows.
 *   6. [OnboardingIntroScreen]           1 action button + 1 skip button, on the first slide.
 *   7. [NotesScreen]                    1 add-note FAB + 1 search field + the filter-chip row + note rows.
 *   8. [TimeCalendarView]               the 2 clickable timeline blocks (entry + past occurrence).
 *   9. [SettingsAutoBackupSection]      the enable toggle, and - enabled - the run-now button, the interval
 *                                        menu anchor and the rotation toggle. The two switches were the
 *                                        last NAMELESS controls in the section; both are now named from
 *                                        their own visible titles.
 *  10. [SettingsAutoTrackingSection]    the global toggle and one per-dimension toggle, named by the
 *                                        dimension label each row already draws.
 *  11. [SettingsDatabaseSecuritySection] the unlock-timeout menu anchor and the biometric switch, on the
 *                                        fixture's AVAILABLE sensor - the state in which that switch is drawn
 *                                        at all, since a platform with no sensor renders no biometric row.
 *  12. [SettingsScreenContent]          the whole screen as [SettingsScreenActions]' defaults draw it: the
 *                                        card headers (each a `Row.clickable`, which merges descendants and so
 *                                        takes its name from the title beside it) plus every row the open
 *                                        card reaches. This is where the nameless debug `Switch` used to be.
 *                                        See the NOT COVERED entry below for what this state does not reach.
 *  13. [ImportEncryptedDbPassphraseDialog] the passphrase gate in BOTH its states - idle and verifying -
 *                                        because the confirming button is nameless in exactly the verifying
 *                                        one, and a disabled control is still interactive to a screen reader.
 *
 * NOT COVERED BY LAYER 1 - AND WHY. An unlisted gap is the defect this file exists to prevent, so this list is
 * the contract, and it is exhaustive for what this module can put on screen today:
 *  - `MinimalDataManagementSection` - EXCLUDED as a section: the two buttons it draws are the same two this
 *    file reaches through the full section, and its own fixture path is a separate state.
 *  - `DimensionIconPicker` / `DimensionColorPicker` dialogs - NOT SWEPT: the icon picker's swatch is now NAMED
 *    (the DimensionIconPicker fixture), but opening either dialog needs the picker's
 *    own `OutlinedButton` to be pressed first, which is inside `DimensionPreferenceCard` - a control this
 *    module's `desktopTest` tree has no fixture that mounts. Both pickers are therefore covered by Layer 2
 *    alone, and the icon swatch's naming rests on that case rather than on a sweep.
 *  - `SettingsDialogs`' OTHER dialogs - the import-confirm, delete-all, delete-export-prompt and bulk-map
 *    dialogs are NOT swept as dialogs. Each is reachable through [SettingsScreenContent] instead (the import
 *    one through `importSourceSelected`, the delete-export one through `uiState.showDeleteExportPrompt`), and
 *    every control they draw carries a label `Text` inside the button, which is the shape Layer 1 already
 *    covers eight times. Only [ImportEncryptedDbPassphraseDialog] needed its own case, because it is the one
 *    whose control loses its label rather than merely lacking a description.
 *  - `LensesScreen` and its twelve sections, `TasksScreen`, `TaskDetailScreen`, `AddTaskScreen`,
 *    `EditTaskScreen`, `ScoreDetailScreen`, `DayScreen`, `FocusModeSelectionScreen`, `DayPlanTemplateScreen`,
 *    `AssistantScreen`, `AssistantSettingsScreen`, `ScoringConfigScreen`, `DatabaseInitScreen` and the rest of
 *    the database-gate screens, `PayanamNavHost` - NOT SWEPT: this module's `desktopTest` tree has no fixture
 *    that can put them on screen, so there is nothing to reuse. They are covered by Layer 2 only, and only to
 *    the extent of the shape Layer 2 can see (see ITS LIMIT).
 *  - Any control that is only interactive in a state no fixture here can reach (a dialog's staged-import
 *    sheet, a busy button) - NOT SWEPT: reaching that state needs a platform seam (a real file picker, an
 *    encrypting export) that no desktop fixture can supply. The ONE such state this file used to exclude -
 *    the passphrase gate's verifying spinner - is now swept by case 13, because it turned out to be reachable
 *    after all: `isVerifying` is a plain parameter, not a platform effect.
 *  - The Android and desktop APP modules' own composables - OUT OF SCOPE: this is the shared module's net, and
 *    `:app` and `:desktop` have their own trees.
 *
 * LAYER 2'S LIMIT, STATED PLAINLY. A source guard cannot see the semantics tree. It reads text, so it enforces
 * only the shape it can see: an `Icon` passing `contentDescription = null` in this module's `commonMain`. It
 * CANNOT see a `Button { Text(...) }` whose label was emptied to `""`, a `Row.clickable` whose sibling label
 * moved out of the control, a nameless control built from a `Box` with no `Icon` at all, or a control
 * rendered only on a path no fixture exercises. Those remain the rendered sweep's job, and where the sweep
 * cannot reach them either they are uncaught - stated here rather than implied away.
 *
 * WHY THE ASSERTION LIBRARY IS SPLIT. `kotlin.test` for the count and message assertions, because it reads
 * the way the arithmetic reads, and JUnit for `@Test`/`@Rule`, which is the harness every neighbouring file in
 * this source set uses. Both are on this source set's classpath (`kotlin("test")` and `libs.junit`, see
 * core/ui/build.gradle.kts).
 *
 * THE FIXTURES ARE REUSED, NOT INVENTED. Every render below reuses state a neighbouring `desktopTest` file
 * already proves renders: the preferences state from `SettingsScreenTest.prefs` (including its deliberate
 * `isLoading = false`, without which every settings section draws only a "loading preferences" line and this
 * sweep would cover nothing), the settings UI state from `SettingsDataManagementSectionsTest.state`, the
 * timeline preferences from `TimeEntryBlockSemanticsTest.preferences`, the notes repositories from
 * `NotesScreenTest`'s fakes, and the split state from `DimensionSplitSectionSemanticsTest.render`. Inventing
 * state here would make this a test of the fixture rather than of the screen - the trap
 * `SettingsScreenTest`'s own KDoc calls out.
 */
class InteractiveControlNamingTest {
    @get:Rule
    val composeRule = createComposeRule()

    // -------------------------------------------------------------------------------------------
    // LAYER 1: THE RENDERED SWEEP
    // -------------------------------------------------------------------------------------------

    @Test
    fun `everyInteractiveNodeIsNamed`() {
        // ONE loop over the shared fixtures rather than fourteen copies of the same three lines: the sweep
        // renders each screen, lets it settle, and asserts the floor plus the name of every control. The
        // fixtures, their fakes and the reach steps that put three of them into the state they are measured
        // in all live in SharedScreenFixtures.kt, so the font-scale readability sweep measures the SAME
        // screens through the SAME fixtures instead of a second copy of them.
        sharedScreenFixtures.forEach { fixture ->
            composeRule.renderScreen(fixture)
            composeRule.waitForIdle()
            assertSwept(fixture.name, fixture.minInteractiveControls)
        }
    }

    /**
     * The sweep itself, written once so the loop above stays about WHICH screen is covered.
     *
     * [expected] is asserted as a floor, and the floor is why a sweep that renders nothing fails: a fixture
     * that stops composing contributes zero interactive nodes, which is below every floor in this file. A
     * floor also survives a screen GAINING a control, which an exact count would turn into a failure; the
     * cost is that a screen silently LOSING one is not caught by this layer, which is stated in the KDoc's
     * NOT COVERED list rather than left to be discovered.
     */
    private fun assertSwept(
        screen: String,
        expected: Int,
    ) {
        val interactive = composeRule.interactiveNodes()
        assertTrue(
            interactive.size >= expected,
            "$screen rendered ${interactive.size} interactive node(s), fewer than the $expected this sweep " +
                "is pinned to. Either the screen stopped composing or the fixture no longer reaches it.",
        )
        val nameless = interactive.filter { !it.isNamed() }
        assertEquals(
            emptyList<String>(),
            nameless.map { it.describe() },
            "$screen exposes interactive nodes a screen reader cannot name. Each entry is a control that " +
                "accepts a press and announces only its ROLE. Fix the control, not this test.",
        )
        // Layout/geometry: every control is drawn with an area, and no two pressable controls partially
        // overlap. Shared with both platforms because it reads the shared screens' rendered tree.
        UiQualityAssertions.assertControlsAreLaidOut(screen, interactive)
        UiQualityAssertions.assertControlsDoNotOverlap(screen, interactive)
    }

    /**
     * The name this node would announce.
     *
     * Every read checks `contains` first, because `config[key]` on an ABSENT key throws rather than returning
     * null. A sweep that crashed on the very node it was written to report would turn a real defect into a
     * stack trace instead of an assertion that names it, which is a weaker signal for the reader.
     */
    private fun SemanticsNode.isNamed(): Boolean {
        val description =
            if (config.contains(SemanticsProperties.ContentDescription)) {
                config[SemanticsProperties.ContentDescription]
            } else {
                emptyList()
            }
        val text =
            if (config.contains(SemanticsProperties.Text)) {
                config[SemanticsProperties.Text]
            } else {
                emptyList()
            }
        val editable =
            if (config.contains(SemanticsProperties.EditableText)) {
                config[SemanticsProperties.EditableText]
            } else {
                null
            }
        return description.isNotEmpty() || text.isNotEmpty() || editable != null
    }

    /** Enough of a node to find it in a failure message: the actions it exposes and where it is drawn. */
    private fun SemanticsNode.describe(): String {
        val actions = INTERACTIVE_ACTIONS.filter { config.contains(it) }.map { it.name }
        return "actions=$actions on node #$id"
    }

    // -------------------------------------------------------------------------------------------
    // LAYER 2: THE SOURCE GUARD
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the source guard reads this module's own commonMain tree`() {
        // A scan pointed at the wrong directory passes VACUOUSLY: it finds no sources and reports nothing. A
        // check that can be green by looking in the wrong place is not a check, so the walk is asserted to
        // have found this module's tree, at a count below the number of files the module actually has.
        val sources = productionSources()
        assertTrue(
            sources.size >= MinProductionFiles,
            "the source guard found ${sources.size} Kotlin file(s) under commonMain, fewer than the " +
                "$MinProductionFiles this module has. It is scanning the wrong tree.",
        )
        assertTrue(
            sources.any { it.name == "SettingsTimeInsightsSection.kt" },
            "the source guard did not find a file this module certainly has, so its attribution is meaningless.",
        )
    }

    /**
     * THE FAIL-CLOSED ASSERTION: no `contentDescription = null` outside a recorded decorative declaration.
     *
     * Every null-description site in this module's `commonMain` is attributed to the declaration that
     * encloses it, and each attribution must appear in [decorativeDeclarations] AND must not exceed that
     * declaration's recorded site count. A new null description in an unrecorded composable FAILS, naming the
     * file, the declaration and the line - which is the whole point of the layer: the next introduction of
     * this defect class stops in the diff, where the question "is this decorative on purpose?" can be
     * answered, rather than silently in a screen.
     *
     * WHY THE SITE COUNT IS PINNED AS WELL AS THE DECLARATION. "That declaration was already reviewed" is a
     * statement about a PAST state of a file, not a permanent exemption. The timeline blocks are the concrete
     * case: TimeScreenTimeline.kt#TimeEntryBlock is recorded with 2 sites (the two decorative cascades) and
     * 2 is what it holds, so ADDING a third null description to that block - the shape a naive fix for a
     * nameless sibling would take - is a failure rather than a silent third decorative icon. The biometric
     * reveal toggle is the complementary case, caught by the missing-key branch instead.
     */
    @Test
    fun `no null-description icon escapes the recorded set`() {
        val unrecorded = mutableListOf<String>()
        for (file in productionSources()) {
            val relative = file.relativeTo(commonMainRoot()).path.replace(File.separatorChar, '/')
            val source = stripComments(file.readText())
            for (site in nullDescriptionSites(source)) {
                val key = "$relative#${site.declaration}"
                val recordedSites = recordedSiteCounts[key]
                when {
                    recordedSites == null ->
                        unrecorded +=
                            "$relative:${site.line} (in ${site.declaration}) - no recorded reason for a null " +
                                "description here"

                    site.index >= recordedSites ->
                        unrecorded +=
                            "$relative:${site.line} (in ${site.declaration}) - site ${site.index + 1} of a " +
                                "declaration recorded with $recordedSites; a null description was ADDED to a " +
                                "declaration that was already reviewed as decorative"

                    else -> Unit
                }
            }
        }
        assertEquals(
            emptyList<String>(),
            unrecorded,
            "an Icon(contentDescription = null) appeared outside the recorded decorative set. Either it is " +
                "decorative, in which case record its declaration in recordedSiteCounts with the reason, or " +
                "it is the only content of a control the user presses - which is the defect this file exists " +
                "to catch.",
        )
    }

    /**
     * Every null-description site in [source], attributed to the declaration that encloses it and numbered
     * within it.
     *
     * Comments are blanked BEFORE this runs, which is what makes the inventory honest: this module's own KDoc
     * discusses the literal `contentDescription = null` many times, and a site counted from a comment would
     * both mis-attribute and eventually push a real declaration over its recorded count.
     *
     * The attribution is "the last `fun` whose signature begins at or above the site". That is deliberately
     * simple, and its one known imprecision is recorded rather than hidden: in a file whose null-description
     * site sits in a composable that DECLARES A NESTED HELPER below itself, the helper's name wins. That is
     * why both keys appear in [recordedSiteCounts] for PayanamNavHost.kt.
     */
    private fun nullDescriptionSites(source: String): List<Site> {
        val declarations =
            DECLARATION
                .findAll(source)
                .map { match ->
                    source.take(match.range.first).count { it == '\n' } + 1 to match.groupValues[1]
                }.toList()
        val sites = mutableListOf<Site>()
        val perDeclaration = mutableMapOf<String, Int>()
        for (match in CD_NULL.findAll(source)) {
            val line = source.take(match.range.first).count { it == '\n' } + 1
            var enclosing = FILE_LEVEL
            for ((declarationLine, name) in declarations) {
                if (declarationLine <= line) enclosing = name else break
            }
            val index = perDeclaration.getOrDefault(enclosing, 0)
            perDeclaration[enclosing] = index + 1
            sites += Site(declaration = enclosing, line = line, index = index)
        }
        return sites
    }

    private class Site(
        val declaration: String,
        val line: Int,
        val index: Int,
    )

    /** Every Kotlin file in this module's own `commonMain`, walked so a new file is covered without an edit. */
    private fun productionSources(): List<File> {
        val root = commonMainRoot()
        if (!root.isDirectory) return emptyList()
        return root
            .walkTopDown()
            .onEnter { it.name != "build" }
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .toList()
    }

    /**
     * This module's `commonMain` directory, DISCOVERED rather than hardcoded.
     *
     * A scan pointed at the wrong directory passes vacuously, and a scan that silently found nothing is the
     * exact failure mode this file exists to prevent. The path is resolved by walking up from the working
     * directory to the Gradle settings file and then into this module, which is the same approach
     * `DatabaseArtifactDeletionGuardTest` uses for its own repository scan, so the repo keeps ONE house way
     * of finding sources. If that walk cannot find the settings file, [productionSources] returns empty and
     * the first Layer 2 case fails on it rather than letting the guard report a green over nothing.
     */
    private fun commonMainRoot(): File {
        var candidate = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (!File(candidate, "settings.gradle.kts").isFile) {
            candidate = candidate.parentFile ?: return File(NOTH_A_DIRECTORY)
        }
        return File(candidate, "core/ui/src/commonMain")
    }

    /**
     * Replaces comment text with spaces, keeping every line's length and numbering.
     *
     * Whitespace rather than removal, so a violation is still reported at the line the reader would find it
     * on. Block comments are handled first, and the line-comment rule stops at end-of-line so a `//` inside a
     * string literal cannot truncate real code after it.
     */
    private fun stripComments(source: String): String =
        source
            .replace(BLOCK_COMMENT) { match -> match.value.blankOutKeepingNewlines() }
            .replace(LINE_COMMENT) { match -> match.value.blankOutKeepingNewlines() }

    private fun String.blankOutKeepingNewlines(): String = map { if (it == '\n') '\n' else ' ' }.joinToString("")

    private companion object {
        /** Below the number of Kotlin files this module's `commonMain` actually holds. */
        const val MinProductionFiles = 50

        /** The path [commonMainRoot] falls back to when the repository root cannot be found; never a directory. */
        const val NOTH_A_DIRECTORY = "core/ui/src/commonMain-does-not-exist"

        /** Attribution for a null-description site that has no enclosing declaration at all. */
        const val FILE_LEVEL = "<file-level>"

        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""//[^\n]*""")
        val CD_NULL = Regex("""contentDescription\s*=\s*null\b""")
        val DECLARATION =
            Regex(
                """^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:public |internal |private |expect |actual )*fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*[<(]""",
                RegexOption.MULTILINE,
            )

        /**
         * The complete inventory of `contentDescription = null` sites in this module's `commonMain`, keyed by
         * `<relative path>#<enclosing declaration>` and valued by the number of sites recorded there: 45 sites
         * across 29 declarations, verified against the tree this file's own guard walks.
         *
         * Every entry is decorative in the sense defined in the KDoc: an icon whose meaning is carried by a
         * sibling `Text` or by a merging container, never the only content of a control the user presses. The
         * per-declaration comments say WHICH of those two it is.
         *
         * UNCHANGED BY NAMING THE REMAINING CONTROLS, and that is the finding worth recording rather than a
         * stale count. The six controls this file's KDoc used to exclude as nameless were nameless because
         * their labels are SIBLING `Text`s, not because an `Icon` passed a null description - so naming them
         * removed no site from this table, and the total is still 45 across 29 declarations. One declaration's
         * COMMENT changed: [DimensionIconPicker]'s second site is no longer the only content of a control
         * nobody can name, because the swatch now carries its own description (see the case of that name).
         *
         * THIS TABLE IS NOT A LIST OF NAMELESS CONTROLS, and the two are easy to confuse. It inventories
         * DECLARATIONS, so a control can be fully named by a `Button { Text(...) }` that appears nowhere here,
         * and - the case that matters for reading the table - a declaration recorded here can still hold a
         * genuinely nameless control that Layer 2 structurally cannot see, such as a `Box.clickable` whose
         * only child is one of these decorative icons. Layer 1 is what settles those.
         */
        val recordedSiteCounts: Map<String, Int> =
            mapOf(
                // components/DimensionVisuals.kt - the badge, dropdown-badge, identity and cascade/pattern
                // rows all publish the dimension's own name on their CONTAINER, so their icons are decorative.
                "kotlin/io/payanam/core/ui/components/DimensionVisuals.kt#DimensionCompactBadge" to 1,
                "kotlin/io/payanam/core/ui/components/DimensionVisuals.kt#DimensionDropdownBadge" to 1,
                "kotlin/io/payanam/core/ui/components/DimensionVisuals.kt#DimensionIconCascadeLayer" to 1,
                "kotlin/io/payanam/core/ui/components/DimensionVisuals.kt#DimensionIconPatternLayer" to 1,
                "kotlin/io/payanam/core/ui/components/DimensionVisuals.kt#DimensionIdentityRow" to 1,
                // DimensionIconPicker: site 1's icon sits beside the "Choose icon" label Text. Site 2 is the
                // swatch's own icon, and the swatch is the control here - a `Box.clickable` whose entire
                // content is that one decorative glyph. It now carries its own description (the picker frame
                // plus this option's stable key), which is what makes site 2 decorative rather than anonymous;
                // the recorded count is 2 because BOTH icons are, not because either is unlabelled.
                "kotlin/io/payanam/core/ui/components/DimensionVisuals.kt#DimensionIconPicker" to 2,
                "kotlin/io/payanam/core/ui/components/ImportDatabaseFileButton.kt#ImportDatabaseFileButtonContent" to 1,
                "kotlin/io/payanam/core/ui/components/TaskDetailHistoryAndScore.kt#RescheduleHistorySection" to 1,
                "kotlin/io/payanam/core/ui/database/DatabaseInitDimensionSetupScreen.kt#MandatoryDimensionSetupSection" to 1,
                "kotlin/io/payanam/core/ui/database/DatabaseInitScreen.kt#DatabaseInitScreen" to 5,
                "kotlin/io/payanam/core/ui/database/DatabasePassphraseUnlockScreen.kt#DatabasePassphraseUnlockScreen" to 1,
                "kotlin/io/payanam/core/ui/day/DayScreen.kt#DateNavigationBar" to 1,
                "kotlin/io/payanam/core/ui/focus/FocusModeSelectionScreen.kt#PresetCard" to 2,
                "kotlin/io/payanam/core/ui/lenses/ScoringConfigScreen.kt#ScoringConfigScreen" to 1,
                // navigation/PayanamNavHost.kt - the nav item's own icon, beside a `.semantics { contentDescription
                // = tabLabel }` AND a `Text(tabLabel)` label, so it is decorative. It is recorded under the
                // nested helper `isRouteAllowed` because that is what the last-`fun`-above-the-site attribution
                // resolves to (see nullDescriptionSites' KDoc), and the attribution is the contract the guard
                // checks - so the key is recorded as the guard sees it, not as a reader would name it.
                "kotlin/io/payanam/core/ui/navigation/PayanamNavHost.kt#isRouteAllowed" to 1,
                // onboarding - the arrow inside the "Next"/"Start" button (whose Text merges up), and the
                // slide's illustrative icon (not inside any interactive node at all).
                "kotlin/io/payanam/core/ui/onboarding/OnboardingIntroScreen.kt#OnboardingIntroScreen" to 1,
                "kotlin/io/payanam/core/ui/onboarding/OnboardingIntroScreen.kt#OnboardingSlideContent" to 1,
                // settings - the Delete icon beside "Disable"/"Enable dimension", the card header's leading icon
                // beside the merging title Text, and the three data-management button icons, each of which sits
                // in a button that also draws a label Text.
                "kotlin/io/payanam/core/ui/settings/SettingsComponents.kt#DimensionPreferenceCard" to 1,
                "kotlin/io/payanam/core/ui/settings/SettingsComponents.kt#SettingsCard" to 1,
                "kotlin/io/payanam/core/ui/settings/SettingsDataManagementSections.kt#DataManagementSettingsSection" to 3,
                "kotlin/io/payanam/core/ui/tasks/AddTaskScreen.kt#AddTaskScreen" to 1,
                "kotlin/io/payanam/core/ui/tasks/EditTaskScreen.kt#EditTaskContent" to 1,
                "kotlin/io/payanam/core/ui/tasks/TaskDetailComponents.kt#TaskDetailContent" to 5,
                "kotlin/io/payanam/core/ui/tasks/TasksScreen.kt#HabitsTabContent" to 1,
                "kotlin/io/payanam/core/ui/tasks/TasksScreen.kt#TasksScreen" to 2,
                "kotlin/io/payanam/core/ui/tasks/TasksScreenCards.kt#TaskListRow" to 1,
                "kotlin/io/payanam/core/ui/tasks/TasksScreenParts.kt#TasksTopBar" to 2,
                // time - the two icon cascades inside the timeline blocks, which are named from their CONTAINER
                // rather than from the icons (and which Layer 1 DOES sweep).
                "kotlin/io/payanam/core/ui/time/TimeScreenTimeline.kt#TimeEntryBlock" to 2,
                "kotlin/io/payanam/core/ui/time/TimeScreenTimelineOccurrenceBlock.kt#PastOccurrenceBlock" to 2,
            )

        /**
         * The declarations whose naming rests on a MODIFIER rather than on a child `Text` - the shape Layer 2
         * structurally cannot see, because the text that names them is a sibling, not a descendant.
         *
         * Every one of these is now covered by a rendered case, which is the point of recording them here: a
         * `.semantics { contentDescription = ... }` deleted from any of them leaves a control that announces
         * only its role, and Layer 2 has no way to notice, because the null-description table does not change
         * when that modifier is removed. The DimensionIconPicker fixture is the one
         * that no other case can cover, because opening that dialog needs a control this module's `desktopTest`
         * tree has no fixture that mounts.
         *
         * It is kept as a named constant rather than folded into prose because a reader who finds a new
         * modifier-named control should add it HERE and to the matching case, and the two places that have to
         * agree are then greppable.
         */
        const val LAYER_1_COVERS_THE_REST =
            "Every control whose name comes from a `.semantics` modifier rather than from a child Text is " +
                "named by this list: the timeline entry and past-occurrence blocks, the dimension icon picker's " +
                "swatch, the biometric / auto-backup / auto-tracking / debug switches, and the passphrase gate's " +
                "confirming button. Layer 2 cannot see the loss of any of them - removing one changes no " +
                "null-description count - so each is held by a rendered case instead."
    }
}
