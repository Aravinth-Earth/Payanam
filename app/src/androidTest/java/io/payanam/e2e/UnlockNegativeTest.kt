//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.payanam.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The unlock gate's negative journey: a wrong passphrase is rejected, and repeated failures escalate to
 * the shared lockout.
 *
 * **What was uncovered.** The tier proved the happy unlock ([UnlockTest]) and the reset the gate offers
 * ([ResetJourneyTest]), but never that the gate REFUSES anything. A gate that accepted the first wrong
 * passphrase, or that never locked out, would have looked identical from every existing assertion — and
 * that is the one behaviour the credential exists for.
 *
 * **What it asserts, in the order the user lives it:**
 *  1. the first wrong passphrase is refused with the app's own "wrong passphrase" copy,
 *  2. the second is refused the same way (the shared policy assigns attempt 1 and 2 a zero delay),
 *  3. the THIRD crosses the policy's first threshold (30 seconds) and surfaces the lockout copy,
 *  4. the gate is still up — no session was opened by any of the failures.
 *
 * **The threshold comes from the shared policy, not a guess.** `SharedPassphraseLockoutPolicy` assigns
 * attempt 1 and 2 a zero delay and attempt 3 the first non-zero delay, so the third refusal is the first
 * that renders the lockout message. The count is written as that fact, and the copy is matched by the
 * lockout string's own prefix (the message is formatted with the remaining seconds, which change).
 *
 * **REQUIRES AN ALREADY-PROVISIONED INSTALL**, and that is the point rather than a shortcut: the unlock
 * gate appears only in a process that starts with a configured passphrase and no open session, so this
 * class runs as its OWN invocation against the install a fresh-setup run provisioned — exactly as
 * [UnlockTest] and [ResetJourneyTest] do. See [UnlockTest]'s KDoc for why one instrumentation run cannot
 * host both halves. A run that starts anywhere else is wrong device state, and the premise check below
 * says so in one line rather than failing twenty steps later.
 *
 * Locale: every label comes from [E2eLabels], the app's own copy in the app's current language. The wrong
 * passphrase is data the test types in, not localized copy.
 */
@RunWith(AndroidJUnit4::class)
class UnlockNegativeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-UNLOCK-NEGATIVE")

    /** The app's own copy in the app's current language. */
    private val l: E2eLabels.Labels get() = h.labels

    /** The device sleeps on a short timeout mid-build; a stopped Activity has no Compose tree. */
    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun wrongPassphraseIsRejectedAndRepeatedFailuresLockOut() {
        h.awaitRoot()

        // The premise, checked rather than assumed: this invocation must start on the unlock gate, which
        // is what a restart with a configured passphrase and a database produces.
        check(h.isOnScreen(l.unlock)) {
            "the unlock negative journey needs the UNLOCK gate, but it is not on screen. Run this class " +
                "as its own invocation against an install a fresh-setup run already provisioned."
        }
        h.capture("01-unlock-gate")

        // ── attempts 1 and 2: refused, no lockout yet ───────────────────────────────────────────────────
        repeat(2) { index ->
            h.replaceType(l.passphraseField, WRONG_PASSPHRASE)
            h.click(l.unlock)
            h.assertVisible(l.ui.dbPassphraseUnlockErrorInvalid)
            h.log("wrong-attempt=${index + 1} result=invalid")
        }

        // ── attempt 3: crosses the shared policy's first threshold ──────────────────────────────────────
        h.replaceType(l.passphraseField, WRONG_PASSPHRASE)
        h.click(l.unlock)
        // The lockout copy is formatted with the remaining seconds, which change every second, so it is
        // matched by its own prefix — everything before the number. Locale-correct on both languages.
        val lockoutPrefix = l.ui.dbPassphraseUnlockErrorLocked.substringBefore('%')
        h.assertVisibleSubstring(lockoutPrefix)
        h.log("wrong-attempt=3 result=locked")
        h.capture("02-lockout")

        // No failure opened a session: the gate is still what the user is looking at.
        check(h.isOnScreen(l.unlock)) { "the unlock gate should still be up after a lockout" }
        h.log("unlock-negative=done")
    }

    private companion object {
        /**
         * A passphrase the suite KNOWS is wrong — long enough to satisfy the setup rules so the refusal is
         * the vault's verdict and not the form's, and never the suite's disposable fixture value.
         */
        const val WRONG_PASSPHRASE = "DefinitelyNotThePassphrase!2026"
    }
}
