//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.payanam.database.jvm.DesktopDatabaseEncryptionProbe
import io.payanam.desktop.database.DesktopBootstrapDataLayer
import io.payanam.presentation.data.LocalDataLayer
import org.junit.Test

/**
 * The desktop's NEW-DATABASE journey, walked in-process: first run -> tour -> create -> passphrase ->
 * mandatory dimension setup -> a real, encrypted database with its dimensions saved.
 *
 * **The gap this closes.** The first-run journey stops at the tour's first slide; nothing ever asked the
 * create path to finish, so a create that wrote a database the controller could not open, or a dimension
 * step that silently saved nothing, would look identical to a working one. This walks the whole path the
 * user takes on a fresh machine and asserts the ARTEFACT at the end rather than only the screens on the way.
 *
 * **What it asserts, in the order the user lives it:** the tour renders and can be finished; the chooser
 * offers the create action; the create-passphrase form accepts a policy-valid pair; the mandatory dimension
 * step appears; and then - the part no screenshot can show - the database FILE exists, the FILE is
 * detectably encrypted, and the dimensions the user was asked for are READABLE back through the shared data
 * layer the shell itself reads.
 *
 * The passphrase is data; every label is the app's own copy via the harness's `strings`.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopDatabaseCreationJourneyTest : DesktopAppJourneyHarness() {
    @Test
    fun `a first run creates a new encrypted database and saves its life dimensions`() =
        runAtTheAppWindowSize {
            // The premise, measured: nothing on disk yet, so the create this walks is a genuine first run.
            assertThat(dataRoot.walkTopDown().filter { it.isFile }.toList()).isEmpty()

            // The shell installs the bootstrap layer before a session exists, and the shared init screen reads
            // the INSTALLED layer rather than building repositories of its own.
            LocalDataLayer.install(DesktopBootstrapDataLayer)
            mountShell(freshState(), isLandingReady = false)

            // 1. The tour, asserted by its own first slide.
            awaitNodeWithText(strings.onboardingSlide1Title)
            frame("00-tour", "the onboarding tour", copy = strings.onboardingSlide1Title)

            onNodeWithText(strings.onboardingActionSkip).performClick()
            waitForIdle()

            // 2. The create/import chooser.
            awaitNodeWithText(strings.locCreateNewEmptyDatabase)
            frame("01-chooser", "the create/import chooser", copy = strings.locCreateNewEmptyDatabase)

            onNodeWithText(strings.locCreateNewEmptyDatabase).performClick()
            waitForIdle()

            // 3. The create-passphrase form. The pair must match and pass the shared policy or the create is
            // refused before it starts.
            awaitNodeWithText(strings.dbPassphraseSetupTitle)
            frame("02-passphrase", "the create-passphrase form", copy = strings.dbPassphraseSetupTitle)

            typeIntoField(0, passphrase)
            typeIntoField(1, passphrase)
            onNodeWithText(strings.dbPassphraseSetupAction).performClick()
            waitForIdle()

            // 4. The mandatory dimension step - a new database genuinely needs its life dimensions.
            awaitNodeWithText(strings.dbInitDimensionSetupTitle)
            frame("03-dimensions", "the mandatory dimension step", copy = strings.dbInitDimensionSetupTitle)

            onNodeWithText(strings.dbInitDimensionSetupContinueAction).performClick()
            waitForIdle()

            // 5. The artefact, not the screen: a file, encrypted, with the dimensions readable back. The create
            // runs on the init flow's own dispatcher, so the file is awaited rather than sampled - a zero-byte
            // placeholder exists the instant the path is reserved.
            waitUntil(timeoutMillis = 15_000) { databaseFile.length() > 16L }
            assertThat(databaseFile.exists()).isTrue()
            val header = databaseFile.readBytes().take(16).joinToString("") { "%02x".format(it) }
            assertWithMessage("size=${databaseFile.length()} header=$header")
                .that(DesktopDatabaseEncryptionProbe().isDetectablyEncrypted(databaseFile, "journey"))
                .isTrue()
            // The dimension persist runs AFTER the open that creates the file, so the file's size is not the
            // signal that the setup is done - the readable dimensions are.
            waitUntil(timeoutMillis = 15_000) {
                runCatching { loadedPreferences().dimensionPreferences }.getOrDefault(emptyList()).isNotEmpty()
            }
            val preferences = loadedPreferences()
            frame(
                "04-created",
                "the shell after the new database was created",
                copy = strings.dbInitDimensionSetupTitle,
            )
            assertThat(preferences.dimensionPreferences).isNotEmpty()
        }

    /** Types [value] into the [index]-th editable field on screen, REPLACING what it holds. */
    private fun DesktopComposeUiTest.typeIntoField(
        index: Int,
        value: String,
    ) {
        onAllNodes(hasSetTextAction())[index].performTextReplacement(value)
    }
}
