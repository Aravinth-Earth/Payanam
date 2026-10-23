//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.navigation.PayanamNavHost
import io.payanam.core.ui.navigation.PayanamTab
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.ThemeModeOption
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.theme.PayanamTheme
import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.database.jvm.PayanamDatabaseSession
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
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.Properties

/**
 * The desktop's settings destination, mounted through the REAL shared graph with the REAL collaborators -
 * the settings half of what `DesktopNavHostDestinationRenderTest` does for the gates.
 *
 * **Why this file exists, stated as the defect it closes.** `settings` is the RAIL route the navigation model
 * documents as the fallback when every other route is hidden, so it is one click from the surface a user
 * lands on - and its body threw `desktopDestinationNotMigrated`. Its destination is now the shared
 * `SettingsScreen`, which raises a second question the renderability record cannot answer: does that screen
 * render with the state the desktop hands it, or does it render a screen whose sections are silently empty?
 *
 * **The specific trap, and why the assertions below target it.** `AppPreferencesState.isLoading` DEFAULTS TO
 * TRUE, and `SettingsAppearanceSection` (line 62) and `SettingsDefaultLandingSection` (line 81) both
 * early-return on it. A destination that built its own default-constructed state, or read the ambient
 * `LocalAppPreferences` before the shell had installed one, therefore renders thirteen card headers and NO
 * controls in those two sections - with no exception, no log line and no red test. So the cases here do not
 * assert "it did not throw"; they assert on the CONTENT those two sections render when expanded, which is
 * the only thing that distinguishes the real wiring from the trap.
 *
 * **The ambient read is pinned in a separate file on purpose.** `DesktopSettingsPreferencesSourceTest` reads
 * the destination's source to assert it passes the shell's state rather than reading `LocalAppPreferences`,
 * because that is a claim about WHICH value reaches the screen rather than about what the screen draws - and
 * a render test cannot see it, because the two values could produce the same tree whenever the shell has
 * installed them the same. Both are here because each fails when the other could not.
 */
class DesktopSettingsDestinationRenderTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val passphrase = "desktop-settings-render-test"
    private val strings = UiStrings.of(UiLanguage.ENGLISH)

    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession
    private lateinit var databaseDirectory: Path
    private lateinit var vault: DesktopPassphraseVault
    private lateinit var sessionLogger: UnifiedLogger

    /** Every settings write this test issues, as `key to value` - the only observable a write has. */
    private val writtenRows = mutableListOf<Pair<String, String?>>()

    @Before
    fun setUp() {
        UnifiedLogger.resetForTests()
        sessionLogger =
            UnifiedLogger.initialize(
                logsDirectory = temporaryFolder.newFolder("settings-logs").toPath(),
                clock = { Instant.EPOCH },
            )

        // A real encrypted file, because the state this screen renders includes THREE repository counts and a
        // filesystem listing, all of which go through the INSTALLED layer. An uninstalled layer throws by
        // design, so a fake repository here would not be a test convenience but a different instrument.
        databaseDirectory = temporaryFolder.newFolder("database").toPath()
        val databasePath = databaseDirectory.resolve(PayanamDatabaseSchema.DATABASE_NAME)
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection = DriverManager.getConnection("jdbc:sqlcipher:${databasePath.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE app_settings (
                    `key` TEXT NOT NULL,
                    value TEXT,
                    updatedAt TEXT NOT NULL,
                    PRIMARY KEY(`key`)
                )
                """.trimIndent(),
            )
            // The three count tables, because the state this destination renders READS them: `settings.uiState`
            // takes the same three repository counts Android's `SettingsViewModel` takes, so a fixture without
            // them fails with `no such table: tasks` before the screen composes - which is a fixture defect,
            // not a screen defect, and exactly the kind that makes a render test look like a wiring failure.
            statement.execute(
                """
                CREATE TABLE tasks (
                    id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    description TEXT,
                    status TEXT NOT NULL DEFAULT 'pending',
                    dueDate TEXT,
                    createdAt TEXT NOT NULL,
                    updatedAt TEXT NOT NULL,
                    completedAt TEXT,
                    archivedAt TEXT,
                    recurrenceEnabled INTEGER NOT NULL DEFAULT 0,
                    recurrenceRule TEXT,
                    durationMinutes INTEGER NOT NULL DEFAULT 60,
                    impactLevel TEXT NOT NULL DEFAULT 'Moderate Impact',
                    goalAlignment TEXT NOT NULL DEFAULT 'Moderate Alignment',
                    energyLevel TEXT NOT NULL DEFAULT 'Moderate',
                    controlLevel TEXT NOT NULL DEFAULT 'Office/Colleagues Dependent',
                    lifeIntentionCategory TEXT NOT NULL DEFAULT 'Career & Work',
                    dimension_id TEXT,
                    day_key TEXT,
                    explicitUrgency REAL,
                    focusRequired REAL,
                    recurrenceStrategy TEXT,
                    blockedReason TEXT,
                    completionRate REAL,
                    externalDependency TEXT,
                    notificationMode TEXT,
                    customNotificationMinutes INTEGER,
                    taskScore REAL,
                    lastOccurrenceDate TEXT,
                    dayBoundaryHour INTEGER NOT NULL DEFAULT 0,
                    import_source TEXT,
                    import_id TEXT,
                    imported_at TEXT,
                    import_batch_id TEXT
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE time_entries (
                    id TEXT PRIMARY KEY NOT NULL,
                    lifeIntentionCategory TEXT NOT NULL,
                    dimension_id TEXT,
                    day_key TEXT,
                    taskId TEXT,
                    startedAt TEXT NOT NULL,
                    endedAt TEXT,
                    focusRating REAL,
                    focusNote TEXT,
                    focusRatedAt TEXT,
                    import_source TEXT,
                    import_id TEXT,
                    imported_at TEXT,
                    import_batch_id TEXT,
                    createdAt TEXT NOT NULL,
                    updatedAt TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE notes (
                    id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    details TEXT,
                    lifeIntentionCategory TEXT NOT NULL,
                    dimension_id TEXT,
                    day_key TEXT,
                    createdAt TEXT NOT NULL,
                    updatedAt TEXT NOT NULL
                )
                """.trimIndent(),
            )
            // The journal table and its two link tables, because `noteRepository.getAllNotes()` reads the
            // journal alongside the notes - so the note COUNT this screen renders needs both. Taken from
            // `DesktopSharedStateExecutionTest`'s fixture, which is the one that already reads this repository.
            statement.execute(
                """
                CREATE TABLE journal_notes (
                    id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    details TEXT,
                    lifeIntentionCategory TEXT NOT NULL,
                    dimension_id TEXT,
                    day_key TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE tags (
                    id TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    normalized_name TEXT NOT NULL,
                    usage_count INTEGER NOT NULL DEFAULT 0,
                    last_used_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute("CREATE TABLE note_tags (note_id TEXT, tag_id TEXT, created_at TEXT)")
            statement.execute("CREATE TABLE task_tags (task_id TEXT, tag_id TEXT, created_at TEXT)")
            statement.execute("CREATE TABLE time_entry_tags (time_entry_id TEXT, tag_id TEXT, created_at TEXT)")
        }
        session = PayanamDatabaseSession.forGeneratedFixture(connection, schemaVersion = 21)
        LocalDataLayer.resetForTests()
        LocalDataLayer.install(DesktopPayanamDataLayer(session, pollIntervalMs = 10L))

        vault =
            DesktopPassphraseVault(
                DesktopSecurityStore(securityDirectory = temporaryFolder.newFolder("security").toPath()),
            )
    }

    private val sessionController: DesktopDatabaseSessionController by lazy {
        DesktopDatabaseSessionController(
            databaseFilePath = {
                databaseDirectory.resolve(PayanamDatabaseSchema.DATABASE_NAME).toFile().absolutePath
            },
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
        LocalDataLayer.resetForTests()
        sessionLogger.close()
        UnifiedLogger.resetForTests()
        if (!session.isClosed) session.close()
    }

    /**
     * The screen renders, which is the direct answer to "does the rail click still throw".
     *
     * Before this body was wired the destination raised `desktopDestinationNotMigrated`, so this is the case
     * that would have failed against the previous commit. The top bar title is the strongest single node
     * available: `SettingsScreenContent` renders it unconditionally, first, in the graph's own language.
     */
    @Test
    fun `the settings destination renders the shared screen instead of throwing`() {
        mountSettings()

        // `settingsTitle` appears TWICE in this graph - the screen's own top bar and the bottom nav tab
        // labelled "Settings" - so the assertion takes the FIRST node, which is the top bar's, and says so.
        // Asserting an exact count instead would pin the nav bar's presence, which is the graph's business.
        composeRule.onAllNodesWithText(strings.settingsTitle)[0].assertIsDisplayed()
        // A section header from each of the three groups the screen divides into, so a screen that rendered
        // only its chrome would fail: the appearance card, the security card and the about card.
        composeRule.onNodeWithText(strings.settingsAppearanceTitle).assertExists()
        composeRule.onNodeWithText(strings.settingsSecurityTitle).assertExists()
        composeRule.onNodeWithText(strings.settingsAboutTitle).assertExists()
    }

    /**
     * The appearance section renders its CONTROLS, which is the assertion that separates the real wiring from
     * the `isLoading` trap.
     *
     * This is the case the whole file is for. `SettingsAppearanceSection` early-returns on
     * `prefsState.isLoading` with a "Loading preferences..." line, so a default-constructed state renders a
     * screen with all thirteen card headers and nothing inside this one - and every other assertion in this
     * file would still pass. Expanding the card and then looking for a control that the section renders
     * UNCONDITIONALLY once expanded is what makes it fail on the trap instead.
     */
    @Test
    fun `the appearance section renders controls, so the state was not the default-constructed one`() {
        mountSettings()

        // Expanded, by tapping the card's own header row - the same affordance a user uses.
        composeRule.onNodeWithText(strings.settingsAppearanceTitle).performClick()
        composeRule.waitForIdle()

        // `settingsThemeMode` is a row LABEL inside this section, rendered once the card is open and never
        // behind the `isLoading` guard. Its absence while the card is open is the trap, and only the trap.
        composeRule.onNodeWithText(strings.settingsThemeMode).assertExists()
    }

    /**
     * The default-landing section renders controls for the same reason and over the same guard.
     *
     * The second of the two early-returning sections, pinned separately because a fix that satisfies one of
     * them - say, by passing a state with `isLoading = false` but an empty route list - could still leave the
     * other empty, and the pair is what makes "the state was passed, not defaulted" a claim.
     */
    @Test
    fun `the default landing section renders controls, so the second guarded section is not empty either`() {
        mountSettings()

        composeRule.onNodeWithText(strings.settingsDefaultLandingTitle).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(strings.settingsDefaultLandingTopLevelLabel).assertExists()
    }

    /**
     * A control in the appearance section writes the desktop's OWN row, through the shell's single-row path.
     *
     * The behavioural counterpart to the render assertions: a destination rendering the screen with a
     * defaulted [SettingsScreenActions] would show the control and do nothing, which is the shape of stub the
     * render-only cases above cannot detect. This asserts the write reached [writtenRows], which is the only
     * observable a settings write has on this platform.
     *
     * Time format is chosen rather than theme mode because it is a single-segmented button whose label does
     * not depend on which rows were read - so a wrong value could not be mistaken for a missing write.
     */
    @Test
    fun `a control in the settings screen writes the shared row in the real database`() {
        mountSettings()

        // The debug card's own switch, located by GEOMETRY rather than by index. An index names whichever
        // switch the section order puts first, and the cards above the debug one carry several - so index 0 is
        // a switch in a DIFFERENT section, and a click on it would assert that section's wiring while this
        // case's name claimed the debug one. "At or below this title's own bottom edge" is what makes the
        // write and the title one claim about one control.
        // The debug card's own switch, located by GEOMETRY rather than by index, and scrolled into view
        // first. An index names whichever switch the section order puts first, and the cards above the debug
        // one carry several - so index 0 is a switch in a DIFFERENT section, and a click on it would assert
        // that section's wiring while this case's name claimed the debug one. The screen is one long
        // `verticalScroll` column, so the switch is below the fold until it is scrolled to, which is why the
        // label it sits beside is the anchor rather than the card header.
        composeRule.onNodeWithText(strings.settingsDebugTitle).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(strings.settingsDebugEnable).performScrollTo().performClick()
        composeRule.waitForIdle()

        val enableLabelTop =
            composeRule
                .onNodeWithText(strings.settingsDebugEnable)
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        val switchIndex =
            composeRule
                .onAllNodes(isToggleable(), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .indexOfFirst { node -> node.boundsInRoot.top >= enableLabelTop }
        // Asserted rather than assumed: an index of -1 means no switch sits below this row, which is a layout
        // change rather than a wiring failure and would otherwise show up as a click on node -1.
        assertThat(switchIndex).isAtLeast(0)
        composeRule.onAllNodes(isToggleable(), useUnmergedTree = true)[switchIndex].performClick()
        composeRule.waitForIdle()

        // Read back from the DATABASE rather than from a recording collaborator, which is what makes this an
        // end-to-end claim: the destination builds `AppPreferencesWriter` over the installed layer, the writer
        // derives the key and the Boolean string, and the row lands in `app_settings` - the SAME table the
        // Android app writes. A callback double would pass while the writer was never constructed.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            readSetting("debug_logging_enabled") != null
        }
        println("DIAG db=" + readSetting("debug_logging_enabled"))
        // The VALUE as well as the key, so a write that landed with a wrong payload fails separately from one
        // that never arrives - different defects, and only the value assertion tells them apart.
        assertThat(readSetting("debug_logging_enabled")).isEqualTo("true")

        // And the shell's single-row callback was NOT the path for it, because the shared writer owns this
        // key. That is the split the destination documents: everything the writer exposes goes through the
        // writer, and only the session-timeout row - which has no writer method on either platform - goes
        // through the shell's callback.
        assertThat(writtenRows.map { (key, _) -> key }).doesNotContain("debug_logging_enabled")
    }

    /** The stored value of one `app_settings` row in this fixture's database, or null when absent. */
    private fun readSetting(key: String): String? =
        connection
            .createStatement()
            .use { statement ->
                statement.executeQuery("SELECT value FROM app_settings WHERE `key` = '$key'").use { rows ->
                    if (rows.next()) rows.getString(1) else null
                }
            }

    /**
     * Mounts the REAL graph on the REAL desktop screens, with this class's REAL collaborators.
     *
     * `landingRoute` is `settings`, so the destination is entered through the graph's own route table rather
     * than through a hand-rolled navigation - the same route the rail reaches, which is the reachability the
     * renderability record now claims. `isLandingReady = true` with no gate flags is what makes the graph open
     * the landing route instead of a gate body.
     */
    private fun mountSettings() {
        val screens =
            DesktopPayanamNavHostScreens(
                databaseInitCollaborators =
                    DesktopDatabaseInitCollaborators(
                        sessionController = sessionController,
                        documentSource = AwtDocumentSource(),
                        passphraseVault = vault,
                        databasePathResolver =
                            DesktopDatabasePathResolver(
                                databaseDirectory = { temporaryFolder.newFolder("init-probe").absolutePath },
                            ),
                    ),
                passphraseUnlockCollaborators =
                    DesktopPassphraseUnlockCollaborators(
                        vault = vault,
                        biometricPrompt = DesktopBiometricUnlockPrompt,
                        forgottenPassphraseReset =
                            DesktopForgottenPassphraseReset(
                                vault,
                                databaseDirectory.resolve(PayanamDatabaseSchema.DATABASE_NAME).toFile().toPath(),
                            ),
                        sessionController = sessionController,
                        databaseFile = databaseDirectory.resolve(PayanamDatabaseSchema.DATABASE_NAME).toFile(),
                    ),
                passphraseChangeCollaborators =
                    DesktopPassphraseChangeCollaborators(
                        vault = vault,
                        sessionController = sessionController,
                    ),
                language = UiLanguage.ENGLISH,
                settingsCollaborators =
                    DesktopSettingsCollaborators(
                        // A state with `isLoading = false` and a real theme mode, which is what the shell's own
                        // mapper publishes after a read. Deliberately NOT `AppPreferencesState()`: this fixture
                        // stands in for the SHELL's published value, and its job is to let the render cases
                        // distinguish the wiring from the trap - which a default state would defeat.
                        preferences = AppPreferencesState(isLoading = false, themeMode = ThemeModeOption.SYSTEM),
                        // The shell's own derive-and-clamp answer, not the shared state's own default of ten.
                        unlockTimeoutMinutes = 120,
                        // A database file inside this fixture's own directory, so the artifact card lists this
                        // test's files rather than the developer's real ones.
                        databaseFile = databaseDirectory.resolve(PayanamDatabaseSchema.DATABASE_NAME).toFile(),
                        // The observable the write case reads.
                        launchWrite = { block -> block() },
                        setSetting = { key, value -> writtenRows += key to value },
                    ),
            )
        composeRule.setContent {
            PayanamTheme(darkTheme = false) {
                PayanamNavHost(
                    screens = screens,
                    language = UiLanguage.ENGLISH,
                    minimalModeEnabled = false,
                    landingRoute = PayanamTab.Settings.route,
                    isLandingReady = true,
                )
            }
        }
        composeRule.waitForIdle()
    }
}
