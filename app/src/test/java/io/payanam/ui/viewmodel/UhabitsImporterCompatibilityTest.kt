//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UhabitsImporterCompatibilityTest.
 */
class UhabitsImporterCompatibilityTest {
    @Test
    /**
     * Build repetitions query uses alias when value column missing.
     */
    fun build_repetitions_query_uses_alias_when_value_column_missing() {
        /** Query. */
        val query = UhabitsImporter.buildRepetitionsQuery(
            hasValueColumn = false,
            hasNotesColumn = true,
        )

        /** Assert equals. */
        assertEquals(
            "SELECT habit, timestamp, 1 AS value, notes FROM Repetitions",
            /** Query. */
            query,
        )
    }

    @Test
    /**
     * Build repetitions query uses null notes alias when notes column missing.
     */
    fun build_repetitions_query_uses_null_notes_alias_when_notes_column_missing() {
        /** Query. */
        val query = UhabitsImporter.buildRepetitionsQuery(
            hasValueColumn = true,
            hasNotesColumn = false,
        )

        /** Assert equals. */
        assertEquals(
            "SELECT habit, timestamp, value, NULL AS notes FROM Repetitions",
            /** Query. */
            query,
        )
    }
}
