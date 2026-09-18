//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOnlySqlGuardTest {
    @Test
    fun `accepts a plain select and appends a limit when missing`() {
        val result = ReadOnlySqlGuard.sanitize("SELECT count(*) FROM tasks", 500)
        assertTrue(result is GuardResult.Ok)
        assertEquals("SELECT count(*) FROM tasks LIMIT 500", (result as GuardResult.Ok).sql)
    }

    @Test
    fun `keeps an existing limit untouched`() {
        val result = ReadOnlySqlGuard.sanitize("SELECT * FROM tasks LIMIT 10", 500)
        assertEquals("SELECT * FROM tasks LIMIT 10", (result as GuardResult.Ok).sql)
    }

    @Test
    fun `strips a single trailing semicolon`() {
        val result = ReadOnlySqlGuard.sanitize("SELECT 1;", 500)
        assertTrue(result is GuardResult.Ok)
    }

    @Test
    fun `rejects multi-statement payloads`() {
        val result = ReadOnlySqlGuard.sanitize("SELECT 1; DROP TABLE tasks", 500)
        assertTrue(result is GuardResult.Rejected)
    }

    @Test
    fun `rejects comments`() {
        assertTrue(ReadOnlySqlGuard.sanitize("SELECT 1 -- comment", 500) is GuardResult.Rejected)
        assertTrue(ReadOnlySqlGuard.sanitize("SELECT /* x */ 1", 500) is GuardResult.Rejected)
    }

    @Test
    fun `rejects write statements`() {
        listOf(
            "DELETE FROM tasks",
            "UPDATE tasks SET status='done'",
            "INSERT INTO tasks VALUES (1)",
            "DROP TABLE tasks",
            "ALTER TABLE tasks ADD COLUMN x",
            "PRAGMA key",
        ).forEach { sql ->
            assertTrue("expected rejection for: $sql", ReadOnlySqlGuard.sanitize(sql, 500) is GuardResult.Rejected)
        }
    }

    @Test
    fun `literal contents do not trip the keyword scan`() {
        val result = ReadOnlySqlGuard.sanitize("SELECT * FROM tasks WHERE note = 'update'", 500)
        assertTrue(result is GuardResult.Ok)
    }

    @Test
    fun `real keywords outside literals are still rejected`() {
        assertTrue(
            ReadOnlySqlGuard.sanitize("WITH x AS (SELECT 1) DELETE FROM tasks", 500) is GuardResult.Rejected,
        )
    }

    @Test
    fun `accepts with-statements`() {
        val result = ReadOnlySqlGuard.sanitize("WITH t AS (SELECT 1 AS a) SELECT a FROM t", 500)
        assertTrue(result is GuardResult.Ok)
        assertTrue((result as GuardResult.Ok).sql.endsWith("LIMIT 500"))
    }

    @Test
    fun `refuses the settings table so no stored secret is reachable`() {
        assertTrue(ReadOnlySqlGuard.sanitize("SELECT value FROM app_settings", 500) is GuardResult.Rejected)
        assertTrue(
            ReadOnlySqlGuard.sanitize(
                "SELECT value FROM \"app_settings\" WHERE key = 'assistant.api_key'",
                500,
            ) is GuardResult.Rejected,
        )
        assertTrue(ReadOnlySqlGuard.sanitize("SELECT * FROM [app_settings]", 500) is GuardResult.Rejected)
    }

    @Test
    fun `rejects statements over the length cap`() {
        val longSelect = "SELECT * FROM tasks WHERE title LIKE '%" + "x".repeat(4100) + "%'"
        assertTrue(ReadOnlySqlGuard.sanitize(longSelect, 500) is GuardResult.Rejected)
    }

    @Test
    fun `accepts literals containing keywords and punctuation`() {
        assertTrue(
            ReadOnlySqlGuard.sanitize("SELECT * FROM tasks WHERE title LIKE '%delete%'", 500) is GuardResult.Ok,
        )
        assertTrue(
            ReadOnlySqlGuard.sanitize("SELECT * FROM notes WHERE text LIKE '%;%'", 500) is GuardResult.Ok,
        )
        assertTrue(
            ReadOnlySqlGuard.sanitize("SELECT * FROM notes WHERE text = 'a -- b'", 500) is GuardResult.Ok,
        )
        assertTrue(
            ReadOnlySqlGuard.sanitize("SELECT * FROM notes WHERE text = 'has /* marker'", 500) is GuardResult.Ok,
        )
    }

    @Test
    fun `appends limit when the word limit only appears inside a literal`() {
        val result = ReadOnlySqlGuard.sanitize("SELECT * FROM tasks WHERE title LIKE '%limit%'", 500)
        assertTrue(result is GuardResult.Ok)
        assertTrue((result as GuardResult.Ok).sql.endsWith("LIMIT 500"))
    }

    @Test
    fun `still rejects real multi-statement next to a literal`() {
        assertTrue(ReadOnlySqlGuard.sanitize("SELECT ';' ; DROP TABLE tasks", 500) is GuardResult.Rejected)
    }

    @Test
    fun `rejects unterminated string literals`() {
        assertTrue(ReadOnlySqlGuard.sanitize("SELECT * FROM tasks WHERE title = 'broken", 500) is GuardResult.Rejected)
    }

    @Test
    fun `strip helper blanks literal contents and keeps delimiters`() {
        val stripped = ReadOnlySqlGuard.stripStringLiterals("SELECT 'ab''cd', \"col\" FROM t")
        assertTrue(stripped.startsWith("SELECT '"))
        assertFalse(stripped.contains("ab"))
        assertFalse(stripped.contains("cd"))
        assertTrue(stripped.contains("\"col\"") || stripped.contains('"'))
    }

    @Test
    fun `rejects blank statements`() {
        assertTrue(ReadOnlySqlGuard.sanitize("   ", 500) is GuardResult.Rejected)
    }

    @Test
    fun `rejects every forbidden keyword, not just the common ones`() {
        listOf("attach database 'x' as y", "detach database y", "vacuum", "reindex", "create table t(a)", "replace into t values(1)", "trigger x after insert on t begin select 1; end")
            .forEach { statement ->
                val sql = "SELECT 1 WHERE ${statement}"
                assertTrue(
                    "expected rejection for: $statement",
                    ReadOnlySqlGuard.sanitize(sql, 500) is GuardResult.Rejected,
                )
            }
    }

    @Test
    fun `rejects pragma and session-pragma table functions`() {
        listOf(
            "PRAGMA table_info(tasks)",
            "SELECT * FROM pragma_table_info('tasks')",
            "SELECT * FROM pragma_database_list",
        ).forEach { statement ->
            assertTrue(
                "expected rejection for: $statement",
                ReadOnlySqlGuard.sanitize(statement, 500) is GuardResult.Rejected,
            )
        }
    }

    @Test
    fun `rejects sqlite schema and raw-page helpers`() {
        listOf(
            "SELECT * FROM sqlite_master",
            "SELECT name FROM sqlite_schema",
            "SELECT * FROM sqlite_dbpage",
            "SELECT * FROM sqlite_dbdata",
        ).forEach { statement ->
            assertTrue(
                "expected rejection for: $statement",
                ReadOnlySqlGuard.sanitize(statement, 500) is GuardResult.Rejected,
            )
        }
    }

    @Test
    fun `allows identifiers that merely start with a forbidden keyword`() {
        // created_at / attachments / attachment_id are ordinary columns — the keyword scan must
        // never confuse them with create/attach statements.
        val result = ReadOnlySqlGuard.sanitize(
            "SELECT created_at, attachment_id FROM tasks ORDER BY created_at",
            500,
        )
        assertTrue(result is GuardResult.Ok)
    }

    @Test
    fun `app_settings stays refused through a pragma table function`() {
        assertTrue(
            ReadOnlySqlGuard.sanitize("SELECT * FROM pragma_table_info('app_settings')", 500) is GuardResult.Rejected,
        )
    }

    @Test
    fun `quoted spellings of blocked names are rejected`() {
        // A quoted identifier resolves to the same table; the keyword scan must see through
        // the quoting instead of blanking its content.
        listOf(
            "SELECT name FROM \"sqlite_master\"",
            "SELECT * FROM \"main\".\"sqlite_master\"",
            "SELECT * FROM [sqlite_master]",
            "SELECT * FROM `sqlite_master`",
            "SELECT * FROM \"pragma_table_info\"('t')",
            "SELECT * FROM \"app_settings\"",
        ).forEach { statement ->
            assertTrue(
                "expected rejection for: $statement",
                ReadOnlySqlGuard.sanitize(statement, 500) is GuardResult.Rejected,
            )
        }
    }

    @Test
    fun `data values that merely spell a blocked name stay queryable`() {
        assertTrue(
            ReadOnlySqlGuard.sanitize("SELECT * FROM tasks WHERE title = 'sqlite_master'", 500) is GuardResult.Ok,
        )
    }
}
