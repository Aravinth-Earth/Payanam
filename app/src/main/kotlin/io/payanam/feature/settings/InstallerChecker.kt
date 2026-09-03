//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

package io.payanam.feature.settings

import android.content.Context

/** Detects whether the app was installed from F-Droid. */
object InstallerChecker {
    private const val F_DROID_INSTALLER = "org.fdroid.fdroid"

    fun isF-DroidBuild(context: Context): Boolean =
        try {
            context.packageManager.getInstallerPackageName(context.packageName) == F_DROID_INSTALLER
        } catch (e: Exception) {
            false
        }
}
