//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantTextTest {
    @Test
    fun `parse splits headings bullets numbered and tables`() {
        val input = buildString {
            appendLine("# Summary")
            appendLine("- first point")
            appendLine("1. second point")
            appendLine("| day | score |")
            appendLine("|---|---|")
            appendLine("| Mon | 0.4 |")
            appendLine("plain closing line")
        }
        val blocks = AssistantText.parse(input)
        assertEquals(MdBlock.Heading("Summary"), blocks[0])
        assertEquals(MdBlock.Bullet("first point"), blocks[1])
        assertEquals(MdBlock.Numbered("1. second point"), blocks[2])
        assertEquals(MdBlock.TableRow("| day | score |"), blocks[3])
        assertEquals(MdBlock.TableRow("| Mon | 0.4 |"), blocks[4])
        assertEquals(MdBlock.Plain("plain closing line"), blocks[5])
    }

    @Test
    fun `parse is safe on empty and malformed input`() {
        assertTrue(AssistantText.parse("").isEmpty())
        assertTrue(AssistantText.parse("**").isNotEmpty())
        assertTrue(AssistantText.parse("|").isNotEmpty())
    }

    @Test
    fun `parse marks empty lines as blank blocks`() {
        val blocks = AssistantText.parse("first line\n\nsecond line")
        assertEquals(3, blocks.size)
        assertEquals(MdBlock.Plain("first line"), blocks[0])
        assertEquals(MdBlock.Blank, blocks[1])
        assertEquals(MdBlock.Plain("second line"), blocks[2])
    }

    @Test
    fun `segments splits bold runs`() {
        val segments = AssistantText.segments("you did **92 of 507** this week")
        assertEquals(3, segments.size)
        assertEquals(TextSegment("you did ", bold = false), segments[0])
        assertEquals(TextSegment("92 of 507", bold = true), segments[1])
        assertEquals(TextSegment(" this week", bold = false), segments[2])
    }

    @Test
    fun `segments keeps unpaired markers as literal text`() {
        val segments = AssistantText.segments("a ** b")
        assertEquals(1, segments.size)
        assertEquals("a ** b", segments[0].text)
        assertEquals(false, segments[0].bold)
    }

    @Test
    fun `segments handles back-to-back bold runs`() {
        val segments = AssistantText.segments("**a****b**")
        assertEquals(2, segments.size)
        assertEquals(TextSegment("a", bold = true), segments[0])
        assertEquals(TextSegment("b", bold = true), segments[1])
    }
}
