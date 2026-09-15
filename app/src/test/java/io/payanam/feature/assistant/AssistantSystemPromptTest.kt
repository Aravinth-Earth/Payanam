//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantSystemPromptTest {
    private val schema = "SCHEMA_TEXT"

    @Test
    fun `default prompt contains base rules today and schema in order`() {
        val prompt = AssistantSystemPrompt.build(AssistantSettings(), schema, "2026-09-15")
        assertTrue(prompt.contains(AssistantSystemPrompt.BASE_INSTRUCTIONS))
        assertTrue(prompt.contains("TODAY: 2026-09-15"))
        assertTrue(prompt.contains("DATABASE SCHEMA (SQLite):\n$schema"))
        assertTrue(
            prompt.indexOf(AssistantSystemPrompt.BASE_INSTRUCTIONS) < prompt.indexOf("TODAY: 2026-09-15"),
        )
        assertTrue(prompt.indexOf("TODAY: 2026-09-15") < prompt.indexOf(schema))
        assertFalse(prompt.contains("USER ADDITIONS"))
        assertFalse(prompt.contains("ABOUT THE USER"))
    }

    @Test
    fun `length directive carries the numeric target per preset`() {
        val short = AssistantSystemPrompt.build(AssistantSettings(length = AssistantLength.SHORT), schema, "2026-09-15")
        val balanced = AssistantSystemPrompt.build(AssistantSettings(length = AssistantLength.BALANCED), schema, "2026-09-15")
        val elaborated = AssistantSystemPrompt.build(AssistantSettings(length = AssistantLength.ELABORATED), schema, "2026-09-15")
        assertTrue(short.contains("about 5 lines"))
        assertTrue(balanced.contains("about 20 lines"))
        assertTrue(elaborated.contains("about 50 lines"))
    }

    @Test
    fun `show method directive is optional`() {
        val withMethod = AssistantSystemPrompt.build(AssistantSettings(showMethod = true), schema, "2026-09-15")
        val withoutMethod = AssistantSystemPrompt.build(AssistantSettings(showMethod = false), schema, "2026-09-15")
        assertTrue(withMethod.contains(AssistantSystemPrompt.SHOW_METHOD_DIRECTIVE))
        assertFalse(withoutMethod.contains(AssistantSystemPrompt.SHOW_METHOD_DIRECTIVE))
    }

    @Test
    fun `user additions are appended after the base and schema`() {
        val settings = AssistantSettings(promptAdditions = "Be extra blunt with me.")
        val prompt = AssistantSystemPrompt.build(settings, schema, "2026-09-15")
        assertTrue(prompt.contains("USER ADDITIONS"))
        assertTrue(prompt.contains("Be extra blunt with me."))
        assertTrue(prompt.indexOf(schema) < prompt.indexOf("Be extra blunt with me."))
    }

    @Test
    fun `about me renders as a labelled list never comma joined`() {
        val settings = AssistantSettings(
            aboutMe = AssistantAboutMe(goals = "ship Payanam AI", wants = "deep work blocks"),
        )
        val prompt = AssistantSystemPrompt.build(settings, schema, "2026-09-15")
        assertTrue(prompt.contains("ABOUT THE USER"))
        assertTrue(prompt.contains("- Goals: ship Payanam AI"))
        assertTrue(prompt.contains("- Wants: deep work blocks"))
        assertFalse(prompt.contains("ship Payanam AI, deep work blocks"))
        assertFalse(prompt.contains("- Needs:"))
    }

    @Test
    fun `about me empty renders nothing`() {
        val settings = AssistantSettings(aboutMe = AssistantAboutMe(goals = "   "))
        val prompt = AssistantSystemPrompt.build(settings, schema, "2026-09-15")
        assertFalse(prompt.contains("ABOUT THE USER"))
    }

    @Test
    fun `base instructions pin english and read-only rules`() {
        val base = AssistantSystemPrompt.BASE_INSTRUCTIONS
        assertTrue(base.contains("ONLY read"))
        assertTrue(base.contains("Answer ONLY in English"))
        assertTrue(base.contains("MIRROR RULE"))
        assertTrue(base.contains("EPISTEMICS RULE"))
        assertTrue(base.contains("never from tasks.status"))
    }

    @Test
    fun `length preset parsing falls back to balanced`() {
        assertEquals(AssistantLength.BALANCED, AssistantLength.fromName(null))
        assertEquals(AssistantLength.BALANCED, AssistantLength.fromName("BOGUS"))
        assertEquals(AssistantLength.SHORT, AssistantLength.fromName("SHORT"))
    }
}
