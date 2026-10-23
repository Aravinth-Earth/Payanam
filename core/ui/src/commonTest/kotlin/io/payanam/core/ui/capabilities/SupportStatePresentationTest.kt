//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.capabilities

import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.shared.platform.SupportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * One rendering result per capability state - the state table in the capability-contract proposal,
 * asserted so a state added later cannot ship unrendered. [presentation]'s `when` has no `else`; this test
 * is its companion (the proposal requires both halves).
 */
class SupportStatePresentationTest {
    private val strings = UiStrings.of(UiLanguage.ENGLISH)

    @Test
    fun `SUPPORTED is enabled`() {
        assertEquals(SupportStatePresentation.Enabled, SupportState.SUPPORTED.presentation(strings))
    }

    @Test
    fun `NOT_APPLICABLE is absent, not disabled`() {
        assertEquals(SupportStatePresentation.Absent, SupportState.NOT_APPLICABLE.presentation(strings))
    }

    @Test
    fun `IN_PROCESS_ONLY is enabled with the qualifier`() {
        val presentation =
            assertIs<SupportStatePresentation.EnabledWithQualifier>(
                SupportState.IN_PROCESS_ONLY.presentation(strings),
            )
        assertEquals(strings.locCapabilityInProcessQualifier, presentation.qualifier)
    }

    @Test
    fun `NOT_SURFACED and DECLINES render identically - disabled with the same reason`() {
        val notSurfaced =
            assertIs<SupportStatePresentation.DisabledWithReason>(
                SupportState.NOT_SURFACED.presentation(strings),
            )
        val declines =
            assertIs<SupportStatePresentation.DisabledWithReason>(
                SupportState.DECLINES.presentation(strings),
            )
        assertEquals(strings.locCapabilityUnavailableReason, notSurfaced.reason)
        // Identical by design: the distinction between the two states is for logs and tests, never copy.
        assertEquals(notSurfaced, declines)
    }

    @Test
    fun `every state has a defined rendering - a new state must add one here too`() {
        assertEquals(5, SupportState.entries.size)
        SupportState.entries.forEach { state ->
            // Rendering any state must not throw; the tests above pin WHICH shape each one produces.
            state.presentation(strings)
        }
        // The four shapes are all reachable today.
        val rendered = SupportState.entries.map { it.presentation(strings) }
        assertTrue(rendered.contains(SupportStatePresentation.Enabled))
        assertTrue(rendered.contains(SupportStatePresentation.Absent))
        assertTrue(rendered.any { it is SupportStatePresentation.EnabledWithQualifier })
        assertTrue(rendered.any { it is SupportStatePresentation.DisabledWithReason })
    }
}
