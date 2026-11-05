//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Desktop's side of the seam: the window's own width, converted to dp.
 *
 * There is no screen-wide configuration to read — the app may be one of several windows — so the container
 * the composition renders into is the honest answer, and it is the one Android's `screenWidthDp` corresponds
 * to anyway. Windows and Linux are the same case, so one `actual` covers both.
 */
@Composable
actual fun currentScreenWidthDp(): Dp {
    val containerWidthPx = LocalWindowInfo.current.containerSize.width
    val density = LocalDensity.current
    return with(density) { containerWidthPx.toDp() }
}
