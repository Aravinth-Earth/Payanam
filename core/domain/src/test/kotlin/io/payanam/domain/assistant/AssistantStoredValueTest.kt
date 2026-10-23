//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored-value rule, in the category this parity work keeps finding: a stored value that
 * cannot be read must be REPORTED, not silently replaced and written back.
 */
class AssistantStoredValueTest {
    @Test
    fun `a missing row takes its default and is NOT a finding`() {
        val field = AssistantStoredValue.int("assistant.max_rows", stored = null, fallback = 500)
        assertEquals(500, field.value)
        assertTrue("a first run is benign and must not be reported", field.usable)
    }

    @Test
    fun `a parseable stored value wins over the default`() {
        val field = AssistantStoredValue.int("assistant.rounds", stored = "3", fallback = 8)
        assertEquals(3, field.value)
        assertTrue(field.usable)
    }

    @Test
    fun `an unparseable stored row falls back for this load AND is reported unusable`() {
        // The whole point: the fallback is used for the load, and `usable = false` is the signal
        // that the caller must report it and leave the stored text alone.
        val field = AssistantStoredValue.int("assistant.max_rows", stored = "not-a-number", fallback = 500)
        assertEquals(500, field.value)
        assertFalse("an unreadable stored row must never pass as usable", field.usable)
        assertEquals("the report must name the row", "assistant.max_rows", field.key)
    }

    @Test
    fun `booleans are parsed strictly so a loose spelling is reported not accepted`() {
        assertEquals(true, AssistantStoredValue.boolean("k", "true", fallback = false).value)
        assertTrue(AssistantStoredValue.boolean("assistant.chips", "true", fallback = false).usable)
        // "TRUE" and "1" are not what this code wrote, so they are unreadable, not true.
        val loose = AssistantStoredValue.boolean("assistant.chips", stored = "TRUE", fallback = true)
        assertEquals(true, loose.value)
        assertFalse("a spelling the app never writes must be reported", loose.usable)
        val numeric = AssistantStoredValue.boolean("assistant.chips", stored = "1", fallback = true)
        assertFalse(numeric.usable)
    }

    @Test
    fun `length preset reports an unknown stored name instead of quietly defaulting`() {
        assertEquals(AssistantLength.SHORT, AssistantStoredValue.length("assistant.length", "SHORT").value)
        assertTrue(AssistantStoredValue.length("assistant.length", "SHORT").usable)
        val unknown = AssistantStoredValue.length("assistant.length", "VERBOSE")
        assertEquals(AssistantLength.BALANCED, unknown.value)
        assertFalse(unknown.usable)
    }
}
