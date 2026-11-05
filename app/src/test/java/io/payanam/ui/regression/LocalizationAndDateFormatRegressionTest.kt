//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.regression

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class LocalizationAndDateFormatRegressionTest {

    @Test
    fun tasks_filter_chip_uses_string_resource_format() {
        val source = readSource("app/src/main/kotlin/io/payanam/ui/screens/MinimalModeComponents.kt")
        // The component now lives in :core:ui and reads the generated copy table (`strings.…`) instead of an
        // Android resource id (`R.string.…`), because commonMain has no `R`. The property this test guards is
        // unchanged either way: the chip label comes from the copy table, never from string interpolation.
        assertTrue(
            "the filter chip label must come from the copy table",
            source.contains("R.string.loc_task_filter_with_count") ||
                source.contains("strings.locTaskFilterWithCount"),
        )
        assertFalse(source.contains("taskFilterLabel(filter)} (${ '$' }count)"))
    }

    /**
     * Bulk map dialog uses dimension preferences not raw display name.
     */
    @Test
    fun bulk_map_dialog_uses_dimension_preferences_not_raw_display_name() {
        val source = readSource("app/src/main/kotlin/io/payanam/feature/settings/ui/SettingsDialogs.kt")
        assertFalse(source.contains("settings_bulk_map_dimension_selected,\n                                    dimension.displayName"))
        assertFalse(source.contains("} else {\n                                dimension.displayName"))
        assertTrue(source.contains("dimensionPreferences"))
    }

    /**
     * Date time formatting avoids hardcoded patterns in primary screens.
     */
    @Test
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

    /**
     * Reads a source file by its current path, falling back to its name anywhere under `src/`.
     *
     * The screens are migrating out of `app/.../ui/screens/` and into the shared `:core:ui` module (Phase V of
     * the desktop parity migration), and what this test is about is the SCREEN, not where it lives. Pinning the
     * old path made the migration look like a regression — a moved file threw
     * `FileNotFoundException` and the assertion never ran. The fallback keeps the assertion pointed at the real
     * screen for every move still to come, and fails loudly if the file genuinely disappears.
     */
    private fun readSource(relativePath: String): String {
        val root = resolveRepoRoot()
        val direct = root.resolve(relativePath)
        if (Files.isRegularFile(direct)) return direct.toFile().readText()

        val name = Path.of(relativePath).fileName.toString()
        val found =
            listOf("app/src", "core")
                .map(root::resolve)
                .filter(Files::isDirectory)
                .firstNotNullOfOrNull { tree -> findByName(tree, name) }
        return found?.toFile()?.readText()
            ?: error("$name was not found under ${root.resolve("app/src")} or ${root.resolve("core")}")
    }

    /** Depth-first search for [name] under [root], skipping every `build/` directory. */
    private fun findByName(
        root: Path,
        name: String,
    ): Path? {
        var found: Path? = null
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult = if (dir.fileName?.toString() == "build") {
                    FileVisitResult.SKIP_SUBTREE
                } else {
                    FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (found == null && file.fileName.toString() == name) {
                        found = file
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return found
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
