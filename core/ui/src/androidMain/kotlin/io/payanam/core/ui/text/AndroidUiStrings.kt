//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.text

import android.content.Context
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * The Android half of the language seam: turning what the OS reports into a shipped language.
 *
 * `Configuration.locales` reflects a per-app language override as well as the system setting, so a user
 * who set Payanam to Tamil in Android settings gets Tamil copy. `Locale.getDefault()` would miss the
 * override, which is why the resolution goes through the configuration instead.
 *
 * Both halves live here, in the module's Android source set, because they answer the same question in the
 * two shapes its callers need: a composable returns the [UiLanguage] to pass into a shared screen, and the
 * plain function returns the copy table directly for callers that hold a `Context` but no composition.
 * Keeping them apart is what let `:app` grow its own copy of this resolution.
 */
private fun languageTagOf(locales: LocaleList): String = locales[0]?.language.orEmpty()

/** The copy for the language Android is running this app in. */
fun uiStringsFor(context: Context): UiStrings = UiStrings.of(UiLanguage.of(languageTagOf(context.resources.configuration.locales)))

/**
 * The UI language in force for this composition, as Android sees it.
 *
 * Reads `LocalConfiguration` rather than the context's resources so a language change recomposes the
 * screens that pass this value on.
 */
@Composable
fun rememberUiLanguage(): UiLanguage = UiLanguage.of(languageTagOf(LocalConfiguration.current.locales))
