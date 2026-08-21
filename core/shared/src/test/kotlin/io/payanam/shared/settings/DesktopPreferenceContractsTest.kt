//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * DesktopPreferenceContractsTest.
 */
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
    }

    @Test
    fun `storage key parsing falls back safely`() {
        assertThat(DesktopThemeMode.fromStorageKey("missing")).isEqualTo(DesktopThemeMode.SYSTEM)
        assertThat(DesktopLanguage.fromStorageKey("missing")).isEqualTo(DesktopLanguage.SYSTEM)
        assertThat(DesktopTopLevelRoute.fromStorageKey("missing")).isEqualTo(DesktopTopLevelRoute.SETTINGS)
    }

    @Test
    fun `all desktop top level routes remain available for shared app shell`() {
        assertThat(DesktopTopLevelRoute.entries.map { it.storageKey }).containsExactly(
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
}
