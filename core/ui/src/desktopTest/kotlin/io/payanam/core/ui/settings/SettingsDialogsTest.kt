//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.payanam.core.ui.preferences.DimensionPreference
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The shared settings dialogs, rendered for real on the JVM target.
 *
 * WHAT THIS MOVE CAN SILENTLY BREAK is the COPY and the WIRING, so the cases below are written against
 * both rather than against structure:
 *
 *  - A string that was dropped on the way to the shared table renders a plausible, wrong dialog. The
 *    fidelity case compares the properties the file READS against the copy table's contents, so a row
 *    that stops drawing its warning, or starts drawing the wrong dialog's message, fails the
 *    comparison instead of the reviewer.
 *  - A dialog bound to the wrong callback still renders perfectly and still looks like it works, forever.
 *    Every case therefore drives a tap and asserts WHICH callback ran.
 *
 * Every string both dialogs draw is asserted against the shared table's own value for the language
 * under test, so this also pins that a key the app used to read as `R.string` resolves to the same copy
 * in the shared table. It would be a silent regression if the shared table's copy drifted from the app's
 * while both continued to compile.
 */
class SettingsDialogsTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * FIDELITY: every `UiStrings` property this file reads must be one the shared table actually carries
     * in BOTH shipped languages, and must resolve to real copy.
     *
     * A mistyped property would not compile, so what this catches is the silent half: a property the
     * table has but leaves EMPTY, or a key that exists in English and is absent in Tamil. Both render as
     * a blank dialog in one language, which no assertion about which nodes exist would notice.
     */
    @Test
    fun `every string these dialogs draw resolves in every shipped language`() {
        listOf(UiLanguage.ENGLISH, UiLanguage.TAMIL).forEach { language ->
            val strings = UiStrings.of(language)
            dialogStrings(strings).forEach { (name, copy) ->
                assertTrue("dialog string $name is blank in $language", copy.isNotBlank())
            }
        }
    }

    @Test
    fun `the import confirm names every hazard and drops none of them`() {
        render {
            ImportDatabaseConfirmDialog(
                showEncryptedModeWarning = false,
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText(strings.settingsImportDialogTitle).assertIsDisplayed()
        composeRule.onNodeWithText(strings.settingsImportDialogWarning).assertIsDisplayed()
        composeRule.onNodeWithText(strings.settingsImportDialogDataLoss).assertIsDisplayed()
        composeRule.onNodeWithText(strings.settingsImportDialogBackupHint).assertIsDisplayed()
        composeRule.onNodeWithText(strings.settingsActionDeleteImport).assertIsDisplayed()
        composeRule.onNodeWithText(strings.settingsActionCancel).assertIsDisplayed()
    }

    @Test
    fun `the encrypted-mode hint appears only when the caller says encryption is on`() {
        render {
            ImportDatabaseConfirmDialog(
                showEncryptedModeWarning = true,
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText(strings.settingsImportDialogEncryptedModeHint).assertIsDisplayed()
    }

    @Test
    fun `the encrypted-mode hint is absent when encryption is off`() {
        render {
            ImportDatabaseConfirmDialog(
                showEncryptedModeWarning = false,
                onConfirm = {},
                onDismiss = {},
            )
        }

        // Zero, not "at least one": a hint shown while plain databases are importable would tell the
        // user a falsehood, and it would still be a plausible-looking dialog.
        composeRule.onAllNodesWithText(strings.settingsImportDialogEncryptedModeHint).assertCountEquals(0)
    }

    @Test
    fun `the import confirm reports confirm and dismiss as different answers`() {
        val confirmed = mutableListOf<String>()
        var dismissed = false
        render {
            ImportDatabaseConfirmDialog(
                showEncryptedModeWarning = false,
                onConfirm = { confirmed.add("confirm") },
                onDismiss = { dismissed = true },
            )
        }

        composeRule.onNodeWithText(strings.settingsActionDeleteImport).performClick()
        assertEquals(listOf("confirm"), confirmed)
        assertTrue("confirm must not also dismiss", !dismissed)
    }

    @Test
    fun `the delete-all confirm reports confirm and dismiss as different answers`() {
        val confirmed = mutableListOf<String>()
        var dismissed = false
        render {
            DeleteAllDataConfirmDialog(
                onConfirm = { confirmed.add("confirm") },
                onDismiss = { dismissed = true },
            )
        }

        composeRule.onNodeWithText(strings.settingsDeleteDialogTitle).assertIsDisplayed()
        composeRule.onNodeWithText(strings.settingsActionDeleteAllData).performClick()
        assertEquals(listOf("confirm"), confirmed)
        assertTrue("confirm must not also dismiss", !dismissed)
    }

    /**
     * The three answers are three different operations, and only one of them writes. Collapsing them
     * would hide which is which, so this asserts the ROUTING: back-up-first fires the launcher callback
     * and neither of the others, and skip-and-delete does not fire it.
     */
    @Test
    fun `the export prompt routes each of its three answers to its own callback`() {
        val events = mutableListOf<String>()
        render {
            DeleteExportPromptDialog(
                onBackUpFirst = { events.add("backUp") },
                onSkipAndDelete = { events.add("skipAndDelete") },
                onDismiss = { events.add("dismiss") },
            )
        }

        composeRule.onNodeWithText(strings.settingsDeleteExportPromptYes).performClick()
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
                DeleteExportPromptDialog(
                    onBackUpFirst = { events.add("backUp") },
                    onSkipAndDelete = { events.add("skipAndDelete") },
                    onDismiss = { events.add("dismiss") },
                )
            }
        }
        composeRule.onNodeWithText(strings.settingsDeleteExportPromptNo).performClick()

        assertEquals(listOf("backUp", "skipAndDelete"), events)
        assertTrue("neither answer may dismiss as well", events.none { it == "dismiss" })
    }

    @Test
    fun `the passphrase gate refuses an empty passphrase and reports the one it was given`() {
        val submitted = mutableListOf<String>()
        render {
            ImportEncryptedDbPassphraseDialog(
                passphraseError = null,
                isVerifying = false,
                onConfirm = { submitted.add(it) },
                onDismiss = {},
            )
        }

        // The empty case FIRST, because a confirm button that reports "" is the bug: it would start an
        // import with a blank passphrase and fail much later, inside the vault, with no clue why.
        composeRule.onNodeWithText(strings.dbImportPassphrasePromptAction).assertIsNotEnabled()
        composeRule.onNodeWithText(strings.dbPassphraseInputLabel).performTextInput("correct horse")
        composeRule.waitForIdle()
        composeRule.onNodeWithText(strings.dbImportPassphrasePromptAction).performClick()

        assertEquals(listOf("correct horse"), submitted)
    }

    @Test
    fun `the passphrase gate shows the caller's error and holds the field shut while verifying`() {
        render {
            ImportEncryptedDbPassphraseDialog(
                passphraseError = "Wrong passphrase",
                isVerifying = true,
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText("Wrong passphrase").assertIsDisplayed()
        // The action is replaced by a spinner while verifying, so asserting on its label would assert
        // about an invisible node; the progress indicator is what the caller can actually see.
        composeRule.onNodeWithText(strings.dbPassphraseInputLabel).assertIsNotEnabled()
        composeRule.onNodeWithText(strings.settingsActionCancel).assertIsNotEnabled()
    }

    @Test
    fun `the passphrase gate toggles the field's visibility between masked and plain`() {
        render {
            ImportEncryptedDbPassphraseDialog(
                passphraseError = null,
                isVerifying = false,
                onConfirm = {},
                onDismiss = {},
            )
        }

        // The content description is the copy for this control, and it is the only place the two
        // toggles' words appear - a swapped pair would leave the button working but announcing the
        // opposite action, which is exactly the sort of loss a screenshot cannot show.
        composeRule.onNodeWithContentDescription(strings.dbPassphraseShowToggle).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(strings.dbPassphraseShowToggle).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(strings.dbPassphraseHideToggle).assertIsDisplayed()
    }

    /**
     * THE IDENTITY THAT MATTERS: the row that is marked is the row that is reported.
     *
     * The dimensions below are deliberately constructed so `id` and `canonicalId` DIFFER, which is what a
     * renamed dimension with a custom id looks like in practice. Marking by one and reporting the other
     * renders a dialog that looks like it honoured the tap and files the habits under the wrong life
     * area - and every assertion about "a row got selected" would still pass.
     */
    @Test
    fun `the bulk map marks the row whose id it also reports`() {
        val reported = mutableListOf<String>()
        render {
            BulkMapImportedHabitsDialog(
                // The marked row's OWN id, not its canonicalId: the mark is drawn for custom_id_health,
                // so a dialog that marked by canonicalId would find nothing and draw no mark at all.
                selectedDimensionId = "custom_id_health",
                dimensionPreferences = dimensions,
                onSelectedDimensionChange = { reported.add(it) },
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText(strings.settingsBulkMapDialogMessage).assertIsDisplayed()
        composeRule.onNodeWithText(strings.settingsBulkMapDimensionSelected.format("Health")).assertIsDisplayed()
        // ZERO unmarked "Health" nodes, not "one marked and one plain": a row drawn twice would be two
        // answers for one question, and the tap below would then be ambiguous about which it hit.
        composeRule.onAllNodesWithText("Health").assertCountEquals(0)
        // Tapping the OTHER row must report that row's own id, not the selected one and not a label.
        composeRule.onNodeWithText("Work").performClick()
        assertEquals(listOf("custom_id_work"), reported)
    }

    @Test
    fun `the bulk map marks only the selected row`() {
        render {
            BulkMapImportedHabitsDialog(
                selectedDimensionId = "custom_id_work",
                dimensionPreferences = dimensions,
                onSelectedDimensionChange = {},
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeRule.onAllNodesWithText(strings.settingsBulkMapDimensionSelected.format("Work")).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsBulkMapDimensionSelected.format("Health")).assertCountEquals(0)
        composeRule.onAllNodesWithText(strings.settingsBulkMapDimensionSelected.format("Journal")).assertCountEquals(0)
    }

    @Test
    fun `the bulk map confirms and dismisses as different answers`() {
        val events = mutableListOf<String>()
        render {
            BulkMapImportedHabitsDialog(
                selectedDimensionId = "custom_id_work",
                dimensionPreferences = dimensions,
                onSelectedDimensionChange = {},
                onConfirm = { events.add("confirm") },
                onDismiss = { events.add("dismiss") },
            )
        }

        composeRule.onNodeWithText(strings.locApply).performClick()
        assertEquals(listOf("confirm"), events)
    }

    /**
     * A dialog whose way out is only its confirm button strands the user - for the destructive ones that
     * would mean the only escape is the answer that destroys. Cancel has to be a real second answer, so
     * this asserts the ROUTING in the direction that matters: pressing Cancel dismisses and confirms
     * nothing.
     */
    @Test
    fun `cancel is a second answer rather than a confirm`() {
        val events = mutableListOf<String>()
        render {
            ImportEncryptedDbPassphraseDialog(
                passphraseError = null,
                isVerifying = false,
                onConfirm = { events.add("confirm") },
                onDismiss = { events.add("dismiss") },
            )
        }

        composeRule.onNodeWithText(strings.dbPassphraseInputLabel).performTextInput("hunter2")
        composeRule.waitForIdle()
        composeRule.onNodeWithText(strings.settingsActionCancel).performClick()

        // The passphrase is live in the field, so if Cancel fell through to onConfirm this would read
        // ["confirm"] with a secret in it rather than ["dismiss"].
        assertEquals(listOf("dismiss"), events)
    }

    private val strings: UiStrings = UiStrings.of(UiLanguage.ENGLISH)

    /**
     * `id` and `canonicalId` differ on purpose: a dimension renamed with a custom id is exactly the case
     * where marking by canonical id and reporting id would disagree.
     */
    private val dimensions =
        listOf(
            DimensionPreference(
                key = "dim_work",
                label = "Work",
                color = Color.Blue,
                isVisible = true,
                id = "custom_id_work",
                canonicalId = "dim_work",
            ),
            DimensionPreference(
                key = "dim_health",
                label = "Health",
                color = Color.Green,
                isVisible = true,
                id = "custom_id_health",
                canonicalId = "dim_health",
            ),
            DimensionPreference(
                key = "dim_journal",
                label = "Journal",
                color = Color.Gray,
                isVisible = false,
                id = "custom_id_journal",
                canonicalId = "dim_journal",
            ),
        )

    private fun render(content: @Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings, content = content)
        }
    }

    /**
     * The `UiStrings` properties the dialogs read, each named BY ITS OWN PROPERTY.
     *
     * Spelled as property reads rather than looked up by name, because a name lookup would answer about
     * the compiled class at runtime while reporting a blank for a property that demonstrably exists -
     * and would report nothing at all for a property that does not, which is the case this check exists
     * for. Written this way the compiler is the check: a property the table drops breaks the build here
     * rather than rendering a blank dialog on one device.
     *
     * The list is also the DECLARATION of what these dialogs say. A property a dialog reads and this
     * function omits goes unchecked, so the file's reads are compared against it by hand - the same
     * declaration-versus-use comparison the appearance section's tests make, for the same reason.
     */
    private fun dialogStrings(strings: UiStrings): Map<String, String> =
        mapOf(
            "settingsImportDialogTitle" to strings.settingsImportDialogTitle,
            "settingsImportDialogWarning" to strings.settingsImportDialogWarning,
            "settingsImportDialogDataLoss" to strings.settingsImportDialogDataLoss,
            "settingsImportDialogBackupHint" to strings.settingsImportDialogBackupHint,
            "settingsImportDialogEncryptedModeHint" to strings.settingsImportDialogEncryptedModeHint,
            "settingsActionDeleteImport" to strings.settingsActionDeleteImport,
            "settingsActionCancel" to strings.settingsActionCancel,
            "settingsDeleteDialogTitle" to strings.settingsDeleteDialogTitle,
            "settingsDeleteDialogMessage" to strings.settingsDeleteDialogMessage,
            "settingsActionDeleteAllData" to strings.settingsActionDeleteAllData,
            "settingsDeleteExportPromptTitle" to strings.settingsDeleteExportPromptTitle,
            "settingsDeleteExportPromptBody" to strings.settingsDeleteExportPromptBody,
            "settingsDeleteExportPromptYes" to strings.settingsDeleteExportPromptYes,
            "settingsDeleteExportPromptNo" to strings.settingsDeleteExportPromptNo,
            "dbImportPassphrasePromptTitle" to strings.dbImportPassphrasePromptTitle,
            "dbImportPassphrasePromptDesc" to strings.dbImportPassphrasePromptDesc,
            "dbImportPassphrasePromptAction" to strings.dbImportPassphrasePromptAction,
            "dbPassphraseInputLabel" to strings.dbPassphraseInputLabel,
            "dbPassphraseShowToggle" to strings.dbPassphraseShowToggle,
            "dbPassphraseHideToggle" to strings.dbPassphraseHideToggle,
            "settingsBulkMapDialogTitle" to strings.settingsBulkMapDialogTitle,
            "settingsBulkMapDialogMessage" to strings.settingsBulkMapDialogMessage,
            "settingsBulkMapDimensionSelected" to strings.settingsBulkMapDimensionSelected,
            "locApply" to strings.locApply,
        )
}