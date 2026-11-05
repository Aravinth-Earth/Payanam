//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/**
 * The width of the surface this composition is rendering into, in dp.
 *
 * `HabitCard` sizes its checkmark row from the available width, and the way to ask for that differs per
 * platform: Android reads `LocalConfiguration.current.screenWidthDp`, which does not exist in the desktop
 * artifact of Compose UI at all. The seam keeps the call site identical on both.
 *
 * A seam rather than a hardcoded width because the answer is a real layout input — a phone in portrait and a
 * desktop window are different widths, and the card must fit whichever it is given.
 */
@Composable
expect fun currentScreenWidthDp(): Dp
