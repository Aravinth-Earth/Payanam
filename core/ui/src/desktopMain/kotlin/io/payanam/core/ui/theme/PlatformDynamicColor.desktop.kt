//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/**
 * The desktop has no dynamic colour source, so it answers null and the fallback scheme is used.
 *
 * Null rather than a copy of the fallback: a platform that cannot derive a palette must not claim it did,
 * and returning the fallback here would hide which platforms actually support dynamic colour from the
 * logger and from any future UI that explains the difference.
 *
 * This is the desktop side of the platform-difference register's theming entry. Windows and Linux are the
 * same case — neither exposes an accent-derived Material palette — so one `actual` covers both.
 */
@Composable
actual fun platformDynamicColorScheme(darkTheme: Boolean): ColorScheme? = null
