//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.text

/**
 * The UI languages the shared layer ships copy for.
 *
 * Mirrors the `values/` and `values-ta/` pair that Android uses today. The caller decides which one is
 * active, because working out the user's language is an OS interaction (per-app language on Android,
 * `Locale.getDefault()` on the desktop) and this module deliberately contains no OS interaction.
 */
enum class UiLanguage {
    ENGLISH,
    TAMIL,
    ;

    companion object {
        /** Maps an IETF language tag or locale language code to a shipped language. */
        fun of(languageTag: String): UiLanguage =
            if (languageTag.substringBefore('-').substringBefore('_').equals("ta", ignoreCase = true)) {
                TAMIL
            } else {
                ENGLISH
            }
    }
}

/**
 * The copy for [languageTag], or for English when the tag is blank or unrecognized.
 *
 * The counterpart to [UiLanguage.of] for callers that hold a tag but no composition: a repository-backed
 * ViewModel building a label table, or a widget's update path. A composable reads [LocalUiStrings] instead -
 * one value, one mechanism.
 *
 * Working out WHICH tag the user is on stays a platform concern (Android's per-app language, the desktop's
 * default locale); this only turns an already-decided tag into a table.
 */
fun uiStringsFor(languageTag: String?): UiStrings = UiStrings.of(UiLanguage.of(languageTag.orEmpty()))
