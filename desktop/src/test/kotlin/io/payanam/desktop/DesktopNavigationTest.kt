//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.settings.DesktopSettingsContracts
import io.payanam.shared.settings.DesktopSettingsSnapshot
import io.payanam.shared.settings.DesktopTopLevelRoute
import org.junit.Test

class DesktopNavigationTest {
    @Test
    fun `launch route follows preferred home surface`() {
        assertThat(
            desktopLaunchRoute(DesktopSettingsSnapshot(launchRoute = DesktopTopLevelRoute.TASKS)),
        ).isEqualTo(DesktopTopLevelRoute.TASKS)
        assertThat(
            desktopLaunchRoute(DesktopSettingsSnapshot(launchRoute = DesktopTopLevelRoute.TIME)),
        ).isEqualTo(DesktopTopLevelRoute.TIME)
        assertThat(
            desktopLaunchRoute(DesktopSettingsSnapshot(launchRoute = DesktopTopLevelRoute.NOTES)),
        ).isEqualTo(DesktopTopLevelRoute.NOTES)
        assertThat(
            desktopLaunchRoute(DesktopSettingsSnapshot(launchRoute = DesktopTopLevelRoute.SETTINGS)),
        ).isEqualTo(DesktopTopLevelRoute.SETTINGS)
    }

    @Test
    fun `navigation model exposes all top-level parity routes`() {
        val model = desktopNavigationModel(DesktopSettingsSnapshot(launchRoute = DesktopTopLevelRoute.SETTINGS))

        assertThat(model.launchRoute).isEqualTo(DesktopTopLevelRoute.SETTINGS)
        assertThat(model.primaryRoutes).containsExactlyElementsIn(DesktopTopLevelRoute.entries).inOrder()
    }

    @Test
    fun `navigation model falls back when preferred route is hidden`() {
        val model =
            desktopNavigationModel(
                DesktopSettingsSnapshot(
                    launchRoute = DesktopTopLevelRoute.TASKS,
                    routeVisibility =
                        DesktopSettingsContracts.normalizeRouteVisibility(
                            mapOf(
                                DesktopTopLevelRoute.TASKS to false,
                                DesktopTopLevelRoute.TIME to true,
                            ),
                        ),
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
}
