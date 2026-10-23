//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

import io.payanam.BuildConfig

/**
 * Answers whether THIS BUILD is the F-Droid channel build.
 *
 * The answer is a build fact, stamped in at build time (`-PfdroidBuild=true`), not an installer probe.
 * The probe was wrong because it asked the device who installed the app and compared that name against
 * the exact package `org.fdroid.fdroid`: builds installed by F-Droid Basic, Droid-ify, Neo Store or adb
 * matched nothing and kept the in-app updater, so the app would have replaced itself behind the store
 * client's back - the one thing the store's signature check exists to prevent.
 */
object FdroidBuildCheck {
    /** Returns `true` when this APK was built for the F-Droid channel. */
    fun isFDroidBuild(): Boolean = BuildConfig.FDROID_BUILD
}
