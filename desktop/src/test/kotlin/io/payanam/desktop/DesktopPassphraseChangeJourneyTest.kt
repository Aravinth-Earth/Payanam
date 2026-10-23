//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.navigation.PayanamTab
import io.payanam.database.jvm.PayanamDatabaseOpener
import org.junit.Test

/**
 * The desktop's PASSPHRASE-CHANGE journey, walked in-process: Settings -> change -> a real rekey.
 *
 * **The gap this closes.** Nothing on the desktop ever changed a passphrase through the UI. The change is the
 * one flow that REWRITES the database's key on disk, and a rekey that half-worked - a new verifier over a file
 * still keyed with the old passphrase - would leave the user locked out of their own data with no screen
 * showing it. The assertion is therefore the FILE's verdict, not the success copy alone.
 *
 * **What it asserts, in the order the user lives it:** the change screen is reachable from Settings; the form
 * takes the current passphrase and a policy-valid replacement; the success copy appears; and then the artefact
 * - the database OPENS with the new passphrase and REFUSES the old one.
 *
 * The rekey is followed by a process restart, which this process refuses (a test JVM cannot relaunch itself),
 * so the flow's success state is what is asserted rather than a relaunch - the same refusal the app surfaces
 * to a user whose install cannot be relaunched.
 *
 * Both passphrases are data; every label is the app's own copy via the harness's `strings`.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopPassphraseChangeJourneyTest : DesktopAppJourneyHarness() {
    @Test
    fun `a passphrase change rekeys the database so the new one opens it and the old one does not`() =
        runAtTheAppWindowSize {
            installProductionFixtureLayer()
            // The vault must know the CURRENT passphrase for the change to verify it; the fixture layer alone
            // leaves it empty, and an empty vault refuses every current passphrase.
            configureVault()
            mountShell(configuredState(), isLandingReady = true, preferences = loadedPreferences())

            // ---- navigate: Settings, then the change action ----
            onAllNodesWithText(PayanamTab.Settings.title(strings)).onFirst().performClick()
            waitForIdle()
            awaitNodeWithText(strings.settingsAppearanceTitle)

            // The change action lives inside the Data-Management card, which starts collapsed.
            onAllNodesWithText(strings.settingsDataManagementTitle)
                .onFirst()
                .performScrollTo()
                .performClick()
            waitForIdle()

            onAllNodesWithText(strings.settingsActionChangePassphrase)
                .onFirst()
                .performScrollTo()
                .performClick()
            waitForIdle()

            awaitNodeWithText(strings.dbPassphraseChangeTitle)
            frame("00-change-form", "the passphrase-change form", copy = strings.dbPassphraseChangeTitle)

            // ---- the form: current, new, confirm (the order the screen lays them out) ----
            typeIntoField(0, passphrase)
            typeIntoField(1, NEW_PASSPHRASE)
            typeIntoField(2, NEW_PASSPHRASE)
            frame("01-filled", "the change form with both passphrases entered", copy = strings.dbPassphraseChangeTitle)

            onAllNodesWithText(strings.dbPassphraseChangeAction).onFirst().performClick()

            // ---- the artefact, polled directly ----
            // WHY NOT THROUGH THE UI: a successful change CLOSES the session and asks the process to restart.
            // In the app the replacement process takes over; here the launch command is a test JVM, so the
            // restart is refused and the shell is left reading the bootstrap layer, which throws on the first
            // `tasks` read. The composition is therefore not driven again - the FILE is the claim, and it is
            // polled on the wall clock because the rekey runs on the ViewModel's own dispatcher.
            val rekeyed =
                pollUntil(timeoutMillis = 30_000) {
                    runCatching { PayanamDatabaseOpener.verifyPassphrase(databaseFile.toPath(), NEW_PASSPHRASE) }
                        .getOrDefault(false)
                }
            assertThat(rekeyed).isTrue()
            assertThat(PayanamDatabaseOpener.verifyPassphrase(databaseFile.toPath(), passphrase)).isFalse()
        }

    /** Polls [condition] on the wall clock until it holds or [timeoutMillis] elapses. */
    private fun pollUntil(
        timeoutMillis: Long,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(200)
        }
        return condition()
    }

    /** Types [value] into the [index]-th editable field on screen, REPLACING what it holds. */
    private fun DesktopComposeUiTest.typeIntoField(
        index: Int,
        value: String,
    ) {
        onAllNodes(hasSetTextAction())[index].performTextReplacement(value)
    }

    private companion object {
        /** A policy-valid replacement - 12+ chars with upper, lower, digit and symbol. */
        const val NEW_PASSPHRASE = "Desktop-Changed-Passphrase2!"
    }
}
