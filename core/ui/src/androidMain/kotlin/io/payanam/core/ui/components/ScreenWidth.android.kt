//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Android's side of the seam: the configuration's screen width, which already accounts for the window the
 * app is drawing into (split-screen, freeform and per-app display sizes included).
 */
@Composable
actual fun currentScreenWidthDp(): Dp = LocalConfiguration.current.screenWidthDp.dp
