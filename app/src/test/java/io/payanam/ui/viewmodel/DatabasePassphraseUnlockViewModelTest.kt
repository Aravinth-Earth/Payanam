//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class DatabasePassphraseUnlockViewModelTest {

    @Test
    fun `classifyDatabaseOpenFailureReason maps schema and version failures to specific reasons`() {
        assertEquals(
            "db_too_new",
            classifyDatabaseOpenFailureReason(IllegalStateException("Database version 18 is newer than app supports (17). Please update the app.")),
        )
        assertEquals(
            "db_too_old",
            classifyDatabaseOpenFailureReason(IllegalStateException("Database version 14 is too old. Minimum supported schema is 16.")),
        )
        assertEquals(
            "schema_invalid",
            classifyDatabaseOpenFailureReason(IllegalStateException("Schema issues: tasks table missing required column dueDate")),
        )
        assertEquals(
            "storage_incomplete",
            classifyDatabaseOpenFailureReason(IllegalStateException("Database sidecar files found but primary DB file missing.")),
        )
    }

    @Test
    fun `classifyDatabaseOpenFailureReason falls back to open_failed for unknown errors`() {
        assertEquals("open_failed", classifyDatabaseOpenFailureReason(IllegalStateException("Something else happened")))
        assertEquals("open_failed", classifyDatabaseOpenFailureReason(null))
    }
}
