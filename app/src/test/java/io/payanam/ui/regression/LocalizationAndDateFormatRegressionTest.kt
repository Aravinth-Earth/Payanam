//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.regression

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
class LocalizationAndDateFormatRegressionTest {

    @Test
    fun tasks_filter_chip_uses_string_resource_format() {
        val source = readSource("app/src/main/kotlin/io/payanam/ui/screens/MinimalModeComponents.kt")
        assertTrue(source.contains("R.string.loc_task_filter_with_count"))
        assertFalse(source.contains("taskFilterLabel(filter)} (${ '$' }count)"))
    }

    @Test
    /**
     * Bulk map dialog uses dimension preferences not raw display name.
     */
    fun bulk_map_dialog_uses_dimension_preferences_not_raw_display_name() {
        val source = readSource("app/src/main/kotlin/io/payanam/feature/settings/ui/SettingsDialogs.kt")
        assertFalse(source.contains("settings_bulk_map_dimension_selected,\n                                    dimension.displayName"))
        assertFalse(source.contains("} else {\n                                dimension.displayName"))
        assertTrue(source.contains("dimensionPreferences"))
    }

    @Test
    /**
     * Date time formatting avoids hardcoded patterns in primary screens.
     */
    fun date_time_formatting_avoids_hardcoded_patterns_in_primary_screens() {
        val notes = readSource("app/src/main/kotlin/io/payanam/ui/screens/NotesScreen.kt")
        val lenses = readSource("app/src/main/kotlin/io/payanam/ui/screens/LensesScreen.kt")
        val time = readSource("app/src/main/kotlin/io/payanam/ui/screens/TimeScreen.kt")
        val dayViewModel = readSource("app/src/main/kotlin/io/payanam/ui/viewmodel/DayViewModel.kt")
        assertFalse(notes.contains("DateTimeFormatter.ofPattern("))
        assertFalse(lenses.contains("DateTimeFormatter.ofPattern("))
        assertFalse(time.contains("DateTimeFormatter.ofPattern("))
        assertFalse(dayViewModel.contains("DateTimeFormatter.ofPattern("))
    }

    private fun readSource(relativePath: String): String {
        val file = resolveRepoRoot().resolve(relativePath)
        return file.toFile().readText()
    }

    private fun resolveRepoRoot(): Path {
        var current: Path? = Path.of("").toAbsolutePath()
        while (current != null) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) {
                return current
            }
            current = current.parent
        }
        error("Could not resolve repository root from test working directory")
    }
}
