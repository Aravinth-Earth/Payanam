//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

/**
 * Port of the retired Maestro tier's fresh-setup subflow.
 *
 * Onboarding → database creation → passphrase → leaves the app on the dimension setup screen.
 *
 * Maestro opens with `launchApp clearState: true`, which wipes app data before the run. An
 * in-process test has no such switch, and shell routes (`pm clear`, adb) are forbidden, so the
 * equivalent state is reached in two supported ways:
 *
 *  - a **fresh install** (the runner's `-KeepInstalled false` leaves AGP to uninstall the app), or
 *  - when the app is still installed from an earlier cycle, the app's **own** Delete All Data flow.
 *
 * The second path is what makes the suite idempotent, which a repeated-run campaign needs. Both are
 * real user journeys, so neither adds a test-only affordance.
 *
 * Every label below comes from [E2eLabels] — the app's own copy, in the app's current language — so
 * this flow works on a Tamil-locale device exactly as it does on an English one.
 */
class FreshSetup(private val h: E2eHarness) {

    private val l: E2eLabels.Labels get() = h.labels

    /** Brings the app to a clean first-run state and completes the setup journey. */
    fun run() {
        h.awaitRoot()
        reachFreshStart()
        onboarding()
        createDatabase()
        setPassphrase()
        verifyReachedMainScreen()
        h.log("setup=complete")
    }

    /**
     * Brings the app to a usable state, provisioning only if it has to.
     *
     * [run] requires a clean install, so it can only ever be the FIRST class of an instrumentation
     * invocation: every later class in the same run sees the app the earlier one provisioned, and a hard
     * requirement turns that shared state into a failure. That is why the tier used to be invoked one class
     * at a time, paying a full install and a full setup per class.
     *
     * This is the form the FEATURE journeys call. It accepts every state a real run can start in - a clean
     * install, a returning machine whose session has closed, or an app an earlier class already provisioned -
     * and never fabricates a first run. A class that is ABOUT the first-run gates keeps calling [run],
     * because for that class the clean install IS the subject.
     *
     * **It probes for the GATES, not for a main screen.** "Is a bottom-nav label on screen" was the first
     * version of this probe and it was wrong: an earlier class can legitimately leave the app on a sub-screen
     * (the assistant, a detail view) where no nav label is composed, and that is still a provisioned app -
     * measured on the device, that probe sent every class after the first into [run] and failed the run.
     * Asking "is a gate showing" answers the question actually being asked.
     *
     * It also completes the mandatory dimension step, which exists only on a first run, so a call site stays
     * one line.
     */
    fun runIfNeeded() {
        h.awaitRoot()
        when {
            // A CLEAN INSTALL, or a reset: the first-run gates are up.
            h.isAnyOnScreen(*firstRunLabels()) -> {
                h.log("setup=provisioning")
                run() // leaves the app on the mandatory dimension step
                h.scrollTo(l.verifyAndProceed)
                h.click(l.verifyAndProceed)
            }

            // A RETURNING machine: the app process was replaced between invocations, so the session is closed
            // and the unlock gate is up. That is a real user state, and the fixture passphrase opens it - the
            // same step [UnlockTest] makes, for the same reason.
            h.isOnScreen(l.unlock) -> {
                h.log("setup=unlocking")
                h.type(l.passphraseField, TEST_DB_PASSPHRASE)
                h.click(l.unlock)
            }

            // Past every gate already - whatever screen it happens to be on.
            else -> h.log("setup=reused")
        }

        // The FOCUS-MODE onboarding gate is the third state, and it arrives AFTER the shell is up rather
        // than instead of it. On a machine whose focus onboarding is still pending it is what the app opens
        // ON, and with it up no bottom-nav label is composed - measured on the device: every class logged
        // `setup=reused` and then every `goToTab` in every class timed out. It is left the way a user leaves
        // it, with the gate's own skip action.
        h.answerFocusModeGate({ l.chooseYourFocus }, { l.skipFocusMode }, timeoutMs = 5_000)

        h.awaitIdle()
        h.log("setup=ready")
    }

    // ── reaching a clean state ───────────────────────────────────────────────────────────────────

    /**
     * Fails loudly unless the app really is at first-run.
     *
     * There is no in-process `clearState`, and the two apparent workarounds both fail:
     *
     *  - **the app's own Delete All Data flow cannot be used from a test.** It works — the wipe
     *    succeeds, then the app logs "restarting process for clean Room/Hilt re-initialization" and
     *    kills its own process. The instrumentation dies with it, so the run reports no result at
     *    all rather than a failure. Verified the hard way.
     *  - **reinstalling over a kept install does not clear data**; the app is retained with its
     *    database.
     *
     * That leaves a fresh install, which the runner produces with `-KeepInstalled false` (AGP
     * uninstalls after each run). The check below turns "wrong device state" into one clear message
     * instead of a confusing assertion failure twenty steps later.
     *
     * IT WAITS, rather than sampling once. The first version of this check called the no-wait
     * `isOnScreen` on each title in turn, which reads the semantics tree at an arbitrary instant: on a
     * genuinely fresh install the startup gate has usually not composed anything yet, so the sample saw
     * an empty tree and reported "the app is already provisioned" — a failure that survived a clean
     * install, because the race was in the CHECK, not in the device state. The gate is now reached by
     * polling until a first-run screen actually appears, on the same "wait for the expected state
     * rather than trusting a settle" principle the rest of the harness runs on
     * (`docs/e2e/SPEC.md`, structural constraint 4).
     */
    private fun reachFreshStart() {
        val start = runCatching { h.awaitAnyOnScreen(*firstRunLabels()) }.getOrNull()
        check(start != null) {
            "the first-run journey needs a clean app, but the app is already provisioned " +
                "(no first-run screen appeared within 60s). " +
                "Run with '-KeepInstalled:\$false' so AGP uninstalls it between runs."
        }
        h.log("first-run screen=$start")
    }

    /**
     * Every label a first run can open on.
     *
     * A fresh install shows the onboarding tour; a re-installed-but-empty app can go straight to
     * database init. The tour contributes all three of its labels so the signal does not depend on
     * which slide the pager resumed at — see [tourLabels].
     */
    private fun firstRunLabels(): Array<String> = (tourLabels() + l.welcomeToPayanam).toTypedArray()

    /** Every label the tour can be showing, whichever slide the pager is on. */
    private fun tourLabels(): List<String> = tourLabels(l)

    // ── onboarding tour ──────────────────────────────────────────────────────────────────────────

    /**
     * Dismisses the onboarding tour if this run landed on it.
     *
     * The tour's forward action reads "Next" on the first two slides and "Get Started" on the last, and
     * "Skip Tour" is only offered off the last slide — so which of the three is on screen depends on
     * which slide the pager resumed at. Advancing to the last slide and then finishing is the one path
     * that works from any slide, and it only ever runs while the tour is actually on screen.
     *
     * Replaces a click on a single title: the tour has no title constant of its own, so the old code
     * reached for slide 1's copy and got a screen that may not exist. Nothing here asserts WHICH slide
     * is showing, only that a tour is showing and that it gets dismissed.
     */
    private fun onboarding() {
        if (!h.isAnyOnScreen(*tourLabels().toTypedArray())) return
        h.log("onboarding=shown")
        // Page forward to the last slide, where the button finishes the tour instead of paging. Bounded
        // by TOUR_MAX_SLIDES so a tour that already ends advances to the end and stops.
        repeat(TOUR_MAX_SLIDES) {
            if (h.isOnScreen(l.onboardingSkip)) return@repeat
            if (!h.isOnScreen(l.onboardingNext)) return@repeat
            h.click(l.onboardingNext)
            h.awaitIdle()
        }
        h.assertVisible(l.onboardingAction)
        h.click(l.onboardingAction)
        h.awaitIdle()
    }

    // ── database init ────────────────────────────────────────────────────────────────────────────

    private fun createDatabase() {
        h.assertVisible(l.welcomeToPayanam)
        h.click(l.createNewEmptyDatabase)
    }

    // ── passphrase setup ─────────────────────────────────────────────────────────────────────────

    private fun setPassphrase() {
        h.assertVisible(l.secureYourDatabase)
        h.type(l.passphraseField, TEST_DB_PASSPHRASE)
        h.type(l.confirmPassphraseField, TEST_DB_PASSPHRASE)
        // Maestro calls hideKeyboard here; Compose's test input never raises the IME, so it is a
        // no-op and the step is intentionally dropped.
        h.click(l.setPassphraseAndContinue)
    }

    private fun verifyReachedMainScreen() {
        h.assertVisible(l.dimensionsTitle)
    }

    companion object {
        /** Disposable test-database passphrase — the run creates and destroys that database itself,
         *  so this protects nothing. Fixed rather than generated because provisioning and unlocking
         *  happen in two separate invocations that both have to agree on the value. */
        const val TEST_DB_PASSPHRASE = "E2ETestPass!2026"

        /** The tour has three slides, so "advance until the last one" cannot loop forever. */
        const val TOUR_MAX_SLIDES = 3

        /**
         * Every label the onboarding tour can be showing, in [l]'s language.
         *
         * The tour is a three-slide pager whose forward action reads "Next" until the last slide and
         * "Get Started" on it, and whose "Skip Tour" action disappears on the last slide — so which
         * single label identifies it depends on the slide the pager resumed at. Shared with
         * [ImportPickerUnificationTest], which has its own onboarding precheck and used to test for the
         * FIRST slide's title.
         */
        fun tourLabels(l: E2eLabels.Labels): List<String> = listOf(l.onboardingAction, l.onboardingNext, l.onboardingSkip)
    }
}
