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

        /** Assert that. */
        assertThat(snapshot.schemaVersion).isEqualTo(DesktopSettingsContracts.SCHEMA_VERSION)
        /** Assert that. */
        assertThat(snapshot.themeMode).isEqualTo(DesktopThemeMode.DARK)
        /** Assert that. */
        assertThat(snapshot.language).isEqualTo(DesktopLanguage.SYSTEM)
        /** Assert that. */
        assertThat(snapshot.launchRoute).isEqualTo(DesktopTopLevelRoute.SETTINGS)
        /** Assert that. */
        assertThat(snapshot.activePreset).isEqualTo(FocusModePreset.FULL_SUITE)
        /** Assert that. */
        assertThat(snapshot.focusModeOnboardingCompleted).isFalse()
        /** Assert that. */
        assertThat(snapshot.visibleRoutes()).containsExactlyElementsIn(DesktopTopLevelRoute.entries).inOrder()
        /** Assert that. */
        assertThat(snapshot.sessionLoggingEnabled).isTrue()
    }

    @Test
    fun `storage key parsing falls back safely`() {
        /** Assert that. */
        assertThat(DesktopThemeMode.fromStorageKey("missing")).isEqualTo(DesktopThemeMode.SYSTEM)
        /** Assert that. */
        assertThat(DesktopLanguage.fromStorageKey("missing")).isEqualTo(DesktopLanguage.SYSTEM)
        /** Assert that. */
        assertThat(DesktopTopLevelRoute.fromStorageKey("missing")).isEqualTo(DesktopTopLevelRoute.SETTINGS)
    }

    @Test
    fun `all desktop top level routes remain available for shared app shell`() {
        /** Assert that. */
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
                /** Map of. */
                mapOf(
                    DesktopTopLevelRoute.TASKS to false,
                    DesktopTopLevelRoute.SETTINGS to false,
                ),
            )

        /** Assert that. */
        assertThat(normalized[DesktopTopLevelRoute.TASKS]).isFalse()
        /** Assert that. */
        assertThat(normalized[DesktopTopLevelRoute.SETTINGS]).isTrue()
    }

    @Test
    fun `focus preset parsing and tab visibility stay stable`() {
        /** Assert that. */
        assertThat(FocusModePreset.fromPresetId("simple_time_habits")).isEqualTo(FocusModePreset.SIMPLE_TIME_HABITS)
        /** Assert that. */
        assertThat(FocusModePreset.fromPresetId("simple_journal")).isEqualTo(FocusModePreset.SIMPLE_JOURNAL)
        /** Assert that. */
        assertThat(FocusModePreset.fromPresetId("simple_tasks")).isEqualTo(FocusModePreset.SIMPLE_TASKS)
        /** Assert that. */
        assertThat(FocusModePreset.fromPresetId("full_suite")).isEqualTo(FocusModePreset.FULL_SUITE)
        /** Assert that. */
        assertThat(FocusModePreset.fromPresetId("missing")).isEqualTo(FocusModePreset.FULL_SUITE)

        /** Assert that. */
        assertThat(FocusModePreset.SIMPLE_TIME_HABITS.visibleTabs)
            .containsExactly("time", "habits", "lenses", "settings")
        /** Assert that. */
        assertThat(FocusModePreset.SIMPLE_JOURNAL.visibleTabs)
            .containsExactly("journal", "notes", "lenses", "settings")
        /** Assert that. */
        assertThat(FocusModePreset.SIMPLE_TASKS.visibleTabs)
            .containsExactly("tasks", "lenses", "settings")
        /** Assert that. */
        assertThat(FocusModePreset.FULL_SUITE.visibleTabs)
            .containsExactlyElementsIn(DesktopTopLevelRoute.entries.map(DesktopTopLevelRoute::storageKey))
    }

    @Test
    fun `route visibility preset keeps settings visible and hides unrelated routes`() {
        val tasksVisibility = DesktopSettingsContracts.routeVisibilityForPreset(FocusModePreset.SIMPLE_TASKS)
        val journalVisibility = DesktopSettingsContracts.routeVisibilityForPreset(FocusModePreset.SIMPLE_JOURNAL)

        /** Assert that. */
        assertThat(tasksVisibility[DesktopTopLevelRoute.TASKS]).isTrue()
        /** Assert that. */
        assertThat(tasksVisibility[DesktopTopLevelRoute.LENSES]).isTrue()
        /** Assert that. */
        assertThat(tasksVisibility[DesktopTopLevelRoute.SETTINGS]).isTrue()
        /** Assert that. */
        assertThat(tasksVisibility[DesktopTopLevelRoute.TIME]).isFalse()
        /** Assert that. */
        assertThat(tasksVisibility[DesktopTopLevelRoute.JOURNAL]).isFalse()

        /** Assert that. */
        assertThat(journalVisibility[DesktopTopLevelRoute.JOURNAL]).isTrue()
        /** Assert that. */
        assertThat(journalVisibility[DesktopTopLevelRoute.NOTES]).isTrue()
        /** Assert that. */
        assertThat(journalVisibility[DesktopTopLevelRoute.SETTINGS]).isTrue()
        /** Assert that. */
        assertThat(journalVisibility[DesktopTopLevelRoute.TASKS]).isFalse()
    }
}
