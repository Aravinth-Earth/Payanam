//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.settings.DesktopTopLevelRoute
import org.junit.Test

/**
 * DesktopRouteContentTest.
 */
class DesktopRouteContentTest {
    @Test
    fun `settings route has no placeholder content`() {
        assertThat(desktopRoutePlaceholderContent(DesktopTopLevelRoute.SETTINGS)).isNull()
    }

    @Test
    fun `tasks placeholder content describes next parity target`() {
        val content = desktopRoutePlaceholderContent(DesktopTopLevelRoute.TASKS)

        assertThat(content).isNotNull()
        assertThat(content!!.title).isEqualTo("Tasks shell")
        assertThat(content.readiness).isEqualTo("Next major parity slice")
        assertThat(content.details).contains("Will absorb Tasks and Habits list read paths.")
    }

    @Test
    fun `notes route has no placeholder content`() {
        assertThat(desktopRoutePlaceholderContent(DesktopTopLevelRoute.NOTES)).isNull()
    }

    @Test
    fun `journal route has no placeholder content`() {
        assertThat(desktopRoutePlaceholderContent(DesktopTopLevelRoute.JOURNAL)).isNull()
    }

    @Test
    fun `remaining non-settings routes expose placeholder content`() {
        val coveredRoutes =
            DesktopTopLevelRoute.entries.filter {
                it != DesktopTopLevelRoute.SETTINGS &&
                    it != DesktopTopLevelRoute.NOTES &&
                    it != DesktopTopLevelRoute.JOURNAL
            }

        coveredRoutes.forEach { route ->
            val content = desktopRoutePlaceholderContent(route)
            assertThat(content).isNotNull()
            assertThat(content!!.summary).isNotEmpty()
            assertThat(content.details).isNotEmpty()
        }
    }
}
