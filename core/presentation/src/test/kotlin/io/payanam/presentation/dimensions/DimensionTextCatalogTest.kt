//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.dimensions

import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.domain.model.DimensionTaxonomyCatalog
import org.junit.Test

/**
 * The display-name half of the dimension catalog, pinned on its own.
 *
 * WHY THIS IS NOT DECORATION: these label strings are APP-OWNED. A dimension label that matches one of them
 * may be normalized or replaced, while anything else is text the user typed - so an entry that quietly
 * stopped resolving, or two entries that resolved to the same label, would break exactly that distinction and
 * start overwriting the user's own words. The same object is what the first-run seeder, the preferences
 * mapper and the Android widget read, so a hole here surfaces as a note filed under a blank dimension name
 * that the user cannot fix from any screen.
 *
 * It also pins the list used for that comparison ([DimensionTextCatalog.namedCanonicalIds]) against the
 * taxonomy itself, because the two are maintained in different modules and a silent divergence is the
 * failure mode: a new canonical dimension with no label would simply never be treated as app-owned.
 */
class DimensionTextCatalogTest {
    private val strings: UiStrings = UiStrings.of(UiLanguage.ENGLISH)

    @Test
    fun `every named canonical id has a label and a description`() {
        for (id in DimensionTextCatalog.namedCanonicalIds) {
            assertThat(DimensionTextCatalog.localizedLabel(id, strings)).isNotEmpty()
            assertThat(DimensionTextCatalog.localizedDescription(id, strings)).isNotEmpty()
        }
    }

    @Test
    fun `no two named ids share a label, so one label identifies one app-owned dimension`() {
        val labels = DimensionTextCatalog.namedCanonicalIds.map { DimensionTextCatalog.localizedLabel(it, strings) }

        assertThat(labels).containsNoDuplicates()
        assertThat(labels).doesNotContain(null)
    }

    @Test
    fun `the named ids are distinct and every one of them is a taxonomy id`() {
        val taxonomyIds = DimensionTaxonomyCatalog.entries.map { it.id }

        assertThat(DimensionTextCatalog.namedCanonicalIds).containsNoDuplicates()
        // Subset, not equality: the taxonomy may grow entries the app intentionally has no copy for, and this
        // test must fail on a named id that the taxonomy does not know - which is the case that would make a
        // label comparison answer for a dimension that no longer exists.
        assertThat(taxonomyIds).containsAtLeastElementsIn(DimensionTextCatalog.namedCanonicalIds)
    }

    @Test
    fun `an unknown or absent id answers null so the caller keeps the user's own text`() {
        val invented = "dim_the_user_invented"

        assertThat(DimensionTextCatalog.localizedLabel(invented, strings)).isNull()
        assertThat(DimensionTextCatalog.localizedDescription(invented, strings)).isNull()
        assertThat(DimensionTextCatalog.localizedLabel(null, strings)).isNull()
        assertThat(DimensionTextCatalog.localizedDescription(null, strings)).isNull()
    }
}
