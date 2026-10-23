//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.navigation.PayanamNavHost
import io.payanam.core.ui.navigation.PayanamNavHostScreens
import io.payanam.core.ui.navigation.PayanamRoutes
import io.payanam.core.ui.navigation.PayanamTab
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.ThemeModeOption
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.text.formatArgs
import io.payanam.core.ui.theme.PayanamTheme
import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.database.jvm.PayanamDatabaseOpener
import io.payanam.database.jvm.PayanamDatabaseSession
import io.payanam.desktop.database.DesktopAssistantCollaborators
import io.payanam.desktop.database.DesktopDatabaseInitCollaborators
import io.payanam.desktop.database.DesktopDatabasePathResolver
import io.payanam.desktop.database.DesktopDatabaseSessionController
import io.payanam.desktop.database.DesktopPassphraseChangeCollaborators
import io.payanam.desktop.database.DesktopPassphraseUnlockCollaborators
import io.payanam.desktop.database.DesktopPayanamDataLayer
import io.payanam.desktop.database.DesktopSettingsCollaborators
import io.payanam.desktop.platform.AwtDocumentSource
import io.payanam.desktop.security.DesktopBiometricUnlockPrompt
import io.payanam.desktop.security.DesktopForgottenPassphraseReset
import io.payanam.desktop.security.DesktopPassphraseVault
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.shared.database.DatabaseBootIssue
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.shared.logging.AppLogger
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Path
import java.time.Instant

/**
 * Every destination the desktop wires must resolve to a REAL shared screen - the graph half of the shell
 * probe, which nothing in the suite asserted.
 *
 * **THE GAP, stated as the regression it detects.** The pane half is asserted twice over
 * (`DesktopShellRenderProbeTest`): one case renders a shared screen through the content pane's exact
 * modifier chain, the other reads the pane's own body to prove it hands its child no unbounded axis. That
 * is a strong claim about ONE screen under ONE ancestor's measure policy - and it is exactly the scope it
 * has. The other sixteen destinations were never composed by any test in this module, because
 * `DesktopShellGraphProbeTest` - the only other file that mounts `PayanamNavHost` - mounts it with its
 * OWN `RecordingScreens`, and `DesktopNavHostParityTest` / `DesktopTopLevelDestinationRenderabilityTest`
 * read SOURCE TEXT and so cannot see a body at all. The class the desktop actually ships,
 * `DesktopPayanamNavHostScreens`, had never been composed for a single top-level destination.
 *
 * The concrete regression: one wired destination quietly stops resolving to a screen, and the desktop keeps
 * offering it. `lenses` is the shape of it - the rail offers Lenses, the pane shows whatever the body drew,
 * and a body that drew nothing is indistinguishable from a bug in the screen. Every existing assertion
 * stays green through that: the pane probe renders `ScoringConfigScreen` and not Lenses; the graph probe's
 * fake answers `lenses` with a `BasicText`; the two source-reading tests cannot see a body. So the
 * question this file asks is the only one with an answer at runtime - did a REAL screen's own copy reach
 * the tree.
 *
 * **Why the destinations are DATA.** The defect is a CLASS - "some wired destination stopped resolving" -
 * and nineteen near-identical test cases would guard it one destination at a time while leaving the rest
 * unguarded, which is the same blind spot in a different shape. So [wiredTabs] and [wiredChildRoutes] are
 * the single source of truth for what this file covers, and
 * [every shared screen method is rendered here, rendered as a gate destination by the graph, or a recorded throw site] is
 * what keeps that honest against the seam itself: a destination added to [PayanamNavHostScreens] with no
 * row here and no recorded reason FAILS, so the walk cannot silently cover less than it claims.
 *
 * **How each destination is entered, and why `landingRoute` is the honest way in.** `landingRoute` is the
 * route the graph opens when no startup gate is raised, so naming a destination there enters it through the
 * graph's own route table, its own `composable(...)` registration and its own argument handling - the
 * production path, with nothing private reached into and no hand-rolled navigation. That is also the only
 * option: the host keeps its `NavController` private and `PayanamNavActions` has no read API, so no test
 * can navigate the graph after the fact. The three PARAMETERISED routes name their concrete form
 * (`PayanamRoutes.taskDetail("...")`) because that is what `PayanamNavActions` navigates to and what the
 * navigator resolves a start destination against: `startDestinationRoute` is matched through
 * `NavDestination.matchRoute`, which substitutes `{taskId}` / `{type}` / `{key}` out of the registered
 * route pattern and carries the values in as arguments. The value is a task that does not exist - the
 * state a stale link leaves a user in - and each of those screens renders its top-bar title ABOVE its
 * loading / not-found / error branches, so the assertion holds whichever branch the fixture lands in.
 *
 * **THE FIXTURE IS THE REAL THING, and that is what makes it the right instrument.** A body that only
 * renders against a fake repository is a different defect than one that renders against the real one. The
 * database is built by `PayanamDatabaseOpener.create` - the production first-run path, which emits every
 * table from the Room schema export - rather than by hand-written `CREATE TABLE` statements. That is not
 * tidiness: these ViewModels read about twenty tables between them (`tasks`, `time_entries`,
 * `task_occurrences`, `tags`, `journal_notes`, `day_journal_entries`, `scoring_config`, `day_metrics`, the
 * `day_plan_*` family, ...) and TWO of them - `TimeTagEditorViewModel` and `TimeVisualsViewModel` - have no
 * `catch`, so a table a hand-written fixture forgot becomes an UNCAUGHT coroutine exception rather than an
 * error flag the screen can render. The real create path cannot be incomplete, so the fixture is not a
 * source of false failures. `LocalDataLayer` is installed over that session because
 * `UninstalledDataLayer` throws by design and `TasksScreen` reads the holder during composition, so a
 * destination cannot be mounted at all without it. `UnifiedLogger` is initialised because the class under
 * test resolves `UnifiedLogger.getInstance()` in a field initialiser, and it is handed to `AppLogger`
 * because the shared screens log through that holder - the same wiring `DesktopMain.kt` does - both reset
 * in `@After` as well as installed in `@Before`, because test classes in a module share one JVM.
 * `DesktopDatabasePathResolver` is INJECTED, not defaulted: the default resolves the host's real
 * `~/.local/share/Payanam`, and a render test that health-checked the developer's own database would be
 * nondeterministic and would touch real user data.
 *
 * **Why the copy asserted per destination is not the screen's title.** The graph's own Material3
 * `NavigationBar` renders one label per visible tab on EVERY non-gate route, and several of those labels
 * are the same strings the screens put in their own top bars - `settingsDatabaseTasks` is both the Tasks
 * tab's label and `TasksScreen`'s title, and likewise `locHabits`, `settingsDatabaseNotes`, `locLenses`,
 * `locJournal`, `settingsTitle`. A bare `onNodeWithText` on any of those passes against a completely EMPTY
 * pane, because the bar drew it. So every row below names copy the bar cannot produce, and the two
 * destinations served by one screen in two modes (`tasks` / `habits`, both `TasksScreen`) name copy that
 * DISCRIMINATES the mode, which is the only thing that distinguishes them:
 * `locSortTasks` renders in the top bar's actions for `TASKS_ONLY` and `locSearchHabits` for
 * `HABITS_ONLY` (`TasksScreenParts.kt`), and each is a `contentDescription` on an icon no bar label uses.
 *
 * **THE TWO ASSISTANT DESTINATIONS ARE IN THIS WALK, not recorded residue.** `assistant` and
 * `assistantSettings` used to call `desktopDestinationNotMigrated("AI assistant", "V4")` and were named in
 * [RECORDED_THROW_SITES] so the gap could not vanish silently. Both now render the SHARED screens
 * (`AssistantScreenContent`, `AssistantSettingsScreenContent`) over the SHARED `AssistantViewModel` and the
 * desktop configuration store, so they are ordinary rows in [wiredChildRoutes] - reached from INSIDE the
 * Lenses screen, which is why the rail-scoped `DesktopTopLevelDestinationRenderabilityTest` never had to
 * record them. The residue map is asserted EXACTLY, so this file fails the moment a destination starts
 * throwing again, and fails if a row here is added without accounting for it in the partition below.
 *
 * **What it does NOT claim.** That a destination's copy proves its whole body is correct - a screen could
 * render a top bar and fail below it, and `DesktopShellRenderProbeTest` owns the constraint half of that
 * class of defect. What is claimed is narrower and exactly what a placeholder or a named error destroys:
 * the body COMPOSED and drew its own screen's copy rather than nothing.
 */
class DesktopNavHostSharedScreenRenderTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val passphrase = "desktop-shared-screen-render-test"
    private val strings = UiStrings.of(UiLanguage.ENGLISH)

    private lateinit var session: PayanamDatabaseSession
    private lateinit var databaseDirectory: Path
    private lateinit var databaseFile: Path
    private lateinit var vault: DesktopPassphraseVault
    private lateinit var sessionLogger: UnifiedLogger

    @Before
    fun setUp() {
        // The class under test's `logger` field calls `UnifiedLogger.getInstance()` at CONSTRUCTION, so a
        // missing logger would fail every case here with a message about logging rather than about a
        // destination. Reset FIRST, as `DesktopNavHostDestinationRenderTest` does, so a leaked instance from
        // an earlier class cannot point this one at a deleted TemporaryFolder.
        UnifiedLogger.resetForTests()
        sessionLogger =
            UnifiedLogger.initialize(
                logsDirectory = temporaryFolder.newFolder("shared-screen-logs").toPath(),
                clock = { Instant.EPOCH },
            )
        // The shared screens and every shared ViewModel log through `AppLogger.current`, whose default is a
        // no-op. This is production's own wiring (`DesktopMain.kt`), installed so that a screen which logs
        // does not log into a void - and, more to the point here, so nothing this walk mounts depends on a
        // logger that was never provided.
        AppLogger.install(sessionLogger)

        // A REAL encrypted database at the REAL schema version, built by the production create path. See
        // the class KDoc for why this is the create path and not a hand-listed set of tables.
        databaseDirectory = temporaryFolder.newFolder("database").toPath()
        databaseFile = databaseDirectory.resolve(PayanamDatabaseSchema.DATABASE_NAME)
        session = (PayanamDatabaseOpener.create(databaseFile, passphrase) as DatabaseOpenResult.Opened).session
        // Process-wide holder: a leaked install would let a later class pass on this one's state.
        LocalDataLayer.resetForTests()
        LocalDataLayer.install(DesktopPayanamDataLayer(session, pollIntervalMs = 10L))

        vault =
            DesktopPassphraseVault(
                DesktopSecurityStore(securityDirectory = temporaryFolder.newFolder("security").toPath()),
            )
    }

    /**
     * One session controller for the whole class.
     *
     * A REFUSED open is the honest default for a render probe - mounting a destination is not an unlock -
     * and one instance rather than two, because two controllers would mean two shutdown targets and two
     * possible installations of the shared data layer. The database file is the one created above, so the
     * settings destination's artifact section lists this test's own file rather than the developer's.
     */
    private val sessionController: DesktopDatabaseSessionController by lazy {
        DesktopDatabaseSessionController(
            databaseFilePath = { databaseFile.toAbsolutePath().toString() },
            open = { _, _ ->
                DatabaseOpenResult.Refused(
                    DatabaseBootIssue(
                        type = DatabaseBootIssueType.OPEN_FAILED,
                        detailMessage = "not opened by a render probe",
                    ),
                )
            },
            logger = sessionLogger,
        )
    }

    @After
    fun tearDown() {
        // Reset in BOTH directions, for the reason `LocalDataLayer.resetForTests` documents: the `@Before`
        // guard stops this class asserting on state leaked out of an earlier one, and the `@After` guard
        // stops it leaking onward. A failing assertion aborts the test body before it can close the logger,
        // so this cannot live at the end of a case.
        LocalDataLayer.resetForTests()
        AppLogger.resetForTests()
        sessionLogger.close()
        UnifiedLogger.resetForTests()
        if (!session.isClosed) session.close()
    }

    /**
     * The seven top-level destinations the desktop's rail offers, each driven through the real graph.
     *
     * `tasks` and `habits` get a row EACH even though both render `TasksScreen`, because the only thing that
     * tells the two apart is which `TasksScreenMode` the destination passed - and asserting the mode's own
     * copy is what makes each row a claim about the route it names rather than about the screen in general.
     */
    @Test
    fun `every top level destination the desktop wires renders its own shared screen`() {
        assertEachDestinationRenders(wiredTabs)
    }

    /**
     * The five child destinations the desktop wires: the routes reached from inside a screen rather than
     * from the rail.
     *
     * In scope for the same reason the rail ones are - a screen can stop resolving, and being one tap
     * deeper does not make it unobservable.
     */
    @Test
    fun `every child destination the desktop wires renders its own shared screen`() {
        assertEachDestinationRenders(wiredChildRoutes)
    }

    /**
     * The consent gate opens on the way IN, through the entry the desktop actually ships.
     *
     * The Lenses entry is the only door to the assistant on this platform, and it is guarded: the entry card
     * raises the shared consent dialog, and only the dialog's Proceed calls the navigation that opens the
     * assistant. This case walks that door in the real graph - the Lenses destination is entered by
     * `landingRoute`, the entry card is clicked by its own copy, and the assertion is the shared dialog's
     * title, which nothing else in the tree draws.
     *
     * A regression this detects: the dialog call being replaced by a direct `onOpenAssistant()`, or the
     * entry being rewired to navigate first - either would leave the dialog's copy absent while every other
     * case in this file stays green, because the assistant destination itself still renders.
     */
    @Test
    fun `choosing the Lenses entry opens the consent gate before the assistant destination`() {
        val screens = desktopNavHostScreens()
        composeRule.setContent {
            PayanamTheme(darkTheme = false) {
                PayanamNavHost(
                    screens = screens,
                    language = UiLanguage.ENGLISH,
                    minimalModeEnabled = false,
                    landingRoute = PayanamTab.Lenses.route,
                    isLandingReady = true,
                )
            }
        }
        composeRule.waitForIdle()

        // The entry card draws only after the screen's own state read completes - the same race the
        // destination walk absorbs with `awaitCopy`, so the click waits for the card's copy to exist.
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(strings.assistantEntryCta).fetchSemanticsNodes().isNotEmpty()
        }
        // The card's own action is its CTA button - the title text is not clickable.
        composeRule.onNodeWithText(strings.assistantEntryCta).performClick()

        // The dialog, not the destination: this title is drawn by the shared consent dialog alone, and the
        // Proceed button proves the gate is actionable rather than a dead surface.
        composeRule.onNodeWithText(strings.assistantConsentTitle).assertExists()
        composeRule.onNodeWithText(strings.assistantConsentProceed).assertExists()
    }

    /**
     * The walk covers the whole wired surface, and says so out loud rather than leaving it to be believed.
     *
     * This is the check that makes the two cases above mean what their names claim. It partitions the seam -
     * every method of [PayanamNavHostScreens] as `DesktopPayanamNavHostScreens` implements it - three ways,
     * and asserts each partition is exact, so all four of the following fail this test:
     *  - a destination rendered here with no row in [wiredTabs] / [wiredChildRoutes] (the walk covering less
     *    than it claims, which is how a whole destination silently loses its only assertion);
     *  - a destination implemented by the desktop that NO file accounts for - the real drift risk, and the
     *    one this file is for;
     *  - a new `desktopDestinationNotMigrated` call site, or one that disappeared without
     *    [RECORDED_THROW_SITES] being updated, which is what keeps the residue honest in BOTH directions;
     *  - a recorded residue row naming a method the implementation no longer has, so a rename cannot leave a
     *    live throw site hiding behind a stale record.
     *
     * The five startup gates are the fourth partition and are NOT re-rendered here: they are mounted and
     * asserted, one at a time, by `DesktopNavHostDestinationRenderTest`, which owns their fixtures because
     * each gate is reached by raising a gate FLAG rather than by naming a route. Naming that file here is
     * what keeps "19 methods, 17 rendering, 2 recorded residue" an audited partition rather than a hope.
     */
    @Test
    fun `every shared screen method is rendered here, rendered as a gate destination by the graph, or a recorded throw site`() {
        val implemented = implementedDestinationNames()
        assertThat(implemented).isNotEmpty()

        val renderedHere = (wiredTabs + wiredChildRoutes).map { it.method }.toSet()
        val recorded = RECORDED_THROW_SITES.keys
        val gates = GATE_DESTINATIONS_OWNED_BY_THE_GRAPH

        // The partition is total: every implemented method is claimed exactly once, by one of the three.
        val unaccounted = implemented - renderedHere - recorded - gates
        assertThat(unaccounted).isEmpty()

        // ...and nothing is claimed that the desktop does not implement. A route named here that no method
        // backs is a typo or a removed destination, and both are failures rather than dead weight.
        val notImplemented = (renderedHere + recorded + gates) - implemented
        assertThat(notImplemented).isEmpty()

        // The residue, EXACTLY as the source has it - so wiring the assistant fails here until the record
        // is updated in the same commit that wires it.
        assertThat(desktopThrowSites()).isEqualTo(RECORDED_THROW_SITES)
    }

    /**
     * Mounts the REAL graph over the REAL desktop bodies, once per destination, asserting each one's copy.
     *
     * One composition per destination rather than one mount walked by clicking: `NavHost` builds the graph
     * when the graph is CREATED and reads `startDestination` only there, so entering a second destination
     * needs either a navigation this test cannot reach or a fresh graph. A fresh graph per destination is
     * also what keeps the cases INDEPENDENT - a destination that only renders after some other one has
     * loaded is a defect this shape cannot miss, and one shared mount would hide it.
     *
     * `isLandingReady = true` with no gate raised is the branch that opens `landingRoute`: any gate flag is
     * captured by `remember(landingReady, landingRoute)` and the destination would then never compose at all,
     * which is the mistake a reader of this fixture could most easily make. `PayanamTheme` is supplied
     * because these are Material3 screens - the production shell wraps the graph in the same theme, and the
     * graph's own `Scaffold` is what the pane probe showed has to lay out inside real constraints.
     *
     * [awaitCopy] rather than a bare `waitForIdle()`: most of these screens draw their top bar on the first
     * frame, but two of them deliberately draw NOTHING until an asynchronous read completes -
     * `SettingsScreen` returns early while `produceState` is still null and `LensesScreen` returns early
     * while its state is loading - so a single idle pass would be a race against a coroutine rather than a
     * measurement. `waitUntil` THROWS on timeout, so this cannot pass vacuously; it makes the assertion
     * robust, not optional.
     *
     * The message names the route and the copy, so a red run says which destination regressed instead of
     * leaving that to whoever reads the stack trace to work out.
     */
    private fun assertEachDestinationRenders(destinations: List<WiredDestination>) {
        for (destination in destinations) {
            val screens = desktopNavHostScreens()
            composeRule.setContent {
                PayanamTheme(darkTheme = false) {
                    PayanamNavHost(
                        screens = screens,
                        language = UiLanguage.ENGLISH,
                        minimalModeEnabled = false,
                        landingRoute = destination.route,
                        isLandingReady = true,
                    )
                }
            }
            composeRule.waitForIdle()

            try {
                awaitCopy(destination)
                destination.assertRendered(composeRule)
            } catch (failure: AssertionError) {
                throw AssertionError(
                    "Desktop destination '${destination.route}' did not render '${destination.copy}', " +
                        "which only its own shared screen draws. This is the signature of a wired " +
                        "destination that stopped resolving to a screen: either re-stubbed to " +
                        "`desktopDestinationNotMigrated(...)` or handed a placeholder pane. " +
                        "Underlying failure: ${failure.message}",
                    failure,
                )
            }
        }
    }

    /**
     * Waits until [WiredDestination.copy] is in the tree, or fails naming the destination.
     *
     * Polls the semantics tree rather than sleeping, so it costs nothing on the screens that draw their
     * chrome immediately and absorbs the genuinely asynchronous two.
     */
    private fun awaitCopy(destination: WiredDestination) {
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            destination.isPresent(composeRule)
        }
    }

    /**
     * The seven top-level routes the desktop's rail can offer, with the copy that proves each rendered.
     *
     * A property rather than a constant because the copy comes from the shared copy TABLE - resolved at
     * runtime from the installed language - so a fixture that read its own expectations from production
     * string constants could not notice those strings changing.
     */
    private val wiredTabs: List<WiredDestination>
        get() =
            listOf(
                // `locSortTasks` -> "Sort tasks": the top bar's sort control, rendered in the ACTIONS slot
                // when `effectiveTabIndex == 1`, i.e. `TasksScreenMode.TASKS_ONLY`. Under `HABITS_ONLY` the
                // actions slot renders the search control instead, so this copy cannot come from the habits
                // row - and it is not a navigation-bar label either way.
                WiredDestination(PayanamTab.Tasks.route, "tasks", CopiedBy.CONTENT_DESCRIPTION, strings.locSortTasks),
                // `locSearchHabits` -> "Search habits": the mirror image, rendered when
                // `effectiveTabIndex == 0`, i.e. `HABITS_ONLY`.
                WiredDestination(PayanamTab.Habits.route, "habits", CopiedBy.CONTENT_DESCRIPTION, strings.locSearchHabits),
                // `locAddTimeEntry` -> "Add Time Entry": `TimeScreen`'s first FAB's contentDescription,
                // rendered in the `Scaffold`'s FAB slot above every branch of its state.
                WiredDestination(PayanamTab.Time.route, "time", CopiedBy.CONTENT_DESCRIPTION, strings.locAddTimeEntry),
                // `locAddNote` -> "Add Note": `NotesScreen`'s FAB, same slot, same guarantee.
                WiredDestination(PayanamTab.Notes.route, "notes", CopiedBy.CONTENT_DESCRIPTION, strings.locAddNote),
                // `locPreviousDay` -> "Previous day": `DayScreen`'s date-navigation row, rendered BEFORE the
                // screen's own loading branch - so it is present in every state it has. This is the Journal
                // destination (`DayScreenMode.JOURNAL_ONLY`).
                WiredDestination(PayanamTab.Journal.route, "journal", CopiedBy.CONTENT_DESCRIPTION, strings.locPreviousDay),
                // `assistantTitle` -> "AI Assistance": `LensesScreen`'s assistant entry card. Chosen over the
                // screen's own top-bar title, which is "Lenses" - the Lenses TAB's label, and therefore drawn
                // by the bottom bar whether or not any body composed.
                WiredDestination(PayanamTab.Lenses.route, "lenses", CopiedBy.TEXT, strings.assistantTitle),
                // `settingsAppearanceTitle` -> "Appearance": the settings screen's own first card, against
                // the "Settings" label the bar draws.
                WiredDestination(PayanamTab.Settings.route, "settings", CopiedBy.TEXT, strings.settingsAppearanceTitle),
            )

    /** The seven non-rail routes the desktop wires, with the copy that proves each rendered. */
    private val wiredChildRoutes: List<WiredDestination>
        get() =
            listOf(
                // `locAddTask` -> "Add Task": `AddTaskScreen`'s top-bar title. Matched as TEXT on purpose -
                // `TasksScreen` uses the same string as its FAB's contentDescription, never as text.
                WiredDestination(PayanamRoutes.ADD_TASK, "addTask", CopiedBy.TEXT, strings.locAddTask),
                // `locTaskDetails` -> "Task Details": `TaskDetailScreen`'s title, rendered above its loading
                // and not-found branches alike.
                WiredDestination(
                    PayanamRoutes.taskDetail(MISSING_TASK_ID),
                    "taskDetail",
                    CopiedBy.TEXT,
                    strings.locTaskDetails,
                ),
                // `locEditTask` -> "Edit Task": `EditTaskScreen`'s title, same shape.
                WiredDestination(
                    PayanamRoutes.editTask(MISSING_TASK_ID),
                    "editTask",
                    CopiedBy.TEXT,
                    strings.locEditTask,
                ),
                // `locLensScoreDetailDay` formatted with the key: `ScoreDetailScreen`'s title, which is
                // DYNAMIC because it carries the key the route supplied. The expected string is computed the
                // way the screen computes it rather than written out, so a change to the copy table moves
                // both sides together. `type = "DAY"` is the literal the desktop's own destination compares
                // `type` against before choosing `ScoreDetailType.DAY`.
                WiredDestination(
                    route = PayanamRoutes.scoreDetail(SCORE_DETAIL_TYPE, SCORE_DETAIL_KEY),
                    method = "scoreDetail",
                    copiedBy = CopiedBy.TEXT,
                    copy = strings.locLensScoreDetailDay.formatArgs(SCORE_DETAIL_KEY),
                ),
                // `settingsScoringTitle` -> "Scoring Configuration": the scoring screen's top-bar title,
                // which renders before its own loading branch.
                WiredDestination(PayanamRoutes.SCORING_CONFIG, "scoringConfig", CopiedBy.TEXT, strings.settingsScoringTitle),
                // `assistantTitle` -> "AI Assistance": the chat screen's TOP-BAR title, rendered by
                // `AssistantScreenContent` above every branch of its state - including the loading branch,
                // which is what this fixture's session-less controller produces. The Lenses screen also draws
                // this string on its entry card, but the Lenses screen is not composed on this route, so
                // nothing else in the tree can produce it.
                WiredDestination(PayanamRoutes.ASSISTANT, "assistant", CopiedBy.TEXT, strings.assistantTitle),
                // `assistantSettingsSavedNote`: the settings editor's first row. Its top-bar title is
                // `assistantSettingsTitle`, which is ALSO the settings screen's own title - and the graph's
                // bottom bar draws that label for the Settings tab - so this row names a line only the
                // assistant editor renders, for the same reason the class KDoc gives for every other row.
                WiredDestination(
                    PayanamRoutes.ASSISTANT_SETTINGS,
                    "assistantSettings",
                    CopiedBy.TEXT,
                    strings.assistantSettingsSavedNote,
                ),
            )

    /**
     * The desktop's real destination bodies, over the desktop's real collaborators.
     *
     * The four gate collaborators are constructor ARGUMENTS of the class under test, so they must be real
     * objects rather than defaults this file never measured - even though this file renders no gate. They
     * are the same three-plus-four `DesktopApp.kt` supplies; the unlock gate's reset is built over the SAME
     * database file that gate is handed, so a reset cannot delete an artifact family it never opened.
     *
     * `settingsCollaborators.preferences` is built with `isLoading = false`, because that is what the shell's
     * own mapper publishes after a read and because `SettingsScreen`'s appearance and default-landing
     * sections early-return on `isLoading` - a default-constructed state would render the "Appearance" card
     * header with no controls inside it, which is precisely the trap `DesktopSettingsDestinationRenderTest`
     * exists to catch and which this walk must not walk into on its way past.
     */
    private fun desktopNavHostScreens(): DesktopPayanamNavHostScreens =
        DesktopPayanamNavHostScreens(
            // No fact-reload hook: these cases mount the graph, not the reset leg that fires it.
            onDatabaseResetObserved = {},
            databaseInitCollaborators =
                DesktopDatabaseInitCollaborators(
                    sessionController = sessionController,
                    // A TEST-OWNED marker, never the default: the tour's completion WRITES through this
                    // store, and the default resolves the operator's real data directory.
                    onboardingStore = DesktopOnboardingStore(securityDirectory = temporaryFolder.newFolder().toPath()),
                    documentSource = AwtDocumentSource(),
                    passphraseVault = vault,
                    databasePathResolver =
                        DesktopDatabasePathResolver(
                            // An empty directory of this test's own: the default would resolve the host's
                            // real one, which the init ViewModel health-checks.
                            databaseDirectory = { temporaryFolder.newFolder("init-probe").absolutePath },
                        ),
                ),
            passphraseUnlockCollaborators =
                DesktopPassphraseUnlockCollaborators(
                    vault = vault,
                    biometricPrompt = DesktopBiometricUnlockPrompt,
                    forgottenPassphraseReset = DesktopForgottenPassphraseReset(vault, databaseFile),
                    sessionController = sessionController,
                    databaseFile = databaseFile.toFile(),
                ),
            passphraseChangeCollaborators =
                DesktopPassphraseChangeCollaborators(
                    vault = vault,
                    sessionController = sessionController,
                ),
            language = UiLanguage.ENGLISH,
            settingsCollaborators =
                DesktopSettingsCollaborators(
                    preferences = AppPreferencesState(isLoading = false, themeMode = ThemeModeOption.SYSTEM),
                    // The shell's own derive-and-clamp answer, not the shared state's own default of ten.
                    unlockTimeoutMinutes = 120,
                    databaseFile = databaseFile.toFile(),
                ),
            // Over the SAME controller and the SAME file the rest of this fixture builds, so the assistant's
            // store runs over this test's own database rather than resolving the host's real one. Its
            // controller REFUSES every open (see [sessionController]), so `viewModelOrNull()` legitimately
            // returns null here and the destinations render over the shared screen's own default state -
            // which is the state `AssistantViewModel` publishes before its first `refresh()`, and the state
            // a user sees for the frames before a store answers. The walk's assertions are deliberately
            // copy that renders ABOVE that state.
            assistantCollaborators =
                DesktopAssistantCollaborators(
                    sessionController = sessionController,
                    databaseFile = databaseFile,
                ),
        )

    /** How a destination's copy reaches the tree - `contentDescription` or `text`. */
    private enum class CopiedBy {
        CONTENT_DESCRIPTION,
        TEXT,
    }

    /**
     * One destination to drive: how to enter it, what its method is called, and the copy that proves it
     * rendered.
     *
     * [method] is the name of the override in `DesktopPayanamNavHostScreens` - the seam's own vocabulary -
     * rather than the route, because the coverage assertion compares against method names and the two do
     * not always coincide (`add_task` the route is `addTask` the method).
     */
    private data class WiredDestination(
        /** The route handed to the graph as `landingRoute`, so the graph enters it as production does. */
        val route: String,
        /** The method of the seam this destination renders. */
        val method: String,
        /** Whether [copy] reaches the tree as a content description or as text. */
        val copiedBy: CopiedBy,
        /** Copy this destination's screen renders, resolved from the shared copy table. */
        val copy: String,
    ) {
        /**
         * Whether any node carries [copy] the way this destination's screen publishes it.
         *
         * The rule is PASSED rather than read from the enclosing class: this is a nested `data class`, and
         * Kotlin does not let it reach the outer instance's property - so a version that reached for
         * `composeRule` here would not compile, and the failure would be about a test file rather than about
         * a screen.
         */
        fun isPresent(rule: ComposeContentTestRule): Boolean =
            when (copiedBy) {
                CopiedBy.CONTENT_DESCRIPTION -> {
                    rule.onAllNodesWithContentDescription(copy).fetchSemanticsNodes().isNotEmpty()
                }

                CopiedBy.TEXT -> {
                    rule.onAllNodesWithText(copy).fetchSemanticsNodes().isNotEmpty()
                }
            }

        /**
         * Asserts this destination's copy is present.
         *
         * `assertExists` rather than `assertIsDisplayed`, for the reason
         * `DesktopNavHostDestinationRenderTest` gives: these are Material3 screens whose top bars and FABs sit
         * in `Scaffold` slots, so whether one falls inside the test window's height depends on the frame, and
         * a control scrolled past the fold is still a correct screen. What is asserted is that the body
         * COMPOSED - the observable that a placeholder pane or a named-error call destroys.
         */
        fun assertRendered(rule: ComposeContentTestRule) {
            when (copiedBy) {
                CopiedBy.CONTENT_DESCRIPTION -> {
                    rule.onNodeWithContentDescription(copy).assertExists()
                }

                CopiedBy.TEXT -> {
                    rule.onNodeWithText(copy).assertExists()
                }
            }
        }
    }

    private companion object {
        /** A task that is deliberately absent: the state a stale link leaves a user in. */
        const val MISSING_TASK_ID = "missing-task-id"

        /** The literal the desktop's own score-detail destination compares `type` against. */
        const val SCORE_DETAIL_TYPE = "DAY"

        /** A day key this fixture has no metric row for; the screen formats it into its own title. */
        const val SCORE_DETAIL_KEY = "2026-01-01"

        /**
         * How long a destination's copy gets to appear.
         *
         * Generous because the wait is a bound on a REGRESSION, not a target: the two asynchronous screens
         * settle as soon as a JDBC read on another dispatcher returns, which on an encrypted SQLCipher file
         * is hundreds of milliseconds. `waitUntil` throws on expiry, so this number cannot turn a failure
         * into a pass - it only decides how long a slow machine is given before the test says so.
         */
        const val TIMEOUT_MILLIS = 10_000L

        /**
         * The five startup gates, rendered and asserted by `DesktopNavHostDestinationRenderTest` instead of
         * here.
         *
         * Named rather than omitted so the coverage assertion can prove the partition is TOTAL. Each gate is
         * reached by raising a gate FLAG - the graph picks it as `startDestination` - not by naming its
         * route, which is a different fixture shape and already has an owner.
         */
        val GATE_DESTINATIONS_OWNED_BY_THE_GRAPH =
            setOf(
                "passphraseSetup",
                "passphraseUnlock",
                "passphraseChange",
                "databaseInit",
                "focusModeSelection",
                // The last two the desktop's own surface used to render. Both are graph destinations now:
                // the graph renders the shared tour screen and the shared refusal screen.
                "onboardingIntro",
                "securityStateUnavailable",
            )

        /**
         * The wired destinations that still throw by name, and why - KNOWN RESIDUE, recorded so it fails
         * when it is fixed rather than being quietly excluded.
         *
         * EMPTY. Both assistant destinations now render the SHARED screens over the SHARED ViewModel and
         * the desktop configuration store, so neither throws any more - and they are reached from INSIDE
         * the Lenses screen rather than from the rail, which is why the rail-scoped
         * `DesktopTopLevelDestinationRenderabilityTest` never had to record them.
         *
         * This is asserted as an EXACT map rather than as a subset, so all three directions fail: a newly
         * throwing destination, a fixed one whose record was not updated (the fix landed undocumented), and
         * a stale row naming a method that no longer exists. An assistant destination added to the walk
         * below is claimed by it, so its coverage is no longer a recorded gap.
         */
        val RECORDED_THROW_SITES: Map<String, String> = emptyMap()

        /** `override fun NAME(` at the start of a line, which is how every destination body opens. */
        val OVERRIDE_FIND = Regex("""^\s*override fun (\w+)\(""")

        /** A throw call with its argument: the declaration has a parameter list, never a quoted string. */
        val THROW_CALL_FIND = Regex("""desktopDestinationNotMigrated\("([^"]+)"""")

        /**
         * The desktop's real destination bodies, located by walking UP from the working directory.
         *
         * Probed rather than fixed: Gradle runs tests with the MODULE directory as the CWD, not the repository
         * root, so a hard-coded `..` path resolves to a file that does not exist - which is what
         * `DesktopShellRenderProbeTest` documents after it failed a whole suite from a green-looking test.
         */
        val SCREENS_FILE: File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { it.resolve("desktop/src/main/kotlin/io/payanam/desktop/DesktopPayanamNavHostScreens.kt") }
                .firstOrNull { it.isFile }
                ?: error("DesktopPayanamNavHostScreens.kt not found above ${File("").absolutePath}")
    }

    /**
     * Every `override fun NAME(` in `DesktopPayanamNavHostScreens.kt` - the destination names the seam is
     * implemented under.
     *
     * Read from the IMPLEMENTATION rather than reflected off [PayanamNavHostScreens] because the
     * interface's methods are the contract while this file's claim is about what the desktop actually
     * implements: a destination with no body here cannot be walked, whatever the interface says.
     */
    private fun implementedDestinationNames(): Set<String> =
        SCREENS_FILE
            .readLines()
            .mapNotNull { line -> OVERRIDE_FIND.find(line)?.groupValues?.get(1) }
            .toSet()

    /**
     * Every destination in the implementation whose body calls `desktopDestinationNotMigrated`, to the name
     * it reports itself as.
     *
     * A call site is attributed to the nearest preceding `override fun NAME(`, which is the destination it
     * sits in - the same scan `DesktopTopLevelDestinationRenderabilityTest` uses for the rail subset, kept
     * deliberately identical so the two files cannot disagree about where a throw site is. The helper's own
     * DECLARATION is not a call site: it is excluded by requiring a quoted string argument, which a
     * declaration never has.
     */
    private fun desktopThrowSites(): Map<String, String> {
        val found = mutableMapOf<String, String>()
        var currentFunction: String? = null
        for (line in SCREENS_FILE.readLines()) {
            OVERRIDE_FIND.find(line)?.let { currentFunction = it.groupValues[1] }
            if (currentFunction == null) continue
            THROW_CALL_FIND.find(line)?.let { call -> found[currentFunction] = call.groupValues[1] }
        }
        return found
    }
}
