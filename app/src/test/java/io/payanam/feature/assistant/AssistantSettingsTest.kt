//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings model's own rules: range clamping and the "is configured" predicate. */
class AssistantSettingsTest {

    @Test
    fun `sanitize clamps every numeric field into its supported range`() {
        val wild = AssistantSettings(maxRows = 99_999, rounds = 99, historyDepth = 0)
        val clean = wild.sanitized()
        assertEquals(AssistantDefaults.HARD_MAX_ROWS, clean.maxRows)
        assertEquals(AssistantDefaults.MAX_ROUNDS_LIMIT, clean.rounds)
        assertEquals(AssistantDefaults.MIN_HISTORY, clean.historyDepth)

        val tiny = AssistantSettings(maxRows = 1, rounds = 0, historyDepth = 999).sanitized()
        assertEquals(AssistantDefaults.MIN_ROWS, tiny.maxRows)
        assertEquals(AssistantDefaults.MIN_ROUNDS, tiny.rounds)
        assertEquals(AssistantDefaults.MAX_HISTORY, tiny.historyDepth)
    }

    @Test
    fun `sanitize caps prompt additions at the documented budget`() {
        val long = AssistantSettings(promptAdditions = "x".repeat(AssistantDefaults.MAX_PROMPT_ADDITIONS + 500))
        assertEquals(AssistantDefaults.MAX_PROMPT_ADDITIONS, long.sanitized().promptAdditions.length)
    }

    @Test
    fun `isConfigured requires both a key and a picked model`() {
        assertFalse(AssistantUiState(hasKey = false, settings = AssistantSettings(model = "m1")).isConfigured)
        assertFalse(AssistantUiState(hasKey = true, settings = AssistantSettings(model = "")).isConfigured)
        assertTrue(AssistantUiState(hasKey = true, settings = AssistantSettings(model = "m1")).isConfigured)
    }
}
