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
 * The reset's own journey: the owner's ruling, measured on the device.
 *
 * Owner's ruling (2026-10-14): *"When a user is using a reset option which means user already know how an app
 * works only then they will be using the reset option. So in that case we don't need to show them the
 * onboarding flow once more."*
 *
 * WHAT WAS BROKEN (build 2038, the owner's own exported log): the reset deleted the database and the
 * credential, set a new passphrase, and then the app bounced to the UNLOCK gate - a gate for a credential the
 * reset had just deleted - and from there into the first-run flow: the tour, then the create/import chooser,
 * over a database the user had just asked to be replaced.
 *
 * WHAT THIS ASSERTS, in the order the user lives it:
 *  1. the unlock gate's reset entry and its confirmation are reachable,
 *  2. the app lands on the CREATE form of the shared database-init gate - no tour, no create/import chooser,
 *  3. the new passphrase is collected there, the mandatory life-dimension step follows (a replaced database
 *     genuinely needs it), and the app comes up - with no unlock re-prompt anywhere in the run.
 *
 * REQUIRES AN ALREADY-PROVISIONED INSTALL, and that is the point rather than a shortcut: a reset needs a
 * database and a configured passphrase to exist first. So this class runs as its own invocation against the
 * install `FullJourneyTest`/`FreshSetup` provisioned, exactly as [UnlockTest] does - see that class's KDoc for
 * why one instrumentation run cannot host both halves.
 *
 * Locale: every label comes from [E2eLabels], the app's own copy in the app's current language, so this runs
 * unchanged on a Tamil-locale device. The passphrase is the suite's shared disposable fixture value, typed
 * into the app's own field on a database the run creates and destroys itself.
 */
@RunWith(AndroidJUnit4::class)
class ResetJourneyTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-RESET")

    /** The app's own copy in the app's current language. */
    private val l: E2eLabels.Labels get() = h.labels

    /** The device sleeps on a short timeout mid-build; a stopped Activity has no Compose tree. */
    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun resetLandsInTheAppWithoutOnboarding() {
        h.awaitRoot()

        // The premise, checked rather than assumed: this invocation must start on the unlock gate, which is
        // what a restart with a configured passphrase and a database produces. A run that starts anywhere else
        // is wrong device state, not a defect in the reset.
        check(h.isOnScreen(l.unlock)) {
            "the reset journey needs the UNLOCK gate, but it is not on screen. Run this class as its own " +
                "invocation against an install a fresh-setup run already provisioned " +
                "(run-inprocess-tests.ps1 -TestClass io.payanam.e2e.ResetJourneyTest)."
        }
        h.capture("01-unlock-gate")
        h.log("reset=starting-at-unlock-gate")

        // ── the reset, through its own confirmation ────────────────────────────────────────────────────────
        h.click(l.forgotPassphrase)
        h.awaitIdle()
        h.capture("02-reset-confirm")
        h.click(l.resetConfirm)
        h.awaitIdle()

        // ── what the reset hands over to ───────────────────────────────────────────────────────────────────
        h.assertVisible(l.secureYourDatabase)
        h.capture("03-create-form")
        h.log("reset=landed-on-create-form")

        // The owner's ruling, checked both ways. The tour is the thing that must not come back; the
        // create/import chooser is the second question that must not be asked over a database the user had
        // just asked to be replaced.
        check(!h.isAnyOnScreen(*FreshSetup.tourLabels(l).toTypedArray())) {
            "the onboarding tour is on screen after a reset - the owner's ruling says it must not be"
        }
        h.assertNotVisible(l.createNewEmptyDatabase)
        h.log("reset=no-tour-no-chooser")

        // ── the passphrase for the database that replaces the wiped one ─────────────────────────────────────
        h.type(l.passphraseField, FreshSetup.TEST_DB_PASSPHRASE)
        h.type(l.confirmPassphraseField, FreshSetup.TEST_DB_PASSPHRASE)
        h.capture("04-passphrase-typed")
        h.click(l.setPassphraseAndContinue)

        // ── the mandatory life-dimension step, then the app ────────────────────────────────────────────────
        // A REPLACED database is a new database: it has no dimensions, so the one step that is genuinely
        // needed again is this one. Its absence would leave the app with an empty life_dimensions catalog.
        h.assertVisible(l.dimensionsTitle)
        h.capture("05-dimension-step")
        h.log("reset=dimension-step")
        h.click(l.verifyAndProceed)

        // Creating the database RESTARTS the activity (the app applies the language preference through a
        // recreate), and the restarted activity re-resolves the startup gate. Every NEW database is then asked
        // the focus-mode question once - measured on this leg's own log: `Startup gate moved; the graph follows
        // it [from="time", to="focus_mode_selection"]` right after `route="time"`. That is not onboarding: it
        // is a question about THIS database, in the same class as the dimension step above, and the suite's
        // own answer to it is the app's "I'll choose later". The same helper the first-run leg uses, so both
        // legs answer the same gate the same way - see [E2eHarness.answerFocusModeGate], which waits for the
        // gate and returns quietly when it does not arrive, rather than assuming either way.
        h.answerFocusModeGate({ l.chooseYourFocus }, { l.skipFocusMode })

        h.assertVisible(l.perDimension)
        h.capture("06-in-the-app")
        h.log("reset=landed-in-app")

        // No unlock re-prompt: the gate that ask for a credential is not reachable from here, and the app is
        // showing its landing screen - asserted last, because this is the outcome the whole leg exists for.
        check(!h.isOnScreen(l.unlock)) {
            "the unlock gate came back after the reset - the reset's own passphrase must have opened the session"
        }
    }
}
