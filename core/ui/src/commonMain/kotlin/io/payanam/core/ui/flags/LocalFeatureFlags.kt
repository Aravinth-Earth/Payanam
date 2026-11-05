//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.flags

import androidx.compose.runtime.staticCompositionLocalOf
import io.payanam.presentation.flags.FeatureFlagsState

/**
 * The feature flags a shared screen lays itself out from, for the composables that need them.
 *
 * The state itself is [FeatureFlagsState] in `:core:presentation`, not a copy here: a shared ViewModel reads
 * the same flags through [io.payanam.presentation.flags.PlatformFeatureFlags], and two declarations would let
 * a screen render a surface its own state layer had decided not to compute. The platform that owns the build
 * config provides this here, the same way it provides [io.payanam.core.ui.text.LocalUiStrings].
 *
 * Defaults come from the state's own defaults - the full feature set - so a preview or a test renders without
 * a provider.
 */
val LocalFeatureFlags = staticCompositionLocalOf { FeatureFlagsState() }
