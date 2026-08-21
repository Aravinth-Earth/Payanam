//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard: disabled modules must not be visible or routable in minimal mode.
 *
 * Complements the runtime back-stack guard in PayanamNavHost — this test verifies the
 * pure-logic layer (NavRoutePolicy) so breakage is caught at compile/unit-test time,
 * long before a device is needed.
 */
class NavRoutePolicyMinimalModeTest {

    // ── Minimal mode ON: allowed tabs ──────────────────────────────────────
    @Test fun `minimal mode - time tab is allowed`() = assertTrue(NavRoutePolicy.isAllowed("time", minimalModeEnabled = true))

    @Test fun `minimal mode - tasks tab is allowed`() = assertTrue(NavRoutePolicy.isAllowed("tasks", minimalModeEnabled = true))

    @Test fun `minimal mode - lenses tab is allowed`() = assertTrue(NavRoutePolicy.isAllowed("lenses", minimalModeEnabled = true))

    @Test fun `minimal mode - settings tab is allowed`() = assertTrue(NavRoutePolicy.isAllowed("settings", minimalModeEnabled = true))

    @Test fun `minimal mode - notes tab is allowed`() = assertTrue(NavRoutePolicy.isAllowed("notes", minimalModeEnabled = true))

    @Test fun `minimal mode - journal tab is allowed`() = assertTrue(NavRoutePolicy.isAllowed("journal", minimalModeEnabled = true))

    // ── Minimal mode ON: disabled tabs must be blocked ─────────────────────
    @Test fun `minimal mode - habits tab is blocked`() = assertFalse(NavRoutePolicy.isAllowed("habits", minimalModeEnabled = true))

    // ── Minimal mode ON: secondary (child) routes always pass ──────────────
    @Test fun `minimal mode - add_task child route is allowed`() = assertTrue(NavRoutePolicy.isAllowed("add_task", minimalModeEnabled = true))

    @Test fun `minimal mode - task_detail with id is allowed`() = assertTrue(NavRoutePolicy.isAllowed("task_detail/abc-123", minimalModeEnabled = true))

    @Test fun `minimal mode - edit_task with id is allowed`() = assertTrue(NavRoutePolicy.isAllowed("edit_task/abc-123", minimalModeEnabled = true))

    @Test fun `minimal mode - scoring_config is allowed`() = assertTrue(NavRoutePolicy.isAllowed("scoring_config", minimalModeEnabled = true))

    // ── Minimal mode ON: startup gate routes always pass
    @Test fun `minimal mode - database_init is always allowed`() = assertTrue(NavRoutePolicy.isAllowed("database_init", minimalModeEnabled = true))

    @Test fun `minimal mode - passphrase_setup is always allowed`() = assertTrue(NavRoutePolicy.isAllowed("passphrase_setup", minimalModeEnabled = true))

    @Test fun `minimal mode - passphrase_unlock is always allowed`() = assertTrue(NavRoutePolicy.isAllowed("passphrase_unlock", minimalModeEnabled = true))

    @Test fun `minimal mode - passphrase_change is always allowed`() = assertTrue(NavRoutePolicy.isAllowed("passphrase_change", minimalModeEnabled = true))

    @Test fun `minimal mode - focus_mode_selection is always allowed`() = assertTrue(NavRoutePolicy.isAllowed("focus_mode_selection", minimalModeEnabled = true))

    // ── Minimal mode OFF: all main tabs allowed ────────────────────────────
    @Test fun `full mode - habits tab is allowed`() = assertTrue(NavRoutePolicy.isAllowed("habits", minimalModeEnabled = false))

    @Test fun `full mode - notes tab is allowed`() = assertTrue(NavRoutePolicy.isAllowed("notes", minimalModeEnabled = false))

    @Test fun `full mode - journal tab is allowed`() = assertTrue(NavRoutePolicy.isAllowed("journal", minimalModeEnabled = false))

    @Test fun `full mode - time tab is allowed`() = assertTrue(NavRoutePolicy.isAllowed("time", minimalModeEnabled = false))

    @Test fun `full mode - tasks tab is allowed`() = assertTrue(NavRoutePolicy.isAllowed("tasks", minimalModeEnabled = false))

    @Test fun `full mode - lenses tab is allowed`() = assertTrue(NavRoutePolicy.isAllowed("lenses", minimalModeEnabled = false))

    @Test fun `full mode - settings tab is allowed`() = assertTrue(NavRoutePolicy.isAllowed("settings", minimalModeEnabled = false))

    // ── minimalModeAllowedTabs + secondaryRoutes are consistent ───────────
    @Test fun `minimalModeAllowedTabs contains exactly time tasks journal notes lenses settings`() {
        val expected = setOf("time", "tasks", "journal", "notes", "lenses", "settings")
        assertTrue(
            "minimalModeAllowedTabs must contain exactly $expected but was ${NavRoutePolicy.minimalModeAllowedTabs}",
            NavRoutePolicy.minimalModeAllowedTabs == expected,
        )
    }

    @Test fun `disabled tabs are not in minimalModeAllowedTabs`() {
        val disabledTabs = listOf("habits")
        disabledTabs.forEach { tab ->
            assertFalse(
                "Disabled tab '$tab' must not be in minimalModeAllowedTabs",
                tab in NavRoutePolicy.minimalModeAllowedTabs,
            )
        }
    }
}
