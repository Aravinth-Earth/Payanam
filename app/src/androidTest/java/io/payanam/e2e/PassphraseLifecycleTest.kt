//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextClearance
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.payanam.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * In-process coverage of the passphrase lifecycle: setup-screen validation, the show/hide
 * visibility toggle, a valid first-time setup, and the wrong-current-passphrase guard on the
 * change screen.
 *
 * REQUIRES A FRESH INSTALL — the run creates and destroys its own database, so it must start
 * from first-run like every other e2e class. Run with `-KeepInstalled:$false` so AGP uninstalls
 * the app between runs.
 *
 * Unlike the other e2e classes, this one does NOT call [FreshSetup].run(). It walks the
 * first-run flow manually (onboarding → database chooser → passphrase setup) so the setup
 * screen's validation can be exercised BEFORE a valid passphrase is submitted. After setup
 * completes and the main app is reached, the change screen is opened from
 * Settings → Data Management.
 *
 * LOCALE: every label is read from [E2eLabels] — the app's own copy in the app's current
 * language — so the assertions hold on a Tamil-locale device too. Values the test TYPES into
 * the passphrase fields are data, not localized copy, and stay literals.
 *
 * Known gap: the change screen's success path (correct current passphrase) deliberately ends
 * with the WRONG-current assertion, because a successful change triggers a process restart that
 * would end the instrumentation run.
 */
@RunWith(AndroidJUnit4::class)
class PassphraseLifecycleTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-PASSPHRASE")

    /** App copy in the app's current language. */
    private val l get() = h.labels

    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun passphraseLifecycle() {
        // ── walk to the passphrase setup screen, exercising validation BEFORE setup ──────────────
        h.awaitRoot()
        reachFreshStart()
        walkOnboarding()
        walkDatabaseChooser()
        h.assertVisible(l.ui.dbPassphraseSetupTitle)
        h.log("setup-screen=reached")

        // ── 2. every validation error, in policy order ──────────────────────────────────────────
        assertValidationError(TOO_SHORT, TOO_SHORT, l.ui.dbPassphraseErrorMinLength)
        assertValidationError(NO_UPPERCASE, NO_UPPERCASE, l.ui.dbPassphraseErrorUppercase)
        assertValidationError(NO_LOWERCASE, NO_LOWERCASE, l.ui.dbPassphraseErrorLowercase)
        assertValidationError(NO_DIGIT, NO_DIGIT, l.ui.dbPassphraseErrorDigit)
        assertValidationError(NO_SYMBOL, NO_SYMBOL, l.ui.dbPassphraseErrorSymbol)

        // A valid passphrase in field 1, a DIFFERENT valid one in field 2 → mismatch.
        clearAndType(l.ui.dbPassphraseInputLabel, VALID_PASSPHRASE)
        clearAndType(l.ui.dbPassphraseConfirmLabel, VALID_PASSPHRASE_ALTERNATE)
        h.click(l.ui.dbPassphraseSetupAction)
        h.assertVisible(l.ui.dbPassphraseErrorMismatch)
        h.assertVisible(l.ui.dbPassphraseSetupTitle) // still on the setup screen
        h.log("validation=mismatch")

        // ── 3. show/hide visibility toggle ───────────────────────────────────────────────────────
        exerciseVisibilityToggle()
        h.log("toggle=exercised")

        // ── 4. valid passphrase completes setup ──────────────────────────────────────────────────
        clearAndType(l.ui.dbPassphraseInputLabel, TEST_DB_PASSPHRASE)
        clearAndType(l.ui.dbPassphraseConfirmLabel, TEST_DB_PASSPHRASE)
        h.click(l.ui.dbPassphraseSetupAction)

        h.assertVisible(l.ui.dbInitDimensionSetupTitle)
        h.log("setup=complete")

        // ── continue to the main app ─────────────────────────────────────────────────────────────
        h.scrollTo(l.ui.dbInitDimensionSetupContinueAction)
        h.click(l.ui.dbInitDimensionSetupContinueAction)
        h.assertVisible(l.ui.locPerDimension)
        h.log("main-app=reached")

        // ── 5. change screen: wrong current passphrase is rejected ──────────────────────────────
        h.clickNav(l.ui.settingsTitle)
        h.scrollTo(l.ui.settingsDataManagementTitle)
        h.click(l.ui.settingsDataManagementTitle)
        h.scrollTo(l.ui.settingsActionChangePassphrase)
        h.click(l.ui.settingsActionChangePassphrase)
        h.assertVisible(l.ui.dbPassphraseChangeTitle)
        h.log("change-screen=reached")

        h.type(l.ui.dbPassphraseChangeCurrentLabel, WRONG_CURRENT_PASSPHRASE)
        h.type(l.ui.dbPassphraseChangeNewLabel, NEW_PASSPHRASE)
        h.type(l.ui.dbPassphraseChangeConfirmLabel, NEW_PASSPHRASE)
        h.click(l.ui.dbPassphraseChangeAction)

        h.assertVisible(l.ui.dbPassphraseChangeErrorCurrentInvalid)
        h.assertVisible(l.ui.dbPassphraseChangeTitle) // still on the change screen
        h.log("wrong-current=rejected")
    }

    /**
     * Fails loudly unless the app really is at first-run.
     *
     * Copied from [FreshSetup]: a fresh install shows the onboarding tour; a re-installed-but-empty
     * app can go straight to database init. Either way one of these labels must appear within 60 s,
     * and a missing first-run screen means the device is already provisioned — a precondition failure
     * that should be reported as such, not as a confusing assertion twenty steps later.
     */
    private fun reachFreshStart() {
        val start = runCatching {
            h.awaitAnyOnScreen(
                l.ui.onboardingActionStart,
                l.ui.onboardingActionNext,
                l.ui.onboardingActionSkip,
                l.ui.locWelcomeToPayanam,
            )
        }.getOrNull()
        check(start != null) {
            "the passphrase lifecycle needs a clean app, but the app is already provisioned " +
                "(no first-run screen appeared within 60s). " +
                "Run with '-KeepInstalled:\$false' so AGP uninstalls it between runs."
        }
        h.log("first-run screen=$start")
    }

    /**
     * Dismisses the onboarding tour if this run landed on it.
     *
     * Copied from [FreshSetup]: the tour's forward action reads "Next" until the last slide and
     * "Get Started" on it, so advancing to the last slide and then finishing is the one path that
     * works from whichever slide the pager resumed at. Bounded by [TOUR_MAX_SLIDES] so a tour that
     * already ends advances to the end and stops.
     */
    private fun walkOnboarding() {
        val tourLabels = listOf(
            l.ui.onboardingActionStart,
            l.ui.onboardingActionNext,
            l.ui.onboardingActionSkip,
        )
        if (!h.isAnyOnScreen(*tourLabels.toTypedArray())) return
        h.log("onboarding=shown")
        repeat(TOUR_MAX_SLIDES) {
            if (h.isOnScreen(l.ui.onboardingActionSkip)) return@repeat
            if (!h.isOnScreen(l.ui.onboardingActionNext)) return@repeat
            h.click(l.ui.onboardingActionNext)
            h.awaitIdle()
        }
        h.assertVisible(l.ui.onboardingActionStart)
        h.click(l.ui.onboardingActionStart)
        h.awaitIdle()
    }

    /** Copied from [FreshSetup]: the database chooser's welcome title, then the create-new action. */
    private fun walkDatabaseChooser() {
        h.assertVisible(l.ui.locWelcomeToPayanam)
        h.click(l.ui.locCreateNewEmptyDatabase)
    }

    /**
     * Types [passphrase] and [confirmation] into the setup fields, submits, and requires
     * [expectedError] to appear — proving the policy rejected exactly this input and nothing else.
     */
    private fun assertValidationError(passphrase: String, confirmation: String, expectedError: String) {
        clearAndType(l.ui.dbPassphraseInputLabel, passphrase)
        clearAndType(l.ui.dbPassphraseConfirmLabel, confirmation)
        h.click(l.ui.dbPassphraseSetupAction)
        h.assertVisible(expectedError)
        h.assertVisible(l.ui.dbPassphraseSetupTitle) // a rejected submit must not advance
        h.log("validation=${expectedError.take(40)}")
    }

    /**
     * Exercises the passphrase field's show/hide visibility toggle.
     *
     * Both the passphrase field and the confirm field start masked, so the tree carries TWO
     * "Show passphrase" content descriptions and none for "Hide passphrase". Tapping the first
     * toggle flips exactly one of them, which is what the counts assert: 2 → 1 show toggles and
     * 0 → 1 hide toggles. Tapping the hide toggle restores the original 2 / 0 state, proving the
     * toggle is reversible and the underlying visibility state actually changed (the icon's
     * content description is the user-visible state of the toggle).
     */
    private fun exerciseVisibilityToggle() {
        check(h.count(l.ui.dbPassphraseShowToggle) == 2) {
            "expected two masked passphrase fields, found ${h.count(l.ui.dbPassphraseShowToggle)}"
        }
        check(h.count(l.ui.dbPassphraseHideToggle) == 0) {
            "expected no visible passphrase before any toggle tap"
        }

        h.click(l.ui.dbPassphraseShowToggle)
        h.awaitIdle()
        check(h.count(l.ui.dbPassphraseHideToggle) == 1) {
            "after one show toggle tap, expected exactly one visible passphrase field"
        }
        check(h.count(l.ui.dbPassphraseShowToggle) == 1) {
            "the other field must stay masked until its own toggle is tapped"
        }

        h.click(l.ui.dbPassphraseHideToggle)
        h.awaitIdle()
        check(h.count(l.ui.dbPassphraseShowToggle) == 2) {
            "tapping hide must restore both fields to masked"
        }
        check(h.count(l.ui.dbPassphraseHideToggle) == 0) {
            "no field should stay visible after the hide toggle is tapped"
        }
    }

    /**
     * Clears a text field before typing, so a re-typed value replaces rather than appends to the
     * previous one — the same pattern [TasksCrudTest] uses when it edits an existing field.
     *
     * The clearance also matters for state: the setup screen's own `onValueChange` calls
     * `viewModel.clearError()`, so clearing wipes the previous validation error and the next
     * submit starts from a clean error state.
     */
    private fun clearAndType(label: String, value: String) {
        rule.onAllNodes(hasSetTextAction() and h.labelled(label)).onFirst().performTextClearance()
        h.type(label, value)
        h.awaitIdle()
    }

    private companion object {
        /** The tour has three slides, so "advance until the last one" cannot loop forever. */
        const val TOUR_MAX_SLIDES = 3

        /** Disposable test-database passphrase — the run creates and destroys that database itself.
         *  Fixed rather than generated because provisioning and unlocking happen in two separate
         *  invocations that both have to agree on the value. Same value [FreshSetup] uses. */
        const val TEST_DB_PASSPHRASE = "E2ETestPass!2026"

        // ── validation inputs: each violates exactly ONE policy rule ──────────────────────────────
        // Policy order is length → uppercase → lowercase → digit → symbol, and only the first
        // failing rule is reported (see SharedPassphrasePolicy.validate), so each input below is
        // crafted to pass every rule before the one it targets.

        /** 7 chars — has upper, lower, digit and symbol, but under the 12-character minimum. */
        const val TOO_SHORT = "Short1!"

        /** 12 chars, lowercase + digit + symbol, no uppercase. */
        const val NO_UPPERCASE = "lowercase1!x"

        /** 12 chars, uppercase + digit + symbol, no lowercase. */
        const val NO_LOWERCASE = "UPPERCASE1!X"

        /** 14 chars, upper + lower + symbol, no digit. */
        const val NO_DIGIT = "NoDigitsHere!!"

        /** 15 chars, upper + lower + digit, no symbol. */
        const val NO_SYMBOL = "NoSymbolsHere12"

        /** A valid passphrase for the mismatch case — valid format, wrong companion value. */
        const val VALID_PASSPHRASE = "ValidPass!2026"

        /** [VALID_PASSPHRASE] with one character changed: still valid, still mismatched. */
        const val VALID_PASSPHRASE_ALTERNATE = "ValidPass!2027"

        // ── change-screen inputs ──────────────────────────────────────────────────────────────────

        /** Valid FORMAT so it passes the new-passphrase policy, but wrong so the vault rejects it. */
        const val WRONG_CURRENT_PASSPHRASE = "WrongPass!2026"

        const val NEW_PASSPHRASE = "NewTestPass!2027"
    }
}
