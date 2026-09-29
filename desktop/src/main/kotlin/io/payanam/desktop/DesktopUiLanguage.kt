//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import io.payanam.core.ui.text.UiLanguage
import java.util.Locale

/**
 * The copy language for the SHARED effective language tag, which is what the desktop's settings rows
 * resolve to.
 *
 * `AppPreferencesState.effectiveLanguageTag` already folds the stored choice and the platform's own system
 * language together (`resolveEffectiveLanguageTag`, in `:core:ui`), so this is a pure mapping from that
 * ALREADY-DECIDED tag to a copy table. Nothing here reads the OS, and a caller holding the shared state
 * cannot render a language the settings screen would report differently.
 *
 * Post-unlock callers must use this one. Reading the machine locale instead was the defect this replaced:
 * a desktop whose stored language choice is Tamil rendered English whenever the JVM locale was not `ta`.
 *
 * Kept in `:desktop` rather than `:core:ui` because the shared module contains no OS interaction; the
 * desktop's own seam over the shared tag belongs on this side of that line.
 */
internal fun desktopUiLanguageForTag(effectiveLanguageTag: String): UiLanguage = UiLanguage.of(effectiveLanguageTag)

/**
 * The language of the MACHINE this process runs on - for the startup GATE only.
 *
 * The gate renders the machine locale in EVERY mode it shows, not only the pre-unlock ones. That is not
 * fully forced by the data: most modes really do run with the encrypted database closed and no
 * `effectiveLanguageTag` to read, but `InitializeDatabase` and `FocusModeSelection` are reached after the
 * session-open check (`resolveDesktopStartupMode`, in `DesktopStartupFlow.kt`) and could read the stored
 * row. The gate renders them with its own chrome, which is hardcoded English today, so nothing shows the
 * difference. The shell root reads the shared rows once the gate is gone.
 *
 * Deliberately NOT named as the general desktop language, and deliberately not used after unlock: the name
 * says which side of the gate it belongs to, so a new post-unlock call site reaching for it is visible in
 * review rather than silently reinstating the machine-locale bug.
 */
internal fun desktopMachineUiLanguage(): UiLanguage = UiLanguage.of(Locale.getDefault().language)
