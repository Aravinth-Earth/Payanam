//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Test

/**
 * The desktop's unlock gate REFUSES a wrong passphrase, and repeated failures lock out - the same journey
 * Android's `UnlockNegativeTest` walks on a device.
 *
 * **The gap this closes.** The desktop's first-run journey opens the shell; nothing ever asked the UNLOCK
 * gate for anything, so a gate that accepted the first wrong passphrase, or that never locked out, would
 * look identical to a working one. That is the one behaviour the credential exists for.
 *
 * **What it asserts, in the order the user lives it:** the first wrong passphrase is refused with the app's
 * own copy, the second the same way (the shared policy assigns attempts 1 and 2 a zero delay), and the
 * THIRD crosses the policy's first threshold and surfaces the lockout copy.
 *
 * The gate is the start destination because the state's session is CLOSED ([lockedState]), and the vault is
 * configured ([configureVault]) so the refusal is the VAULT's verdict on a wrong passphrase rather than an
 * empty vault refusing everything.
 *
 * Every label is the app's own copy via the harness's `strings`; the wrong passphrase is data.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopUnlockNegativeJourneyTest : DesktopAppJourneyHarness() {
    @Test
    fun `a wrong passphrase is refused and repeated failures lock out`() =
        runAtTheAppWindowSize {
            installProductionFixtureLayer()
            configureVault()
            mountShell(state = lockedState(), isLandingReady = false, preferences = loadedPreferences())

            awaitNodeWithText(strings.dbPassphraseUnlockAction)
            frame("00-unlock-gate", "the unlock gate", copy = strings.dbPassphraseUnlockAction)

            // Attempts 1 and 2: refused, no lockout yet.
            repeat(2) { index ->
                enterPassphrase(WRONG_PASSPHRASE)
                submitUnlock()
                awaitNodeWithText(strings.dbPassphraseUnlockErrorInvalid)
                frame(
                    "0${index + 1}-refused",
                    "the unlock gate after a wrong passphrase",
                    copy = strings.dbPassphraseUnlockErrorInvalid,
                )
            }

            // Attempt 3: crosses the shared policy's first threshold.
            enterPassphrase(WRONG_PASSPHRASE)
            submitUnlock()
            // The lockout copy is formatted with the remaining seconds, which change every second, so it is
            // matched by its own prefix.
            val lockoutPrefix = strings.dbPassphraseUnlockErrorLocked.substringBefore('%')
            awaitTextContaining(lockoutPrefix)
            frame("03-locked-out", "the unlock gate after the lockout", copy = lockoutPrefix)

            // No failure opened a session: the gate is still what the user is looking at.
            awaitNodeWithText(strings.dbPassphraseUnlockAction)
        }

    /** Types [value] into the gate's passphrase field, REPLACING what it holds (it keeps the value after a failure). */
    private fun DesktopComposeUiTest.enterPassphrase(value: String) {
        onAllNodes(hasSetTextAction()).onFirst().performTextReplacement(value)
    }

    private fun DesktopComposeUiTest.submitUnlock() {
        onAllNodesWithText(strings.dbPassphraseUnlockAction).onFirst().performClick()
        waitForIdle()
    }

    /** Waits for a node whose text CONTAINS [fragment]. */
    private fun DesktopComposeUiTest.awaitTextContaining(
        fragment: String,
        timeoutMillis: Long = 15_000,
    ) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithText(fragment, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        /** A passphrase the run KNOWS is wrong - long enough to satisfy the setup rules, never the fixture. */
        const val WRONG_PASSPHRASE = "DefinitelyNotThePassphrase!2026"
    }
}
