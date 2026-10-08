//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

package io.payanam.feature.settings

/**
 * Decides whether in-app updates must be suppressed.
 *
 * @param buildFlag true when this APK was built for F-Droid.
 * @return true when the app must not offer or run its own updater.
 */
fun shouldSuppressInAppUpdate(buildFlag: Boolean): Boolean = buildFlag
