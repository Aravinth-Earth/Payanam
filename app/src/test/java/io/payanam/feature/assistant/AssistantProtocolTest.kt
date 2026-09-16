//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantProtocolTest {
    @Test
    fun `empty sql string is treated as no query`() {
        assertTrue(AssistantProtocol.extractSql("{\"sql\": \"\"}") == null)
    }

    @Test
    fun `extracts sql from a well-formed reply`() {
        assertEquals("SELECT 1", AssistantProtocol.extractSql("{\"sql\": \"SELECT 1\"}"))
    }

    @Test
    fun `extracts sql when the reply wraps the json in prose`() {
        val reply = "Let me check that.\n{\"sql\": \"SELECT count(*) FROM tasks\"}\n"
        assertEquals("SELECT count(*) FROM tasks", AssistantProtocol.extractSql(reply))
    }

    @Test
    fun `unescapes quotes inside the sql value`() {
        val reply = "{\"sql\": \"SELECT * FROM tasks WHERE title = \\\"Water\\\"\"}"
        assertEquals("SELECT * FROM tasks WHERE title = \"Water\"", AssistantProtocol.extractSql(reply))
    }

    @Test
    fun `unescapes newlines inside the sql value`() {
        val reply = "{\"sql\": \"SELECT 1\\nFROM tasks\"}"
        assertEquals("SELECT 1\nFROM tasks", AssistantProtocol.extractSql(reply))
    }

    @Test
    fun `returns null for a final answer without sql`() {
        assertNull(AssistantProtocol.extractSql("You completed 92 tasks this week."))
        assertFalse(AssistantProtocol.mentionsSqlKey("You completed 92 tasks this week."))
    }

    @Test
    fun `detects truncated sql json`() {
        val truncated = "{\"sql\": \"SELECT 1"
        assertTrue(AssistantProtocol.mentionsSqlKey(truncated))
        assertNull(AssistantProtocol.extractSql(truncated))
    }

    @Test
    fun `empty replies are flagged`() {
        assertTrue(AssistantProtocol.isEmptyReply("   "))
        assertFalse(AssistantProtocol.isEmptyReply("answer"))
    }

    @Test
    fun `extracts first valid sql from reply with multiple json objects`() {
        val reply = "thinking {\"sql\": null} ... {\"sql\": \"SELECT 1\"}"
        assertEquals("SELECT 1", AssistantProtocol.extractSql(reply))
    }

    @Test
    fun `handles unicode escapes in sql value`() {
        val reply = "{\"sql\": \"SELECT * FROM t WHERE name = \\u0041\"}"
        assertEquals("SELECT * FROM t WHERE name = A", AssistantProtocol.extractSql(reply))
    }

    @Test
    fun `returns null for non-string sql value`() {
        val reply = "{\"sql\": 123}"
        assertNull(AssistantProtocol.extractSql(reply))
    }
}
