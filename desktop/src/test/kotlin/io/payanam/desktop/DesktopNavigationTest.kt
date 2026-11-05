//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.LaunchDestination
import io.payanam.shared.settings.DesktopTopLevelRoute
import org.junit.Test

/**
 * Provides the desktop navigation test.
 *
 * The navigation model reads the SHARED preferences now, so every case builds an [AppPreferencesState] out
 * of `launch_destination_route` / `tab_visible_*` rows rather than a desktop snapshot.
 */
class DesktopNavigationTest {
    @Test
    fun `launch route follows preferred home surface`() {
        assertThat(desktopLaunchRoute(preferences(launchRoute = DesktopTopLevelRoute.TASKS)))
            .isEqualTo(DesktopTopLevelRoute.TASKS)
        assertThat(desktopLaunchRoute(preferences(launchRoute = DesktopTopLevelRoute.TIME)))
            .isEqualTo(DesktopTopLevelRoute.TIME)
        assertThat(desktopLaunchRoute(preferences(launchRoute = DesktopTopLevelRoute.NOTES)))
            .isEqualTo(DesktopTopLevelRoute.NOTES)
        assertThat(desktopLaunchRoute(preferences(launchRoute = DesktopTopLevelRoute.SETTINGS)))
            .isEqualTo(DesktopTopLevelRoute.SETTINGS)
    }

    @Test
    fun `navigation model exposes all top-level parity routes`() {
        val model = desktopNavigationModel(preferences(launchRoute = DesktopTopLevelRoute.SETTINGS))

        assertThat(model.launchRoute).isEqualTo(DesktopTopLevelRoute.SETTINGS)
        assertThat(model.primaryRoutes).containsExactlyElementsIn(DesktopTopLevelRoute.entries).inOrder()
    }

    @Test
    fun `navigation model falls back when preferred route is hidden`() {
        val model =
            desktopNavigationModel(
                preferences(
                    launchRoute = DesktopTopLevelRoute.TASKS,
                    hidden = setOf(DesktopTopLevelRoute.TASKS),
                ),
            )

        assertThat(model.launchRoute).isEqualTo(DesktopTopLevelRoute.HABITS)
        assertThat(model.primaryRoutes).doesNotContain(DesktopTopLevelRoute.TASKS)
        assertThat(model.primaryRoutes).contains(DesktopTopLevelRoute.SETTINGS)
    }

    @Test
    fun `route summaries stay available for every desktop top-level route`() {
        DesktopTopLevelRoute.entries.forEach { route ->
            assertThat(route.summary()).isNotEmpty()
        }
    }

    /** The rows Android writes for the launch destination and the tab visibility. */
    private fun preferences(
        launchRoute: DesktopTopLevelRoute = DesktopTopLevelRoute.SETTINGS,
        hidden: Set<DesktopTopLevelRoute> = emptySet(),
    ): AppPreferencesState =
        AppPreferencesState(
            launchDestination = LaunchDestination(route = launchRoute.storageKey),
            tabVisibility = DesktopTopLevelRoute.entries.associate { it.storageKey to (it !in hidden) },
        )
}
