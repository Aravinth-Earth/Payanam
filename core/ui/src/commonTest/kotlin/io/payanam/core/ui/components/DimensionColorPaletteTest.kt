//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.components

import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The colour picker's palette and the labels it announces.
 *
 * The swatch labels used to be stored `R.string` ids, so a missing one could not compile. They now
 * resolve through [dimensionColorLabel], a `when` over the stored hex value: that buys the same
 * compile-time checking of every string PROPERTY but loses the compiler's ability to tell that all 39
 * swatches are COVERED, because a `when` with no matching branch is still valid. Coverage is what
 * these tests check, per language - a swatch with no label renders as an unlabeled circle to assistive
 * technology, which is the failure this guards.
 */
class DimensionColorPaletteTest {
    @Test
    fun `every swatch has a label in every shipped language`() {
        for (language in UiLanguage.entries) {
            val strings = UiStrings.of(language)
            for (hex in DimensionColorHexOptions) {
                val label = dimensionColorLabel(hex, strings)
                assertNotNull(label, "no ${language.name} label for the swatch $hex")
                assertTrue(label.isNotBlank(), "blank ${language.name} label for the swatch $hex")
            }
        }
    }

    @Test
    fun `labels are unique within a language, so no two swatches read the same`() {
        // The palette's shade suffixing exists for this: screen readers announce the label, so two
        // swatches sharing one would be indistinguishable to a user who cannot see the colour.
        for (language in UiLanguage.entries) {
            val strings = UiStrings.of(language)
            val labels = DimensionColorHexOptions.mapNotNull { dimensionColorLabel(it, strings) }
            assertEquals(
                DimensionColorHexOptions.size,
                labels.toSet().size,
                "duplicate ${language.name} swatch labels: $labels",
            )
        }
    }

    @Test
    fun `no two swatches are the same colour`() {
        assertEquals(
            DimensionColorHexOptions.size,
            DimensionColorHexOptions.toSet().size,
            "the palette repeats a colour, so one swatch can never be selected",
        )
        assertTrue(DimensionColorHexOptions.all { it.matches(Regex("#[0-9A-F]{6}")) })
    }

    @Test
    fun `the first swatch is the default selection, and must not drift`() {
        // Onboarding and the dimension editor both seed the draft colour from `first()`, so the palette's
        // ORDER is part of the contract - reordering it would silently change what a new dimension gets.
        assertEquals("#3F51B5", DimensionColorHexOptions.first())
    }

    @Test
    fun `a hex is matched as forgivingly as a stored colour is written`() {
        val strings = UiStrings.of(UiLanguage.ENGLISH)
        val expected = dimensionColorLabel("#3F51B5", strings)

        assertNotNull(expected)
        // Stored colours arrive from the database and from `toDimensionHexString()`, which disagree
        // about case and about the leading hash; all four spellings must resolve to the same swatch.
        assertEquals(expected, dimensionColorLabel("#3f51b5", strings))
        assertEquals(expected, dimensionColorLabel("3F51B5", strings))
        assertEquals(expected, dimensionColorLabel("  #3F51B5  ", strings))
    }

    @Test
    fun `a colour outside the palette resolves to null rather than to a neighbour`() {
        // Null, not the nearest swatch: the caller renders the stored colour itself, and picking a
        // palette entry it does not own would misreport the user's own choice back to them.
        val strings = UiStrings.of(UiLanguage.ENGLISH)

        assertNull(dimensionColorLabel("#123456", strings))
        assertNull(dimensionColorLabel("", strings))
        assertNull(dimensionColorLabel("#", strings))
        assertNull(dimensionColorLabel("not-a-colour", strings))
    }
}
