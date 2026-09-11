//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

/**
 * Port of `maestro/e2e/subflows/fresh_setup.yaml`.
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
 */
class FreshSetup(private val h: E2eHarness) {

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
     */
    private fun reachFreshStart() {
        check(h.isOnScreen(TOUR_TITLE) || h.isOnScreen(DB_INIT_TITLE)) {
            "the first-run journey needs a clean app, but the app is already provisioned. " +
                "Run with '-KeepInstalled:\$false' so AGP uninstalls it between runs."
        }
    }

    // ── onboarding tour ──────────────────────────────────────────────────────────────────────────

    private fun onboarding() {
        // A fresh install shows the tour; a re-installed-but-empty app can go straight to DB init.
        if (h.isOnScreen(TOUR_TITLE)) {
            h.click("Skip Tour")
        }
    }

    // ── database init ────────────────────────────────────────────────────────────────────────────

    private fun createDatabase() {
        h.assertVisible(DB_INIT_TITLE)
        h.click("Create New Empty Database")
    }

    // ── passphrase setup ─────────────────────────────────────────────────────────────────────────

    private fun setPassphrase() {
        h.assertVisible("Secure your database")
        h.type("Passphrase", TEST_DB_PASSPHRASE)
        h.type("Confirm passphrase", TEST_DB_PASSPHRASE)
        // Maestro calls hideKeyboard here; Compose's test input never raises the IME, so it is a
        // no-op and the step is intentionally dropped.
        h.click("Set passphrase and continue")
    }

    private fun verifyReachedMainScreen() {
        h.assertVisible(DIMENSIONS_TITLE)
    }

    companion object {
        /** Same value the Maestro flows use. */
        /** Disposable test-database passphrase — the run creates and destroys that database itself,
         *  so this protects nothing. Fixed rather than generated because provisioning and unlocking
         *  happen in two separate invocations that both have to agree on the value. */
        const val TEST_DB_PASSPHRASE = "E2ETestPass!2026"

        const val TOUR_TITLE = "Your Privacy First"
        const val DIMENSIONS_TITLE = "Set up your life dimensions"
        const val DB_INIT_TITLE = "Welcome To Payanam"
    }
}
