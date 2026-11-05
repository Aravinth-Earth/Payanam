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
import com.google.common.truth.Truth.assertWithMessage
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
 * **THE TERMINAL OUTCOME COMES FROM THE JOURNEY'S OWN LOG, NOT FROM THE FILE.** The earlier version of this
 * case opened the database every 200 ms WHILE the rekey was still writing it, so the probe was a COMPETING
 * connection against the very write it was waiting for: the rekey's lock request was refused, this driver
 * drops the SQLite return code, and the flow rolled back - the test caused the failure it was watching for.
 * The file was also the wrong instrument, because the opener refuses any sidecar beside the main file. The
 * session log is the flow's own report of when it finished, and the file is read only AFTER that report
 * arrives.
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

            // ---- the terminal outcome, read from the journey's OWN session log ----
            // NOT BY POLLING THE FILE: polling the database while the rekey is still writing it made the
            // probe a competing connection against the very write it was waiting for, and a rekey that
            // loses that race rolls the file back. The log is the flow's own account of when it finished.
            val outcome = awaitTerminalOutcome()
            // The message is CONCATENATED, not formatted: the session log is app output and may carry a
            // stray `%`, which a format string would treat as a conversion and blow up on.
            assertWithMessage(
                "the passphrase change did not succeed; the session log was:\n" + sessionLogText(),
            ).that(outcome.name).isEqualTo(TerminalOutcome.SUCCEEDED.name)

            // ---- the artefact, read only now that the flow has said it is done ----
            // WHY NOT THROUGH THE UI: a successful change CLOSES the session and asks the process to restart.
            // In the app the replacement process takes over; here the launch command is a test JVM, so the
            // restart is refused and the shell is left reading the bootstrap layer, which throws on the first
            // `tasks` read. The composition is therefore not driven again - the FILE is the claim.
            assertThat(PayanamDatabaseOpener.verifyPassphrase(databaseFile.toPath(), NEW_PASSPHRASE)).isTrue()
            assertThat(PayanamDatabaseOpener.verifyPassphrase(databaseFile.toPath(), passphrase)).isFalse()
        }

    /**
     * Waits for the passphrase change to reach a terminal state, and reports which one it reached.
     *
     * **THE INSTRUMENT IS THE CHANGE FLOW'S OWN TERMINAL LINE**, read through the harness's
     * [sessionLogText]: the change ViewModel's "Passphrase changed successfully" or "Passphrase change
     * failed, restoring backup" for the attempt as a whole. The failure line is checked first, and it is
     * terminal on its own.
     *
     * **THE REKEY'S MARKER IS DELIBERATELY NOT REQUIRED.** An earlier version waited for the rekey's
     * "Database rekey completed" as well, but that marker belongs to a LOWER LAYER which does not guarantee
     * it on every successful path. Gating on it made this test depend on someone else's logging, and a run
     * that had actually SUCCEEDED - its "Passphrase changed successfully" line present in the session log -
     * still ran the wait out to its full timeout and failed.
     *
     * **WHY THE SUCCESS LINE ALONE IS STILL CORRECT.** The file is read only AFTER the flow has said it is
     * finished, and the success line is written AFTER the vault update, so a vault update that fails takes
     * the FAILURE branch instead. The pair was belt-and-braces on a marker this test does not own.
     */
    private fun awaitTerminalOutcome(timeoutMillis: Long = 30_000): TerminalOutcome {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val text = sessionLogText()
            if (text.contains(FAILURE_LINE)) return TerminalOutcome.FAILED
            if (text.contains(SUCCESS_LINE)) {
                return TerminalOutcome.SUCCEEDED
            }
            Thread.sleep(200)
        }
        error(
            "the passphrase change reached no terminal state within ${timeoutMillis}ms; " +
                "the session log was:\n${sessionLogText()}",
        )
    }

    /** Types [value] into the [index]-th editable field on screen, REPLACING what it holds. */
    private fun DesktopComposeUiTest.typeIntoField(
        index: Int,
        value: String,
    ) {
        onAllNodes(hasSetTextAction())[index].performTextReplacement(value)
    }

    /** Which way the passphrase change ended, as the flow's own log reports it. */
    private enum class TerminalOutcome {
        SUCCEEDED,
        FAILED,
    }

    private companion object {
        /** A policy-valid replacement - 12+ chars with upper, lower, digit and symbol. */
        const val NEW_PASSPHRASE = "Desktop-Changed-Passphrase2!"

        /** The change's terminal line for an attempt that finished. */
        const val SUCCESS_LINE = "Passphrase changed successfully"

        /** The change's terminal line for an attempt that failed and rolled back. */
        const val FAILURE_LINE = "Passphrase change failed, restoring backup"
    }
}
