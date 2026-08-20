//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.regression

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * LocalizationAndDateFormatRegressionTest.
 */
class LocalizationAndDateFormatRegressionTest {

    @Test
    /**
     * Tasks filter chip uses string resource format.
     */
    fun tasks_filter_chip_uses_string_resource_format() {
        /** Source. */
        val source = readSource("app/src/main/kotlin/io/payanam/ui/screens/MinimalModeComponents.kt")
        /** Assert true. */
        assertTrue(source.contains("R.string.loc_task_filter_with_count"))
        /** Assert false. */
        assertFalse(source.contains("taskFilterLabel(filter)} (${ '$' }count)"))
    }

    @Test
    /**
     * Bulk map dialog uses dimension preferences not raw display name.
     */
    fun bulk_map_dialog_uses_dimension_preferences_not_raw_display_name() {
        /** Source. */
        val source = readSource("app/src/main/kotlin/io/payanam/feature/settings/ui/SettingsDialogs.kt")
        /** Assert false. */
        assertFalse(source.contains("settings_bulk_map_dimension_selected,\n                                    dimension.displayName"))
        /** Assert false. */
        assertFalse(source.contains("} else {\n                                dimension.displayName"))
        /** Assert true. */
        assertTrue(source.contains("dimensionPreferences"))
    }

    @Test
    /**
     * Date time formatting avoids hardcoded patterns in primary screens.
     */
    fun date_time_formatting_avoids_hardcoded_patterns_in_primary_screens() {
        /** Notes. */
        val notes = readSource("app/src/main/kotlin/io/payanam/ui/screens/NotesScreen.kt")
        /** Lenses. */
        val lenses = readSource("app/src/main/kotlin/io/payanam/ui/screens/LensesScreen.kt")
        /** Time. */
        val time = readSource("app/src/main/kotlin/io/payanam/ui/screens/TimeScreen.kt")
        /** Day view model. */
        val dayViewModel = readSource("app/src/main/kotlin/io/payanam/ui/viewmodel/DayViewModel.kt")

        /** Assert false. */
        assertFalse(notes.contains("DateTimeFormatter.ofPattern("))
        /** Assert false. */
        assertFalse(lenses.contains("DateTimeFormatter.ofPattern("))
        /** Assert false. */
        assertFalse(time.contains("DateTimeFormatter.ofPattern("))
        /** Assert false. */
        assertFalse(dayViewModel.contains("DateTimeFormatter.ofPattern("))
    }

    private fun readSource(relativePath: String): String {
        /** File. */
        val file = resolveRepoRoot().resolve(relativePath)
        return file.toFile().readText()
    }

    private fun resolveRepoRoot(): Path {
        /** Current. */
        var current: Path? = Path.of("").toAbsolutePath()
        /** While. */
        while (current != null) {
            /** If. */
            if (Files.exists(current.resolve("settings.gradle.kts"))) {
                return current
            }
            current = current.parent
        }
        /** Error. */
        error("Could not resolve repository root from test working directory")
    }
}
