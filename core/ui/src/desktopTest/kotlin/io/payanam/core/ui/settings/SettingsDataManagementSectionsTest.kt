//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.presentation.settings.SettingsUiState
import io.payanam.shared.updates.UpdateChannel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The shared data-management settings content, rendered for real on the JVM target.
 *
 * The section used to hold Android resource ids and was gated behind three activity-result launchers that
 * lived in the platform screen, not here. These cases pin what the move actually had to keep honest.
 *
 * THE CALLBACKS: the three file operations report an intent to pick, never the choice. `DatabaseInitScreen`
 * in this same module already reports `onImportFileSelected(MIME types)` and receives the document back, so
 * a section that called a picker itself would have been a second, divergent seam. Each case below names the
 * operation it clicked, because the failure mode is two buttons wired to each other's callback - each row
 * asserting its own identity catches that, a single "something fired" case would not.
 *
 * THE BUSY GATES: import, export and the staged encrypted-import passphrase all disable different rows, and
 * the uHabits import adds a fourth flag of its own. The staged-passphrase case matters most: while that
 * prompt is open the import has been staged, so accepting a second tap would replace the staged file.
 */
class SettingsDataManagementSectionsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val strings = UiStrings.of(UiLanguage.ENGLISH)

    /** Exactly what the platform screen passes: a busy row is a real state, not an invented one. */
    private fun state(
        isExporting: Boolean = false,
        isImporting: Boolean = false,
        isUhabitsImporting: Boolean = false,
        isBulkMappingImportedHabits: Boolean = false,
        awaitingImportPassphrase: Boolean = false,
        importedUhabitsHabitCount: Int = 0,
    ) = SettingsUiState(
        // Measured facts, not invented ones: the section reads the schema versions and the channel, so a
        // default here would render a pane reporting something the app never measured.
        currentDatabaseSchemaVersion = 12,
        minimumSupportedSchemaVersion = 5,
        appVersion = "1.2.3",
        buildNumber = 42,
        updateChannel = UpdateChannel.STABLE,
        isExporting = isExporting,
        isImporting = isImporting,
        isUhabitsImporting = isUhabitsImporting,
        isBulkMappingImportedHabits = isBulkMappingImportedHabits,
        awaitingImportPassphrase = awaitingImportPassphrase,
        importedUhabitsHabitCount = importedUhabitsHabitCount,
    )

    private fun render(
        uiState: SettingsUiState = state(),
        minimal: Boolean = false,
        onExportClick: () -> Unit = {},
        onImportClick: () -> Unit = {},
        onImportUhabitsClick: () -> Unit = {},
        onMapImportedHabitsClick: () -> Unit = {},
        onChangePassphraseClick: () -> Unit = {},
        onDeleteAllDataClick: () -> Unit = {},
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
                if (minimal) {
                    MinimalDataManagementSection(
                        onChangePassphraseClick = onChangePassphraseClick,
                        onDeleteAllDataClick = onDeleteAllDataClick,
                        isExporting = uiState.isExporting,
                        isImporting = uiState.isImporting,
                    )
                } else {
                    DataManagementSettingsSection(
                        uiState = uiState,
                        onExportClick = onExportClick,
                        onImportClick = onImportClick,
                        onImportUhabitsClick = onImportUhabitsClick,
                        onMapImportedHabitsClick = onMapImportedHabitsClick,
                        onChangePassphraseClick = onChangePassphraseClick,
                        onDeleteAllDataClick = onDeleteAllDataClick,
                    )
                }
            }
        }
    }

    @Test
    fun `every operation reports its own callback`() {
        val fired = mutableListOf<String>()
        render(
            onExportClick = { fired += "export" },
            onImportClick = { fired += "import" },
            onImportUhabitsClick = { fired += "importUhabits" },
            onMapImportedHabitsClick = { fired += "mapHabits" },
            onChangePassphraseClick = { fired += "changePassphrase" },
            onDeleteAllDataClick = { fired += "deleteAll" },
        )

        composeRule.onNodeWithText(strings.settingsActionExport).performClick()
        assertEquals(listOf("export"), fired)

        composeRule.onNodeWithText(strings.settingsActionImport).performClick()
        assertEquals(listOf("export", "import"), fired)

        composeRule.onNodeWithText(strings.settingsActionImportUhabits).performClick()
        assertEquals(listOf("export", "import", "importUhabits"), fired)

        // The mapping row needs imported habits to exist before it is actionable.
        composeRule.onNodeWithText(strings.settingsActionMapImportedHabits).performClick()
        assertEquals(listOf("export", "import", "importUhabits"), fired)

        composeRule.onNodeWithText(strings.settingsActionChangePassphrase).performClick()
        assertEquals(listOf("export", "import", "importUhabits", "changePassphrase"), fired)

        composeRule.onNodeWithText(strings.settingsActionDeleteAllData).performClick()
        assertEquals(
            listOf("export", "import", "importUhabits", "changePassphrase", "deleteAll"),
            fired,
        )
    }

    @Test
    fun `the mapping row becomes actionable only once habits have been imported`() {
        render(state(importedUhabitsHabitCount = 0))
        composeRule.onNodeWithText(strings.settingsActionMapImportedHabits)
            .assertIsNotEnabled()

        var fired = false
        render(state(importedUhabitsHabitCount = 3), onMapImportedHabitsClick = { fired = true })
        composeRule.onNodeWithText(strings.settingsActionMapImportedHabits)
            .assertIsEnabled()
            .performClick()
        assertEquals(true, fired)
    }

    @Test
    fun `a staged encrypted import blocks only the import pick`() {
        render(state(awaitingImportPassphrase = true))

        // The whole point of the extra flag: the import is staged awaiting a passphrase, so the import
        // button must not accept another file.
        composeRule.onNodeWithText(strings.settingsActionImport).assertIsNotEnabled()

        // Exporting is the ONE row this state deliberately leaves alone: writing a backup does not touch
        // the staged file, and the passphrase flag belongs only to the import. Asserting it stayed enabled
        // pins that the flag was not widened to the whole card during the move.
        composeRule.onNodeWithText(strings.settingsActionExport).assertIsEnabled()
        composeRule.onNodeWithText(strings.settingsActionChangePassphrase).assertIsEnabled()
        composeRule.onNodeWithText(strings.settingsActionDeleteAllData).assertIsEnabled()
    }

    @Test
    fun `an uhabits import disables its own row and the mapping row but not export`() {
        render(state(isUhabitsImporting = true))

        composeRule.onNodeWithText(strings.settingsActionImportUhabits).assertIsNotEnabled()
        composeRule.onNodeWithText(strings.settingsActionMapImportedHabits).assertIsNotEnabled()
        composeRule.onNodeWithText(strings.settingsActionExport).assertIsEnabled()
    }

    @Test
    fun `the imported habit count is rendered from state`() {
        render(state(importedUhabitsHabitCount = 7))

        // Twice: once above the passphrase row and once below the delete row, as the platform screen drew it.
        composeRule.onAllNodesWithText(strings.settingsImportedHabitsCount.replace("%1\$d", "7"))
            .assertCountEquals(2)
    }

    @Test
    fun `the count line reads zero rather than disappearing when nothing was imported`() {
        render(state(importedUhabitsHabitCount = 0))

        composeRule.onAllNodesWithText(strings.settingsImportedHabitsCount.replace("%1\$d", "0"))
            .assertCountEquals(2)
    }

    @Test
    fun `minimal mode omits export and both imports entirely`() {
        render(minimal = true)

        composeRule.onNodeWithText(strings.settingsActionChangePassphrase).assertIsEnabled()
        composeRule.onNodeWithText(strings.settingsActionDeleteAllData).assertIsEnabled()
        composeRule.onAllNodesWithText(strings.settingsActionExport).assertCountEquals(0)
        composeRule.onAllNodesWithText(strings.settingsActionImportUhabits).assertCountEquals(0)
        composeRule.onAllNodesWithText(strings.settingsImportedHabitsCount.replace("%1\$d", "0"))
            .assertCountEquals(0)
    }

}
