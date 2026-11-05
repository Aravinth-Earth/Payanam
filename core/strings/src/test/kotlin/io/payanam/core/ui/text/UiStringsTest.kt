//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The generated copy table's own guarantees, asserted here rather than trusted.
 *
 * The table is generated, so these cannot fail because someone mistyped a string — they can only fail
 * because the GENERATOR stopped guaranteeing what the rest of the app relies on. That is the point: a
 * shared ViewModel is about to read its error and label copy out of this table on both platforms, and a
 * silently-empty or language-divergent table would surface as a blank label in one language only.
 */
class UiStringsTest {
    @Test
    fun everyShippedLanguageIsComplete() {
        val english = UiStrings.of(UiLanguage.ENGLISH)
        val tamil = UiStrings.of(UiLanguage.TAMIL)

        val blankInTamil = tamilBlankKeys(tamil)
        val blankInEnglish = tamilBlankKeys(english)

        assertThat(blankInTamil).isEmpty()
        assertThat(blankInEnglish).isEmpty()
    }

    @Test
    fun formatArgumentsSurviveGeneration() {
        // A summary label carries a number. The generator copies the text verbatim, so the placeholder
        // must still be there AND still be Android-style (%1$d), because shared callers format it with
        // String.format - not with Kotlin's {} interpolation. A generator that "helpfully" rewrote it to
        // {} would compile and then render a literal brace in the UI.
        assertThat(UiStrings.of(UiLanguage.ENGLISH).dbPassphraseDbSummaryTasks).contains("%1\$d")
        assertThat(UiStrings.of(UiLanguage.TAMIL).dbPassphraseDbSummaryTasks).contains("%1\$d")
    }

    @Test
    fun apostrophesAreUnescapedRatherThanRenderedAsBackslashes() {
        // `skip_focus_mode` is "I\'ll choose later" in the ENGLISH file - Android's resource compiler
        // resolves that escape, an XML parser does not. If the generator stopped resolving it, the user
        // would read "I\'ll choose later" on BOTH platforms: a visible shippable defect. Asserted against
        // the English copy specifically, because the Tamil copy is a translation that simply has no
        // Latin contraction in it - asserting English wording on a Tamil string would pass for the wrong
        // reason, or fail for a reason that has nothing to do with the generator.
        val english = UiStrings.of(UiLanguage.ENGLISH).skipFocusMode
        assertThat(english).isEqualTo("I'll choose later")
        assertThat(english).doesNotContain("\\")
    }

    @Test
    fun noShippedStringCarriesAnUnresolvedEscape() {
        // The general form of the check above, over every key in both languages, so a NEW string added
        // with an unresolvable escape is caught here rather than shipping a visible backslash.
        listOf(UiLanguage.ENGLISH, UiLanguage.TAMIL).forEach { language ->
            val strays = mutableListOf<String>()
            UiStrings::class.java.methods
                .filter { it.name.startsWith("get") && it.parameterCount == 0 }
                .forEach { method ->
                    val value = method.invoke(UiStrings.of(language)) as? String ?: return@forEach
                    if (value.contains("\\")) strays += "${method.name}=$value"
                }
            assertThat(strays).isEmpty()
        }
    }

    @Test
    fun languageTagParsingAcceptsBothSeparatorStyles() {
        // Android reports "ta-IN" and the desktop reports "ta_IN"; both must land on Tamil, and an
        // unknown or blank tag must fall back to English rather than throwing.
        assertThat(UiLanguage.of("ta-IN")).isEqualTo(UiLanguage.TAMIL)
        assertThat(UiLanguage.of("ta_IN")).isEqualTo(UiLanguage.TAMIL)
        assertThat(UiLanguage.of("en-US")).isEqualTo(UiLanguage.ENGLISH)
        assertThat(UiLanguage.of("")).isEqualTo(UiLanguage.ENGLISH)
    }

    @Test
    fun anUnknownTagStillYieldsUsableCopy() {
        assertThat(uiStringsFor("fr-FR").dbPassphraseUnlockErrorInvalid)
            .isEqualTo(UiStrings.of(UiLanguage.ENGLISH).dbPassphraseUnlockErrorInvalid)
    }

    private fun tamilBlankKeys(strings: UiStrings): List<String> {
        val blank = mutableListOf<String>()
        UiStrings::class.java.methods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 }
            .forEach { method ->
                val value = method.invoke(strings) as? String ?: return@forEach
                if (value.isBlank()) blank += method.name.removePrefix("get")
            }
        return blank
    }
}
