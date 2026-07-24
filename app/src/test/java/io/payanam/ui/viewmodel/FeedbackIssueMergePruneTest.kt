//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import io.payanam.feedback.FeedbackIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackIssueMergePruneTest {

    @Test
    fun `merge keeps local issue and overlays remote fields for matching issue`() {
        val local = listOf(
            FeedbackIssue(
                number = 101,
                title = "[Bug] Local title",
                state = "open",
                createdAt = "2026-03-01",
                htmlUrl = "https://example.local/101",
                body = "## Description\n\nlocal body",
            ),
            FeedbackIssue(
                number = 100,
                title = "[Bug] Existing only",
                state = "open",
                createdAt = "2026-02-28",
                htmlUrl = "https://example.local/100",
                body = "## Description\n\nexisting only",
            ),
        )

        val remote = listOf(
            FeedbackIssue(
                number = 101,
                title = "[Bug] Remote canonical title",
                state = "closed",
                createdAt = "2026-03-01",
                htmlUrl = "https://example.remote/101",
                body = null,
                updatedAt = "2026-03-02",
                closedAt = "2026-03-02",
            ),
        )

        val merged = mergeIssues(local, remote)
        assertEquals(2, merged.size)
        assertEquals(101, merged[0].number)
        assertEquals("[Bug] Remote canonical title", merged[0].title)
        assertEquals("closed", merged[0].state)
        assertEquals("## Description\n\nlocal body", merged[0].body)
        assertEquals("2026-03-02", merged[0].closedAt)
        assertEquals(100, merged[1].number)
    }

    @Test
    fun `prune drops closed issues older than 30 days and keeps active issues`() {
        val nowMs = 1_800_000_000_000L
        val issues = listOf(
            FeedbackIssue(
                number = 11,
                title = "Old closed",
                state = "closed",
                createdAt = "2026-01-01",
                htmlUrl = "https://example/11",
                closedAt = "2026-01-01",
            ),
            FeedbackIssue(
                number = 12,
                title = "Recent closed",
                state = "closed",
                createdAt = "2026-01-01",
                htmlUrl = "https://example/12",
                closedAt = "2027-01-10",
            ),
            FeedbackIssue(
                number = 13,
                title = "Open issue",
                state = "open",
                createdAt = "2026-01-01",
                htmlUrl = "https://example/13",
            ),
        )

        val pruned = pruneClosedIssues(issues, nowMs)
        assertEquals(2, pruned.size)
        assertTrue(pruned.any { it.number == 12 })
        assertTrue(pruned.any { it.number == 13 })
        assertTrue(pruned.none { it.number == 11 })
    }
}
