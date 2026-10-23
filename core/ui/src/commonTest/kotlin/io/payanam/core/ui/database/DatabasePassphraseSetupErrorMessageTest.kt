//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.database

import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every reason code the setup ViewModel can emit must turn into words.
 *
 * WHY THIS TEST IS ABOUT COVERAGE RATHER THAN ABOUT ONE CODE: the codes are a contract that crosses the
 * layer boundary, and the failure mode of adding one is invisible until a user hits it - a code with no
 * arm renders no error at all, so the screen looks like it silently refused to work. That is precisely
 * what the four setup-only codes would have done had the map not been extended here, which is why this
 * loops the WHOLE vocabulary instead of pinning one message.
 *
 * The list is derived from the setup ViewModel, not invented: five codes from [io.payanam.shared.security.
 * SharedPassphrasePolicy], `mismatch` from its own confirmation check, and the rest from the
 * configuration and reset outcomes. `rejected_by_policy` is included even though both of today's vaults
 * make it unreachable, because the ViewModel writes it out on purpose - a vault that tightened its policy
 * must not be able to make this screen render silence.
 *
 * It runs in commonTest because the map is plain string selection over a generated table: no Android API,
 * no Compose runtime, no Robolectric, and the same test runs on the JVM desktop.
 */
class DatabasePassphraseSetupErrorMessageTest {
    private val strings = UiStrings.of(UiLanguage.ENGLISH)

    /**
     * Every code [io.payanam.presentation.database.DatabasePassphraseSetupViewModel] can put in
     * `errorReasonCode`, for the setup screen's own resolver.
     */
    private val setupCodes =
        listOf(
            // SharedPassphrasePolicy.
            "min_length",
            "missing_uppercase",
            "missing_lowercase",
            "missing_digit",
            "missing_symbol",
            // The screen's own confirmation check.
            "mismatch",
            // Migration, persist and reset outcomes.
            "migration_incompatible",
            "migration_failed",
            "persist_failed",
            "reset_failed",
            // Unreachable with today's two vaults, reachable by contract.
            "rejected_by_policy",
        )

    @Test
    fun everyCodeTheSetupViewModelCanEmitResolvesToWords() {
        for (code in setupCodes) {
            val message = strings.passphraseSetupErrorMessage(code)
            assertNotNull(message, "Setup code '$code' renders no error at all")
            assertTrue(message.isNotBlank(), "Setup code '$code' resolves to a blank message")
        }
    }

    @Test
    fun aCodeWithNoErrorStillRendersNothing() {
        // The distinction this whole screen depends on: no error is not the same as an unknown error.
        // Folding the two would make every successful configuration flash a failure message.
        assertNull(strings.passphraseSetupErrorMessage(null))
    }

    @Test
    fun aCodeNobodyDefinesFallsBackToTheGenericSentence() {
        // A future code must never render as silence, and must not need copy written for it in this
        // module either - the fallback is the honest answer when the words do not exist.
        assertEquals(strings.dbPassphraseErrorGeneric, strings.passphraseSetupErrorMessage("a_code_from_the_future"))
    }

    @Test
    fun theSharedMapStaysSilentForAnUnknownCode() {
        // The CHANGE screen depends on the opposite contract: it returns null so the screen draws no error
        // at all. The setup fallback above must not have quietly changed that for every other caller.
        assertNull(strings.passphraseErrorMessage("a_code_from_the_future"))
        assertNull(strings.passphraseErrorMessage(null))
    }

    @Test
    fun everyCodeWithItsOwnArmResolvesToItsOwnMessage() {
        // Guards against an arm being added that silently reuses another code's copy, which is the
        // cheapest way for two unrelated failures to start looking identical in the UI.
        val distinct = setupCodes.mapNotNull { strings.passphraseErrorMessage(it) }.toSet()
        assertEquals(
            setupCodes.size - 1,
            distinct.size,
            "Expected each mapped setup code to have its own copy; reused messages: $distinct",
        )
    }
}
