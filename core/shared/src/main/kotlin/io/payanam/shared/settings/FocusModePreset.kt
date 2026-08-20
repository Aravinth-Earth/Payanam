//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.settings

/**
 * FocusModePreset.
 */
enum class FocusModePreset(
    /** Preset id. */
    val presetId: String,
    /** Name key. */
    val nameKey: String,
    /** Description key. */
    val descriptionKey: String,
    /** Visible routes. */
    val visibleRoutes: Set<DesktopTopLevelRoute>,
) {
    /** Simple time habits. */
    SIMPLE_TIME_HABITS(
        presetId = "simple_time_habits",
        nameKey = "preset_simple_time_habits",
        descriptionKey = "preset_simple_time_habits_desc",
        visibleRoutes = setOf(DesktopTopLevelRoute.TIME, DesktopTopLevelRoute.HABITS, DesktopTopLevelRoute.LENSES, DesktopTopLevelRoute.SETTINGS),
    ),
    /** Simple journal. */
    SIMPLE_JOURNAL(
        presetId = "simple_journal",
        nameKey = "preset_simple_journal",
        descriptionKey = "preset_simple_journal_desc",
        visibleRoutes = setOf(DesktopTopLevelRoute.JOURNAL, DesktopTopLevelRoute.NOTES, DesktopTopLevelRoute.LENSES, DesktopTopLevelRoute.SETTINGS),
    ),
    /** Simple tasks. */
    SIMPLE_TASKS(
        presetId = "simple_tasks",
        nameKey = "preset_simple_tasks",
        descriptionKey = "preset_simple_tasks_desc",
        visibleRoutes = setOf(DesktopTopLevelRoute.TASKS, DesktopTopLevelRoute.LENSES, DesktopTopLevelRoute.SETTINGS),
    ),
    /** Full suite. */
    FULL_SUITE(
        presetId = "full_suite",
        nameKey = "preset_full_suite",
        descriptionKey = "preset_full_suite_desc",
        visibleRoutes = DesktopTopLevelRoute.entries.toSet(),
    ),
    ;

    /** Visible tabs. */
    val visibleTabs: Set<String>
        /** Get. */
        get() = visibleRoutes.map(DesktopTopLevelRoute::storageKey).toSet()

    companion object {
        /**
         * From preset id.
         */
        fun fromPresetId(presetId: String?): FocusModePreset = entries.firstOrNull { it.presetId == presetId } ?: FULL_SUITE
    }
}
