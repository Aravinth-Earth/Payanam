//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DatabasePassphraseUnlockViewModelTest.
 */
class DatabasePassphraseUnlockViewModelTest {

    @Test
    fun `classifyDatabaseOpenFailureReason maps schema and version failures to specific reasons`() {
        /** Assert equals. */
        assertEquals(
            "db_too_new",
            /** Classify database open failure reason. */
            classifyDatabaseOpenFailureReason(IllegalStateException("Database version 18 is newer than app supports (17). Please update the app.")),
        )
        /** Assert equals. */
        assertEquals(
            "db_too_old",
            /** Classify database open failure reason. */
            classifyDatabaseOpenFailureReason(IllegalStateException("Database version 14 is too old. Minimum supported schema is 16.")),
        )
        /** Assert equals. */
        assertEquals(
            "schema_invalid",
            /** Classify database open failure reason. */
            classifyDatabaseOpenFailureReason(IllegalStateException("Schema issues: tasks table missing required column dueDate")),
        )
        /** Assert equals. */
        assertEquals(
            "storage_incomplete",
            /** Classify database open failure reason. */
            classifyDatabaseOpenFailureReason(IllegalStateException("Database sidecar files found but primary DB file missing.")),
        )
    }

    @Test
    fun `classifyDatabaseOpenFailureReason falls back to open_failed for unknown errors`() {
        /** Assert equals. */
        assertEquals("open_failed", classifyDatabaseOpenFailureReason(IllegalStateException("Something else happened")))
        /** Assert equals. */
        assertEquals("open_failed", classifyDatabaseOpenFailureReason(null))
    }
}
