//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

package io.payanam.presentation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four one-shot outcomes the shared Settings screen renders, asserted on what a screen can actually
 * read off them.
 *
 * WHY THIS EXISTS: the module's own note on this file says the four were split out "because they are
 * already pure", and nothing tested them - [ImportResult.Success] was the single least covered class in
 * the module at zero of forty instructions, because the app module constructs them on a path this module
 * never runs. What a screen depends on is the PAYLOAD: a successful import's three counts and whether it
 * asks for a restart, a success that reports zero imported rows, and the fact that an [Error] carries a
 * message rather than a flag. Those are the fields that would render as an empty summary or a blank error
 * line if a name changed, and nothing else in the build would notice.
 */
class SettingsSharedResultsTest {
    // ── ImportResult ──────────────────────────────────────────────────────────

    @Test
    fun a_successful_import_reports_each_count_and_asks_for_no_restart_by_default() {
        val result = ImportResult.Success(tasksImported = 12, timeEntriesImported = 30, notesImported = 4)

        assertEquals(12, result.tasksImported)
        assertEquals(30, result.timeEntriesImported)
        assertEquals(4, result.notesImported)
        assertFalse("an import that changed nothing in place needs no restart", result.requiresAppRestart)
    }

    @Test
    fun an_import_that_changed_the_schema_asks_for_a_restart() {
        val result =
            ImportResult.Success(
                tasksImported = 0,
                timeEntriesImported = 0,
                notesImported = 0,
                requiresAppRestart = true,
            )

        assertTrue(result.requiresAppRestart)
    }

    @Test
    fun a_successful_import_of_nothing_is_distinguishable_from_a_failure() {
        // Zero counts with no restart is a legitimate "there was nothing to import" answer, and it must not
        // read as an error. The two are separate types precisely so a screen can tell them apart.
        val empty = ImportResult.Success(tasksImported = 0, timeEntriesImported = 0, notesImported = 0)
        val failed: ImportResult = ImportResult.Error("disk full")

        assertTrue(empty is ImportResult.Success)
        assertTrue(failed is ImportResult.Error)
        assertFalse(empty is ImportResult.Error)
    }

    @Test
    fun an_import_error_carries_a_displayable_message_and_no_counts() {
        val error = ImportResult.Error("Could not read backup: file is corrupt")

        assertEquals("Could not read backup: file is corrupt", error.message)
    }

    @Test
    fun two_imports_with_the_same_counts_are_equal_and_differing_ones_are_not() {
        val first = ImportResult.Success(3, 4, 5)
        val same = ImportResult.Success(3, 4, 5)
        val different = ImportResult.Success(3, 4, 6)

        assertEquals(first, same)
        assertFalse(first == different)
    }

    // ── ExportResult ──────────────────────────────────────────────────────────

    @Test
    fun a_successful_export_names_the_file_it_wrote() {
        val result = ExportResult.Success("payanam-2026-10-01.db")

        assertEquals("payanam-2026-10-01.db", result.fileName)
    }

    @Test
    fun an_export_error_names_the_failure_instead_of_a_file() {
        val error: ExportResult = ExportResult.Error("No space left on device")

        assertTrue(error is ExportResult.Error)
        assertEquals("No space left on device", (error as ExportResult.Error).message)
    }

    // ── UhabitsImportResult ───────────────────────────────────────────────────

    @Test
    fun a_uhabits_import_reports_both_upsert_counts() {
        val result = UhabitsImportResult.Success(habitsUpserted = 7, repetitionsUpserted = 421)

        assertEquals(7, result.habitsUpserted)
        assertEquals(421, result.repetitionsUpserted)
    }

    @Test
    fun a_uhabits_import_that_matched_nothing_still_succeeds() {
        val result = UhabitsImportResult.Success(habitsUpserted = 0, repetitionsUpserted = 0)

        assertEquals(0, result.habitsUpserted)
        assertEquals(0, result.repetitionsUpserted)
    }

    @Test
    fun a_uhabits_import_error_carries_its_message() {
        val error: UhabitsImportResult = UhabitsImportResult.Error("uHabits database is not a database")

        assertEquals("uHabits database is not a database", (error as UhabitsImportResult.Error).message)
    }

    // ── BulkHabitMappingResult ────────────────────────────────────────────────

    @Test
    fun a_bulk_mapping_reports_how_many_were_tagged_and_into_which_dimension() {
        val result = BulkHabitMappingResult.Success(mappedCount = 9, dimensionId = "dim_learning_growth")

        assertEquals(9, result.mappedCount)
        assertEquals("dim_learning_growth", result.dimensionId)
    }

    @Test
    fun a_bulk_mapping_that_tagged_nothing_names_the_dimension_it_targeted() {
        // Zero mapped is still a real answer, and the target dimension is what a screen shows next to it -
        // so a mapping that changed nothing must not lose the dimension it aimed at.
        val result = BulkHabitMappingResult.Success(mappedCount = 0, dimensionId = "dim_health_physical")

        assertEquals(0, result.mappedCount)
        assertEquals("dim_health_physical", result.dimensionId)
    }

    @Test
    fun a_bulk_mapping_error_carries_its_message_and_no_dimension() {
        val error: BulkHabitMappingResult = BulkHabitMappingResult.Error("Dimension is archived")

        assertEquals("Dimension is archived", (error as BulkHabitMappingResult.Error).message)
    }

    @Test
    fun a_success_and_an_error_are_never_interchangeable_within_a_family() {
        // Each family is a sealed pair, so the screen's `when` is exhaustive; this asserts the two branches
        // are genuinely distinct values rather than one class aliasing the other.
        assertTrue(ExportResult.Success("a.db") is ExportResult)
        assertTrue(ExportResult.Error("x") is ExportResult)
        assertFalse(ExportResult.Success("a.db") == ExportResult.Error("x"))
    }
}
