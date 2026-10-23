//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.material3.SnackbarHostState
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.DimensionPreference
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.components.IMPORT_PICKER_MIME
import io.payanam.presentation.settings.SettingsUiState
import io.payanam.presentation.updates.UpdateChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The shared settings screen, rendered for real on the JVM target.
 *
 * What this pins is the arrangement the move created, not the copy: thirteen cards in one list, ONE of
 * them open at a time, and every row wired to the callback that names its own write. The screen used to
 * live in the Android app with a `SettingsSection?` expansion state, thirteen hand-written toggle
 * lambdas and the pickers inline; all three are now shared, and each is a place where a plausible edit
 * silently breaks behaviour rather than failing to compile.
 *
 * WHY THE CALLBACK IDENTITIES ARE ASSERTED PER ROW: the failure mode of a holder this wide is two rows
 * reporting each other's write - a shared screen where editing a dimension's colour changes its icon
 * would render perfectly. Each case therefore names the value it clicked and the value it expected.
 *
 * WHY THE PICKER IS ASSERTED BY ITS FILTER: the shared screen cannot hold a picked file, so it reports
 * the intent together with the MIME filter the platform should offer. A screen that passed an empty
 * filter would still open a picker and still look correct on screen while accepting nothing useful.
 *
 * WHY EVERY ROW REACH GOES THROUGH [scrollTo]: the screen is one `verticalScroll` column thirteen cards
 * tall, and the desktop test viewport is 1024x768, so the lower cards compose but sit outside the
 * clipped viewport (`VerticalScrollAxisRange maxValue=176`). Compose composes them either way - the
 * rows exist, they are simply not hittable where they are drawn - so the harness scrolls the shared
 * column to the node before clicking it rather than enlarging the window to hide the fact that the list
 * scrolls at all.
 */
class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val strings = UiStrings.of(UiLanguage.ENGLISH)

    private fun prefs(
        debugLoggingEnabled: Boolean = false,
        dimensions: List<DimensionPreference> = listOf(
            DimensionPreference(
                key = "dim_health",
                canonicalId = "dim_health",
                label = "Health",
                color = androidx.compose.ui.graphics.Color(0xFF4CAF50),
                isVisible = true,
                iconKey = "heart",
                weight = 1.0,
                hasCustomLabelOverride = false,
            ),
        ),
    ) = AppPreferencesState(
        debugLoggingEnabled = debugLoggingEnabled,
        dimensionPreferences = dimensions,
        // NOT the default, and the omission was a defect in this harness rather than in the screen:
        // `AppPreferencesState.isLoading` defaults to TRUE so that no shared section paints storage
        // values before the first read lands, which means the appearance card renders its single
        // "loading preferences" line and nothing else. A test that asserted the expanded card's rows
        // without saying so was testing a state the app never sits in - the mapper hands the UI
        // `isLoading = false` on every real read (AppPreferencesMapper.kt:183). Same trap that would
        // hang the desktop timeline, which needs an explicit `isLoading = false` at its pre-unlock call
        // site.
        isLoading = false,
    )

    private fun setContent(
        actions: SettingsScreenActions = SettingsScreenActions(),
        prefsState: AppPreferencesState = prefs(),
        importSourceSelected: Boolean = false,
        // Measured facts, not invented ones: the database card and the import dialog both read these,
        // so a default would render a pane reporting something the app never measured.
        uiState: SettingsUiState = SettingsUiState(
            currentDatabaseSchemaVersion = 12,
            minimumSupportedSchemaVersion = 5,
            appVersion = "1.2.3",
            buildNumber = 42,
            updateChannel = UpdateChannel.STABLE,
        ),
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
                SettingsScreenContent(
                    prefsState = prefsState,
                    uiState = uiState,
                    snackbarHostState = SnackbarHostState(),
                    actions = actions,
                    importSourceSelected = importSourceSelected,
                )
            }
        }
    }

    /** The one scrollable that matters: the screen's own column, the outermost in the semantics tree. */
    private fun scrollTo(text: String) {
        composeRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))
    }

    /** Opens a card by its header, after scrolling it into the clipped viewport. */
    private fun openCard(title: String) {
        scrollTo(title)
        composeRule.onNodeWithText(title).performClick()
    }

    /** Clicks a row inside an already-open card, after scrolling it into the clipped viewport. */
    private fun clickRow(text: String) {
        scrollTo(text)
        composeRule.onNodeWithText(text).performClick()
    }

    @Test
    fun everySectionIsOfferedWithItsSharedTitle() {
        setContent()
        listOf(
            strings.settingsAppearanceTitle,
            strings.settingsDefaultLandingTitle,
            strings.settingsLifeDimensionsTitle,
            strings.settingsInsightsChartsVisibilityTitle,
            strings.settingsAutoBackupTitle,
            strings.settingsDebugTitle,
            strings.settingsSecurityTitle,
            strings.settingsDatabaseTitle,
            strings.settingsDataManagementTitle,
            strings.settingsAboutTitle,
        ).forEach { title ->
            composeRule.onAllNodesWithText(title).assertCountEquals(1)
        }
    }

    @Test
    fun onlyTheTappedSectionExpandsAndTappingItAgainCollapsesIt() {
        setContent()
        openCard(strings.settingsAppearanceTitle)
        // The appearance section's own row proves the card opened rather than just logging a toggle.
        composeRule.onAllNodesWithText(strings.settingsAppLanguage).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsAutoTrackHabitTimeTitle).assertCountEquals(1)

        openCard(strings.settingsDebugTitle)
        composeRule.onAllNodesWithText(strings.settingsAppLanguage).assertCountEquals(0)
        composeRule.onAllNodesWithText(strings.settingsDebugEnable).assertCountEquals(1)

        clickRow(strings.settingsDebugTitle)
        composeRule.onAllNodesWithText(strings.settingsDebugEnable).assertCountEquals(0)
    }

    @Test
    fun theDebugSwitchReportsItsOwnWriteAndOnlyItsOwn() {
        val writes = mutableListOf<Pair<String, Boolean>>()
        setContent(
            actions = SettingsScreenActions(onDebugLoggingEnabledChanged = { writes += "debug" to it }),
        )
        openCard(strings.settingsDebugTitle)
        composeRule.onAllNodesWithText(strings.settingsDebugDisabledHint).assertCountEquals(1)
        // The label is a SIBLING of the switch, not part of it (SettingsScreen.kt:377-398), so the
        // node to click is the toggle - the same shape SettingsAutoBackupSectionTest pins.
        composeRule.onAllNodes(isToggleable()).assertCountEquals(1)
        composeRule.onAllNodes(isToggleable())[0].performClick()
        assertEquals(listOf("debug" to true), writes)
    }

    @Test
    fun theImportRowReportsTheSharedPickerFilterRatherThanPickingItself() {
        val filters = mutableListOf<Array<String>>()
        setContent(
            actions = SettingsScreenActions(onImportSourceRequested = { filters += it }),
        )
        openCard(strings.settingsDataManagementTitle)
        clickRow(strings.settingsActionImport)
        assertEquals(1, filters.size)
        assertEquals(IMPORT_PICKER_MIME.toList(), filters.single().toList())
    }

    @Test
    fun theUhabitsRowAsksForItsOwnFilterNotTheSharedOne() {
        val filters = mutableListOf<List<String>>()
        setContent(
            actions = SettingsScreenActions(onUhabitsSourceRequested = { filters += it.toList() }),
        )
        openCard(strings.settingsDataManagementTitle)
        clickRow(strings.settingsActionImportUhabits)
        assertEquals(1, filters.size)
        assertTrue(
            "the uHabits row must offer its SQLite filter, not the import row's: ${filters.single()}",
            filters.single().contains("application/x-sqlite3"),
        )
    }

    @Test
    fun theExportRowReportsTheFileNameThePlatformSupplied() {
        val names = mutableListOf<String>()
        setContent(
            actions = SettingsScreenActions(
                exportFileName = "payanam-2026-10-01.db",
                onExportDestinationRequested = { names += it },
            ),
        )
        openCard(strings.settingsDataManagementTitle)
        clickRow(strings.settingsActionExport)
        assertEquals(listOf("payanam-2026-10-01.db"), names)
    }

    @Test
    fun theImportConfirmationOnlyAppearsOnceAPlatformHasADocumentAndConfirmingClearsIt() {
        var confirmed = 0
        setContent(
            actions = SettingsScreenActions(onImportConfirmed = { confirmed++ }),
        )
        composeRule.onAllNodesWithText(strings.settingsImportDialogTitle).assertCountEquals(0)

        setContent(
            actions = SettingsScreenActions(onImportConfirmed = { confirmed++ }),
            importSourceSelected = true,
        )
        composeRule.onAllNodesWithText(strings.settingsImportDialogTitle).assertCountEquals(1)
        composeRule.onNodeWithText(strings.settingsActionDeleteImport).assertIsDisplayed()
        composeRule.onNodeWithText(strings.settingsActionDeleteImport).performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun theMinimalModeCardDropsEveryFileRow() {
        setContent(actions = SettingsScreenActions(minimalModeEnabled = true))
        openCard(strings.settingsDataManagementTitle)
        composeRule.onAllNodesWithText(strings.settingsActionImport).assertCountEquals(0)
        scrollTo(strings.settingsActionChangePassphrase)
        composeRule.onAllNodesWithText(strings.settingsActionChangePassphrase).assertCountEquals(1)
    }

    @Test
    fun theDimensionEditorCommitsTheEditedLabelToItsOwnDimension() {
        val labels = mutableListOf<Pair<String, String>>()
        setContent(
            actions = SettingsScreenActions(onDimensionLabelChanged = { id, label -> labels += id to label }),
        )
        openCard(strings.settingsLifeDimensionsTitle)
        composeRule.onAllNodesWithContentDescription(strings.settingsDimensionEdit).assertCountEquals(1)
        composeRule.onNodeWithContentDescription(strings.settingsDimensionEdit).performClick()
        // The editor is open, so the name field is editable and the current label is still the value.
        // TWO nodes legitimately carry "Health" now - the merged badge row behind the editor and the
        // editable field itself - so this is a count-of-one on the FIELD, not on the string.
        composeRule.onAllNodesWithText("Health", substring = false).onFirst().assertIsDisplayed()
        composeRule.onAllNodes(hasSetTextAction()).onFirst().performTextClearance()
        composeRule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Wellbeing")
        composeRule.onAllNodesWithContentDescription(strings.settingsDimensionSave).onFirst().performClick()
        assertEquals(listOf("dim_health" to "Wellbeing"), labels)
    }

    @Test
    fun aBlankEditedLabelIsNotAWriteBecauseTheRowFallsBackToTheCurrentOne() {
        val labels = mutableListOf<Pair<String, String>>()
        setContent(
            actions = SettingsScreenActions(onDimensionLabelChanged = { id, label -> labels += id to label }),
        )
        openCard(strings.settingsLifeDimensionsTitle)
        composeRule.onNodeWithContentDescription(strings.settingsDimensionEdit).performClick()
        composeRule.onAllNodes(hasSetTextAction()).onFirst().performTextClearance()
        composeRule.onAllNodesWithContentDescription(strings.settingsDimensionSave).onFirst().performClick()
        // The card itself refuses an all-whitespace commit, so nothing reaches the holder at all.
        assertEquals(emptyList<Pair<String, String>>(), labels)
    }

    @Test
    fun theDatabaseCardReadsItsCountsOutOfTheStateItWasHanded() {
        setContent(
            uiState = SettingsUiState(
                taskCount = 7,
                currentDatabaseSchemaVersion = 12,
                minimumSupportedSchemaVersion = 5,
                appVersion = "1.2.3",
                buildNumber = 42,
                updateChannel = UpdateChannel.STABLE,
            ),
        )
        openCard(strings.settingsDatabaseTitle)
        scrollTo("7")
        composeRule.onAllNodesWithText("7").assertCountEquals(1)
    }
    @Test
    fun debugTreeDump() {
        setContent()
        composeRule.onRoot().printToString(maxDepth = 100).let { println("TREE-START\n$it\nTREE-END") }
    }
}
