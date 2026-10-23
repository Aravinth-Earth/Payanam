//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.assistant

import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The consent copy has to say what actually leaves the device, in both shipped languages.
 *
 * WHY A TEST ABOUT COPY AT ALL:
 *
 * The whole value of this gate is that it is honest about the one feature that is not local. A test that
 * only asserted the dialog compiles would pass if the body were reduced to "this sends data somewhere",
 * which is the exact failure the gate exists to prevent - a user who cannot tell WHICH data, and who
 * processes it, has not been told anything they can act on.
 *
 * It reads the GENERATED [UiStrings] rather than the XML: that is the table the composable actually reads,
 * so this fails if generation ever drops a key, and it needs no resource-loading machinery in commonTest.
 *
 * The assertions are about the CLAIMS the copy makes, not about exact wording: rewording the message should
 * not break the build, but dropping a claim should.
 */
class AssistantConsentCopyTest {
    private val en = UiStrings.of(UiLanguage.ENGLISH)
    private val ta = UiStrings.of(UiLanguage.TAMIL)

    private fun consentValues(table: UiStrings): List<String> =
        listOf(
            table.assistantConsentTitle,
            table.assistantConsentLocalFirst,
            table.assistantConsentWhatLeaves,
            table.assistantConsentNotControlled,
            table.assistantConsentTurnOff,
            table.assistantConsentProceed,
            table.assistantConsentCancel,
        )

    @Test
    fun bothLanguagesCarryTheWholeMessage() {
        for (table in listOf(en, ta)) {
            for (value in consentValues(table)) {
                assertTrue(value.isNotBlank(), "A blank consent string renders as a broken dialog: '$value'")
            }
        }
        // English must not be the only place the message exists: a Tamil user gets the same gate.
        assertNotEquals(en.assistantConsentWhatLeaves, ta.assistantConsentWhatLeaves)
    }

    @Test
    fun theMessageNamesBothTheLocalDefaultAndTheOptIn() {
        // The claim the rest of the app makes, restated here so the user is not asked to take it on faith.
        assertContains(en.assistantConsentLocalFirst, "this device")
        // ...and the one exception to it.
        assertContains(en.assistantConsentTitle, "opt-in")
    }

    @Test
    fun theMessageSaysWhatLeavesAndThatTheProviderHandlesIt() {
        val whatLeaves = en.assistantConsentWhatLeaves

        // "Some data" is not consent. Both halves are named: what is sent, and who ends up holding it.
        assertContains(whatLeaves, "your question")
        assertContains(whatLeaves, "provider")
        assertContains(whatLeaves, "terms")
    }

    @Test
    fun theMessageStatesWhatTheAppDoesNotDoInTheNegative() {
        val notControlled = en.assistantConsentNotControlled

        // The privacy-relevant claim is a set of NON-actions. "We respect your privacy" is not one of them.
        for (claim in listOf("does not control", "log", "trace")) {
            assertContains(notControlled, claim)
        }
    }

    @Test
    fun theMessageSaysHowToTurnTheFeatureOff() {
        // Without this the gate is a one-way door, and a user who accepted once could not undo it.
        assertContains(en.assistantConsentTurnOff, "remove the key")
    }

    @Test
    fun bothChoicesAreLabelledSoCancellingIsPossible() {
        assertTrue(en.assistantConsentProceed.isNotBlank())
        assertTrue(en.assistantConsentCancel.isNotBlank())
    }
}
