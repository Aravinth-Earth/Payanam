//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.preferences

import io.payanam.core.ui.navigation.PayanamTab
import io.payanam.core.ui.navigation.payanamBottomNavItems
import io.payanam.shared.settings.DesktopTopLevelRoute
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tab visibility map is keyed by the ids in [ALL_TAB_ROUTES], which are read straight out of the
 * stored `tab_visible_*` rows and out of a focus-mode preset's `visibleTabs`. Two other declarations
 * name the same tabs - [PayanamTab]'s routes and [DesktopTopLevelRoute]'s storage keys - and neither
 * derives from the list above, so the three can drift apart with no compile error.
 *
 * The cost of the drift is not cosmetic: a tab missing from [ALL_TAB_ROUTES] makes its stored
 * visibility row unreadable, and a tab added to it that no nav destination owns produces a key
 * nothing ever reads. [assertSameRouteIds] therefore reports the ids missing on each side rather
 * than a bare inequality, so a failure names the tab to fix instead of two long lists.
 */
class TopLevelTabRouteIdsTest {
    @Test
    fun allTabRoutesMatchTheBottomNavigationTabs() {
        assertSameRouteIds(
            expected = ALL_TAB_ROUTES,
            actual = payanamBottomNavItems.map { it.route },
            expectedName = "ALL_TAB_ROUTES",
            actualName = "PayanamTab routes",
        )
    }

    @Test
    fun allTabRoutesMatchTheDesktopTopLevelStorageKeys() {
        assertSameRouteIds(
            expected = ALL_TAB_ROUTES,
            actual = DesktopTopLevelRoute.entries.map { it.storageKey },
            expectedName = "ALL_TAB_ROUTES",
            actualName = "DesktopTopLevelRoute.storageKey",
        )
    }

    @Test
    fun allTabRoutesAreDistinct() {
        assertEquals(
            emptyList<String>(),
            repeated(ALL_TAB_ROUTES),
            "ALL_TAB_ROUTES repeats an id, so a tab visibility row would be read for the wrong tab",
        )
    }

    /**
     * Asserts the two id lists describe the same set of tabs.
     *
     * Both directions are checked and reported together: an id in [expected] but not [actual] is a tab
     * whose stored `tab_visible_*` row is silently dropped, and an id in [actual] but not [expected] is
     * a tab that can be navigated to but whose visibility can never be read. A repeated id is reported
     * too, because it would pass a set comparison while aliasing one tab onto another.
     */
    private fun assertSameRouteIds(
        expected: List<String>,
        actual: List<String>,
        expectedName: String,
        actualName: String,
    ) {
        val expectedSet = expected.toSet()
        val actualSet = actual.toSet()
        val problems = buildList {
            expected.filter { it !in actualSet }.forEach {
                add("\"$it\" is in $expectedName but not in $actualName")
            }
            actual.filter { it !in expectedSet }.forEach {
                add("\"$it\" is in $actualName but not in $expectedName")
            }
            repeated(expected).forEach { add("\"$it\" appears more than once in $expectedName") }
            repeated(actual).forEach { add("\"$it\" appears more than once in $actualName") }
        }
        assertEquals(emptyList<String>(), problems)
    }

    private fun repeated(ids: List<String>): List<String> =
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
}
