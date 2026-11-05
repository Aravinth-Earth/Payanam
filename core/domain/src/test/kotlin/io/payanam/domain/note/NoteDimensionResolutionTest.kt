//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.note

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.model.DimensionTaxonomyCatalog
import org.junit.Test

/**
 * Tests for [NoteDimensionResolution] — the one copy of the note dimension rules, shared by the Android
 * Room implementation and the desktop JDBC implementation.
 *
 * The assertions are written against [DimensionTaxonomyCatalog] itself rather than against literal ids and
 * labels, so an edit to the taxonomy cannot make this test pass while the resolution silently changes.
 */
class NoteDimensionResolutionTest {
    @Test
    fun `an explicit canonical id is returned unchanged`() {
        val canonicalId = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id

        assertThat(
            NoteDimensionResolution.resolveDimensionId(
                explicitDimensionId = canonicalId,
                categoryLabel = null,
            ),
        ).isEqualTo(canonicalId)
    }

    @Test
    fun `an unknown id is kept rather than dropped`() {
        assertThat(
            NoteDimensionResolution.resolveDimensionId(
                explicitDimensionId = "dim_that_does_not_exist",
                categoryLabel = null,
            ),
        ).isEqualTo("dim_that_does_not_exist")
    }

    @Test
    fun `a blank id falls through to no id rather than being kept as whitespace`() {
        assertThat(
            NoteDimensionResolution.resolveDimensionId(
                explicitDimensionId = "   ",
                categoryLabel = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.fallbackLabel,
            ),
        ).isNull()
    }

    @Test
    fun `a category label alone never invents a dimension id`() {
        // The rule that matters most: inferring an id from a bare label used to move notes onto a
        // canonical dimension the user never picked, so a label must resolve to null instead.
        assertThat(
            NoteDimensionResolution.resolveDimensionId(
                explicitDimensionId = null,
                categoryLabel = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.fallbackLabel,
            ),
        ).isNull()
    }

    @Test
    fun `an explicit label is persisted as given`() {
        assertThat(
            NoteDimensionResolution.resolveDimensionLabel(
                explicitLabel = "Mental Health",
                resolvedDimensionId = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id,
            ),
        ).isEqualTo("Mental Health")
    }

    @Test
    fun `a missing label falls back to the canonical dimension's label`() {
        assertThat(
            NoteDimensionResolution.resolveDimensionLabel(
                explicitLabel = "  ",
                resolvedDimensionId = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id,
            ),
        ).isEqualTo(DimensionTaxonomyCatalog.WORK_LIVELIHOOD.fallbackLabel)
    }

    @Test
    fun `a missing label and an unusable id fall back to the catch-all dimension`() {
        assertThat(
            NoteDimensionResolution.resolveDimensionLabel(
                explicitLabel = null,
                resolvedDimensionId = "dim_that_does_not_exist",
            ),
        ).isEqualTo(DimensionTaxonomyCatalog.WORK_LIVELIHOOD.fallbackLabel)
    }
}
