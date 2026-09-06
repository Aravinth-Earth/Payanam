//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.settings
/**
 * Curated desktop focus presets: each exposes a subset of top-level routes so the
 * shell can show a simplified surface.
 */
enum class FocusModePreset(
    val presetId: String,
    val nameKey: String,
    val descriptionKey: String,
    val visibleRoutes: Set<DesktopTopLevelRoute>,
) {
    SIMPLE_TIME_HABITS(
        presetId = "simple_time_habits",
        nameKey = "preset_simple_time_habits",
        descriptionKey = "preset_simple_time_habits_desc",
        visibleRoutes = setOf(DesktopTopLevelRoute.TIME, DesktopTopLevelRoute.HABITS, DesktopTopLevelRoute.LENSES, DesktopTopLevelRoute.SETTINGS),
    ),
    SIMPLE_JOURNAL(
        presetId = "simple_journal",
        nameKey = "preset_simple_journal",
        descriptionKey = "preset_simple_journal_desc",
        visibleRoutes = setOf(DesktopTopLevelRoute.JOURNAL, DesktopTopLevelRoute.NOTES, DesktopTopLevelRoute.LENSES, DesktopTopLevelRoute.SETTINGS),
    ),
    SIMPLE_TASKS(
        presetId = "simple_tasks",
        nameKey = "preset_simple_tasks",
        descriptionKey = "preset_simple_tasks_desc",
        visibleRoutes = setOf(DesktopTopLevelRoute.TASKS, DesktopTopLevelRoute.LENSES, DesktopTopLevelRoute.SETTINGS),
    ),
    FULL_SUITE(
        presetId = "full_suite",
        nameKey = "preset_full_suite",
        descriptionKey = "preset_full_suite_desc",
        visibleRoutes = DesktopTopLevelRoute.entries.toSet(),
    ),
    ;
    val visibleTabs: Set<String>
        get() = visibleRoutes.map(DesktopTopLevelRoute::storageKey).toSet()

    companion object {
        /**
         * Resolves a preset from its [presetId]; unknown/blank → [FULL_SUITE].
         */
        fun fromPresetId(presetId: String?): FocusModePreset = entries.firstOrNull { it.presetId == presetId } ?: FULL_SUITE
    }
}
