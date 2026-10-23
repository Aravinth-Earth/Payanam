//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DesktopPreferenceContractsTest {
    @Test
    fun `default snapshot stays stable for desktop bootstrap`() {
        val snapshot = DesktopSettingsContracts.defaultSnapshot()
        assertThat(snapshot.schemaVersion).isEqualTo(DesktopSettingsContracts.SCHEMA_VERSION)
        assertThat(snapshot.themeMode).isEqualTo(DesktopThemeMode.DARK)
        assertThat(snapshot.language).isEqualTo(DesktopLanguage.SYSTEM)
        assertThat(snapshot.launchRoute).isEqualTo(DesktopTopLevelRoute.SETTINGS)
        assertThat(snapshot.activePreset).isEqualTo(FocusModePreset.FULL_SUITE)
        assertThat(snapshot.focusModeOnboardingCompleted).isFalse()
        assertThat(snapshot.visibleRoutes()).containsExactlyElementsIn(DesktopTopLevelRoute.entries).inOrder()
        assertThat(snapshot.sessionLoggingEnabled).isTrue()
        // Scope A: previously non-persisted controls
        assertThat(snapshot.focusModeEnabled).isTrue()
        assertThat(snapshot.habitTrackingEnabled).isFalse()
        assertThat(snapshot.timeInsightsEnabled).isTrue()
        assertThat(snapshot.autoBackupEnabled).isFalse()
        assertThat(snapshot.backupFrequency).isEqualTo("60m")
        assertThat(snapshot.scoringModel).isEqualTo("balanced")
        assertThat(snapshot.unlockTimeout).isEqualTo(120)
        // Scope B: genuinely missing desktop settings
        assertThat(snapshot.timeFormat).isEqualTo("24h")
        assertThat(snapshot.backupRotationEnabled).isFalse()
        assertThat(snapshot.backupRotationCount).isEqualTo(50)
    }

    @Test
    fun `storage key parsing falls back safely`() {
        assertThat(DesktopThemeMode.fromStorageKey("missing")).isEqualTo(DesktopThemeMode.SYSTEM)
        assertThat(DesktopLanguage.fromStorageKey("missing")).isEqualTo(DesktopLanguage.SYSTEM)
        assertThat(DesktopTopLevelRoute.fromStorageKey("missing")).isEqualTo(DesktopTopLevelRoute.SETTINGS)
    }

    @Test
    fun `all desktop top level routes remain available for shared app shell`() {
        assertThat(DesktopTopLevelRoute.entries.map { it.storageKey })
            .containsExactly(
                "tasks",
                "habits",
                "time",
                "journal",
                "notes",
                "lenses",
                "settings",
            ).inOrder()
    }

    @Test
    fun `normalize route visibility keeps settings visible`() {
        val normalized =
            DesktopSettingsContracts.normalizeRouteVisibility(
                mapOf(
                    DesktopTopLevelRoute.TASKS to false,
                    DesktopTopLevelRoute.SETTINGS to false,
                ),
            )
        assertThat(normalized[DesktopTopLevelRoute.TASKS]).isFalse()
        assertThat(normalized[DesktopTopLevelRoute.SETTINGS]).isTrue()
    }

    @Test
    fun `focus preset parsing and tab visibility stay stable`() {
        assertThat(FocusModePreset.fromPresetId("simple_time_habits")).isEqualTo(FocusModePreset.SIMPLE_TIME_HABITS)
        assertThat(FocusModePreset.fromPresetId("simple_journal")).isEqualTo(FocusModePreset.SIMPLE_JOURNAL)
        assertThat(FocusModePreset.fromPresetId("simple_tasks")).isEqualTo(FocusModePreset.SIMPLE_TASKS)
        assertThat(FocusModePreset.fromPresetId("full_suite")).isEqualTo(FocusModePreset.FULL_SUITE)
        assertThat(FocusModePreset.fromPresetId("missing")).isEqualTo(FocusModePreset.FULL_SUITE)
        assertThat(FocusModePreset.SIMPLE_TIME_HABITS.visibleTabs)
            .containsExactly("time", "habits", "lenses", "settings")
        assertThat(FocusModePreset.SIMPLE_JOURNAL.visibleTabs)
            .containsExactly("journal", "notes", "lenses", "settings")
        assertThat(FocusModePreset.SIMPLE_TASKS.visibleTabs)
            .containsExactly("tasks", "lenses", "settings")
        assertThat(FocusModePreset.FULL_SUITE.visibleTabs)
            .containsExactlyElementsIn(DesktopTopLevelRoute.entries.map(DesktopTopLevelRoute::storageKey))
    }

    @Test
    fun `route visibility preset keeps settings visible and hides unrelated routes`() {
        val tasksVisibility = DesktopSettingsContracts.routeVisibilityForPreset(FocusModePreset.SIMPLE_TASKS)
        val journalVisibility = DesktopSettingsContracts.routeVisibilityForPreset(FocusModePreset.SIMPLE_JOURNAL)
        assertThat(tasksVisibility[DesktopTopLevelRoute.TASKS]).isTrue()
        assertThat(tasksVisibility[DesktopTopLevelRoute.LENSES]).isTrue()
        assertThat(tasksVisibility[DesktopTopLevelRoute.SETTINGS]).isTrue()
        assertThat(tasksVisibility[DesktopTopLevelRoute.TIME]).isFalse()
        assertThat(tasksVisibility[DesktopTopLevelRoute.JOURNAL]).isFalse()
        assertThat(journalVisibility[DesktopTopLevelRoute.JOURNAL]).isTrue()
        assertThat(journalVisibility[DesktopTopLevelRoute.NOTES]).isTrue()
        assertThat(journalVisibility[DesktopTopLevelRoute.SETTINGS]).isTrue()
        assertThat(journalVisibility[DesktopTopLevelRoute.TASKS]).isFalse()
    }

    @Test
    fun `isRouteVisible falls back to DEFAULT_ROUTE_VISIBLE when a route is absent from the map`() {
        val snapshot =
            DesktopSettingsSnapshot(
                routeVisibility = mapOf(DesktopTopLevelRoute.TASKS to true, DesktopTopLevelRoute.JOURNAL to false),
            )
        assertThat(snapshot.isRouteVisible(DesktopTopLevelRoute.TASKS)).isTrue()
        assertThat(snapshot.isRouteVisible(DesktopTopLevelRoute.JOURNAL)).isFalse()
        // HABITS is absent — the map lookup returns null, so the fallback must kick in
        assertThat(snapshot.isRouteVisible(DesktopTopLevelRoute.HABITS))
            .isEqualTo(DesktopSettingsContracts.DEFAULT_ROUTE_VISIBLE)
    }

    @Test
    fun `visibleRoutes only includes routes whose flag is true`() {
        val snapshot =
            DesktopSettingsSnapshot(
                routeVisibility =
                    mapOf(
                        DesktopTopLevelRoute.TASKS to true,
                        DesktopTopLevelRoute.HABITS to false,
                        DesktopTopLevelRoute.JOURNAL to true,
                        DesktopTopLevelRoute.SETTINGS to true,
                    ),
            )
        // LENSES, TIME, NOTES absent → default true
        assertThat(snapshot.visibleRoutes())
            .containsExactlyElementsIn(
                listOf(
                    DesktopTopLevelRoute.TASKS,
                    DesktopTopLevelRoute.TIME,
                    DesktopTopLevelRoute.JOURNAL,
                    DesktopTopLevelRoute.NOTES,
                    DesktopTopLevelRoute.LENSES,
                    DesktopTopLevelRoute.SETTINGS,
                ),
            ).inOrder()
    }

    @Test
    fun `focus preset exposes stable name and description keys for the i18n layer`() {
        assertThat(FocusModePreset.SIMPLE_TIME_HABITS.nameKey).isEqualTo("preset_simple_time_habits")
        assertThat(FocusModePreset.SIMPLE_TIME_HABITS.descriptionKey)
            .isEqualTo("preset_simple_time_habits_desc")
        assertThat(FocusModePreset.SIMPLE_JOURNAL.nameKey).isEqualTo("preset_simple_journal")
        assertThat(FocusModePreset.SIMPLE_JOURNAL.descriptionKey)
            .isEqualTo("preset_simple_journal_desc")
        assertThat(FocusModePreset.SIMPLE_TASKS.nameKey).isEqualTo("preset_simple_tasks")
        assertThat(FocusModePreset.SIMPLE_TASKS.descriptionKey)
            .isEqualTo("preset_simple_tasks_desc")
        assertThat(FocusModePreset.FULL_SUITE.nameKey).isEqualTo("preset_full_suite")
        assertThat(FocusModePreset.FULL_SUITE.descriptionKey).isEqualTo("preset_full_suite_desc")
    }
}
