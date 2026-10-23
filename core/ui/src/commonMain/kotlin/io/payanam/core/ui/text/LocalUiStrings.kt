//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.text

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The copy for the language the app is currently running in, available to every shared composable.
 *
 * WHY A COMPOSITION LOCAL AND NOT A PARAMETER:
 *
 * Android's screens read their copy through `stringResource(...)`, which resolves against an implicit
 * ambient resource table rather than a value threaded down the tree. The shared screens replace that call
 * with [UiStrings] lookups, so they need the same thing: one ambient table the platform installs once at
 * its root. Passing a `strings: UiStrings` parameter instead would mean adding that parameter to all 253
 * composables - including private section helpers - which is churn with no benefit and a signature that
 * has to be kept in step across two platforms.
 *
 * Using a CompositionLocal also keeps ONE convention: the shared `DatabasePassphraseChangeScreen`
 * previously took a `language: UiLanguage` parameter, and that argument is gone now that the ambient
 * table exists. A screen should never receive the same value twice by two different mechanisms.
 *
 * The ambient table is installed once by [io.payanam.core.ui.navigation.PayanamNavHost] for the whole
 * navigation graph. A shared screen rendered outside the graph (a platform's own startup flow, for
 * example) installs it at that root instead. A read without a provider is a programming error and fails
 * loudly rather than rendering blank labels.
 */
val LocalUiStrings =
    staticCompositionLocalOf<UiStrings> {
        error(
            "LocalUiStrings was read without a provider. Install it at the platform root with " +
                "CompositionLocalProvider(LocalUiStrings provides UiStrings.of(language)).",
        )
    }
