//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The allowlist behind the `enumLiteral` log field, asserted as the boundary it is: the keys and values
 * the e2e sessions verify must pass, and everything that can carry user text must not - including a
 * value a caller invented for an admitted key.
 */
class NonContentPreferenceValuesTest {
    private fun literal(
        key: String,
        value: String,
    ): String? = NonContentPreferenceValues.literalFor(key, value)

    // ---------------------------------------------------------------- the rows the sessions assert

    @Test
    fun `the preference values a session asserts are admitted`() {
        // theme and landing are session 05; the preset and a chart toggle are session 06.
        assertEquals("dark", literal("theme_mode", "dark"))
        assertEquals("notes", literal("launch_destination_route", "notes"))
        assertEquals("simple_journal", literal("focus_mode_active_preset", "simple_journal"))
        assertEquals("true", literal("chart_journal_module_enabled", "true"))
    }

    @Test
    fun `the boolean rows the writer stringifies are admitted`() {
        assertEquals("false", literal("tab_visible_tasks", "false"))
        assertEquals("true", literal("auto_track_dimension_health", "true"))
        assertEquals("false", literal("auto_backup_enabled", "false"))
        assertEquals("true", literal("chart_time_module_enabled", "true"))
    }

    @Test
    fun `the numeric rows the writer clamps are admitted`() {
        assertEquals("60.00", literal("time_hour_height_dp", "60.00"))
        assertEquals("5", literal("day_boundary_hour", "5"))
        assertEquals("999", literal("backup_rotation_count", "999"))
    }

    @Test
    fun `the enum rows whose domain is declared by an enum are admitted`() {
        assertEquals("24h", literal("time_format", "24h"))
        assertEquals("ta", literal("app_language", "ta"))
        assertEquals("serif", literal("font_family", "serif"))
        assertEquals("6h", literal("auto_backup_interval", "6h"))
        assertEquals("not_active", literal("task_filter_option", "not_active"))
        assertEquals("active", literal("launch_destination_task_filter", "active"))
    }

    // ---------------------------------------------------------------- the boundary

    @Test
    fun `an admitted key carrying a value outside its domain is not logged`() {
        // The table alone would trust every caller of setThemeMode(String) / setLaunchDestination(String).
        // The domain check is what stops an invented value from reaching the log through an admitted key.
        assertNull(literal("theme_mode", "my favourite theme"))
        assertNull(literal("launch_destination_route", "wherever the user last was"))
        assertNull(literal("focus_mode_active_preset", "custom"))
        assertNull(literal("task_filter_option", "not-a-filter"))
    }

    @Test
    fun `a row whose value can be user text is never logged`() {
        assertNull(literal("auto_backup_last_run", "2026-11-19T12:00:00"))
        assertNull(literal("dimension_label", "Morning routine"))
        assertNull(literal("log_export_path", "/home/someone/exported-logs/session.log"))
    }

    @Test
    fun `a boolean row admits only the two spellings the reader parses`() {
        assertNull(literal("tab_visible_tasks", "yes"))
        assertNull(literal("chart_time_module_enabled", "TRUE"))
        assertNull(literal("auto_track_dimension_health", "1"))
    }

    @Test
    fun `a numeric row admits only a plain decimal`() {
        assertNull(literal("day_boundary_hour", "five"))
        assertNull(literal("backup_rotation_count", "50 backups"))
        assertNull(literal("time_hour_height_dp", "60,00"))
    }

    @Test
    fun `the logged field name is the one the writer emits`() {
        // The verifier and the writer agree on this string; a rename here without the verifier would make
        // every pref assertion silently report "the session logged no change".
        assertEquals("enumLiteral", NonContentPreferenceValues.FIELD)
    }
}
